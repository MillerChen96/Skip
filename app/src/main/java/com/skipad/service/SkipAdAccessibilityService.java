package com.skipad.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 跳过广告的无障碍服务
 * 自动检测并点击跳过按钮
 * 
 * 关键改进：
 * 1. 只在应用启动时检测（窗口切换）
 * 2. 每个应用每次启动只检测一次
 * 3. 更严格的跳过按钮判断
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    
    // 精确匹配的跳过按钮文本（必须完全匹配或非常接近）
    private static final String[] EXACT_SKIP_TEXTS = {
        "跳过", "关闭", "跳过广告", "关闭广告",
        "skip", "close", "skip ad", "close ad", "dismiss",
        "立即跳过", "点击跳过", "跳过此广告", "跳过视频",
        "跳过开屏", "跳过启动页"
    };
    
    // 包含倒计时的跳过文本模式（如 "3秒后跳过", "5s" 等）
    private static final Pattern COUNTDOWN_PATTERN = Pattern.compile(
        "(\\d+\\s*[秒s].*跳过|跳过.*\\d+\\s*[秒s]|倒计时.*跳过|跳过.*倒计时)",
        Pattern.CASE_INSENSITIVE
    );
    
    // 跳过按钮的常见ID关键词（更严格）
    private static final String[] SKIP_ID_KEYWORDS = {
        "splash_skip", "splash_close", "ad_skip", "ad_close",
        "skip_ad", "close_ad", "launch_skip", "launch_close"
    };
    
    // 广告相关的类名关键词
    private static final String[] AD_ACTIVITY_KEYWORDS = {
        "splash", "launch", "ad", "welcome", "start"
    };
    
    private Handler handler;
    private SharedPreferences preferences;
    private int skipCount = 0;
    
    // 记录每个应用的启动时间
    private Map<String, Long> appLaunchTimes = new HashMap<>();
    // 记录每个应用本次启动是否已经处理过
    private Set<String> processedApps = new HashSet<>();
    
    // 应用启动后检测广告的时间窗口（毫秒）
    private static final long AD_DETECT_WINDOW = 8000; // 8秒内认为是开屏广告
    
    // 最小点击间隔
    private static final long MIN_CLICK_INTERVAL = 2000; // 2秒
    
    private long lastClickTime = 0;
    private String lastPackageName = "";
    
    private static SkipAdAccessibilityService instance;
    
    public static SkipAdAccessibilityService getInstance() {
        return instance;
    }
    
    public static boolean isServiceEnabled() {
        return instance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        preferences = getSharedPreferences("skip_ad_prefs", MODE_PRIVATE);
        skipCount = preferences.getInt("skip_count", 0);
        Log.d(TAG, "服务创建，已跳过广告次数: " + skipCount);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d(TAG, "服务销毁");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        int eventType = event.getEventType();
        
        // 只监听窗口状态变化（应用切换）
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String packageName = event.getPackageName() != null ? 
                event.getPackageName().toString() : "";
            String className = event.getClassName() != null ? 
                event.getClassName().toString() : "";
            
            // 忽略系统界面和本应用
            if (isSystemPackage(packageName) || packageName.equals(getPackageName())) {
                return;
            }
            
            // 检测到应用切换
            if (!packageName.equals(lastPackageName)) {
                Log.d(TAG, "应用切换: " + lastPackageName + " -> " + packageName);
                
                // 记录新应用的启动时间
                appLaunchTimes.put(packageName, System.currentTimeMillis());
                // 清除该应用的处理标记
                processedApps.remove(packageName);
                
                lastPackageName = packageName;
            }
            
            // 检查是否在广告检测时间窗口内
            Long launchTime = appLaunchTimes.get(packageName);
            if (launchTime == null) {
                launchTime = System.currentTimeMillis();
                appLaunchTimes.put(packageName, launchTime);
            }
            
            long timeSinceLaunch = System.currentTimeMillis() - launchTime;
            
            // 只在应用启动后的时间窗口内检测，且未处理过
            if (timeSinceLaunch < AD_DETECT_WINDOW && !processedApps.contains(packageName)) {
                // 检查当前界面是否可能是广告界面
                if (isPossibleAdScreen(packageName, className)) {
                    Log.d(TAG, "检测到可能的广告界面: " + className);
                    
                    // 延迟检测，等待广告加载
                    handler.postDelayed(() -> {
                        if (!processedApps.contains(packageName)) {
                            findAndClickSkipButton(packageName);
                        }
                    }, 500);
                }
            }
        }
    }
    
    /**
     * 检查是否是系统包
     */
    private boolean isSystemPackage(String packageName) {
        if (packageName == null) return true;
        return packageName.startsWith("com.android.") ||
               packageName.startsWith("android.") ||
               packageName.equals("com.android.systemui") ||
               packageName.equals("com.android.launcher") ||
               packageName.equals("com.android.launcher3") ||
               packageName.contains("launcher");
    }
    
    /**
     * 检查当前界面是否可能是广告界面
     */
    private boolean isPossibleAdScreen(String packageName, String className) {
        if (className == null || className.isEmpty()) return false;
        
        String lowerClassName = className.toLowerCase();
        
        // 检查类名是否包含广告相关关键词
        for (String keyword : AD_ACTIVITY_KEYWORDS) {
            if (lowerClassName.contains(keyword)) {
                return true;
            }
        }
        
        // 如果是主Activity，也可能是开屏广告
        if (lowerClassName.contains("main") || lowerClassName.contains("home")) {
            return true;
        }
        
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "服务被中断");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "无障碍服务已连接");
        startKeepAliveService();
    }

    /**
     * 查找并点击跳过按钮
     */
    private void findAndClickSkipButton(String packageName) {
        // 防止频繁点击
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < MIN_CLICK_INTERVAL) {
            return;
        }
        
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.d(TAG, "无法获取根节点");
            return;
        }
        
        // 标记该应用已处理
        processedApps.add(packageName);
        
        // 查找跳过按钮
        AccessibilityNodeInfo skipButton = findSkipButton(rootNode);
        
        if (skipButton != null) {
            Log.d(TAG, "找到跳过按钮: " + getNodeDescription(skipButton));
            
            if (performClick(skipButton)) {
                lastClickTime = currentTime;
                skipCount++;
                saveSkipCount();
                Log.d(TAG, "成功点击跳过按钮，总次数: " + skipCount);
            }
        } else {
            // 如果没找到，移除处理标记，允许再次检测
            processedApps.remove(packageName);
        }
        
        rootNode.recycle();
    }
    
    /**
     * 查找跳过按钮（更严格的判断）
     */
    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root) {
        if (root == null) return null;
        
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        
        // 遍历所有节点
        findSkipButtonRecursive(root, candidates);
        
        // 返回最可能的跳过按钮
        if (!candidates.isEmpty()) {
            // 优先选择右上角的按钮
            for (AccessibilityNodeInfo node : candidates) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                
                // 检查是否在右上角区域
                if (bounds.right > getScreenWidth(root) * 0.6f && 
                    bounds.top < getScreenHeight(root) * 0.4f) {
                    return node;
                }
            }
            
            // 否则返回第一个
            return candidates.get(0);
        }
        
        return null;
    }
    
    private void findSkipButtonRecursive(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> candidates) {
        if (node == null) return;
        
        // 检查文本
        CharSequence textSeq = node.getText();
        if (textSeq != null) {
            String text = textSeq.toString().trim();
            
            // 精确匹配
            for (String skipText : EXACT_SKIP_TEXTS) {
                if (text.equalsIgnoreCase(skipText)) {
                    Log.d(TAG, "精确匹配跳过按钮: " + text);
                    candidates.add(node);
                    return;
                }
            }
            
            // 倒计时模式匹配
            if (COUNTDOWN_PATTERN.matcher(text).find()) {
                Log.d(TAG, "倒计时跳过按钮: " + text);
                candidates.add(node);
                return;
            }
            
            // 包含"跳过"且长度较短（排除正常内容）
            if (text.length() <= 15 && text.contains("跳过")) {
                Log.d(TAG, "短文本跳过按钮: " + text);
                candidates.add(node);
                return;
            }
        }
        
        // 检查内容描述
        CharSequence descSeq = node.getContentDescription();
        if (descSeq != null) {
            String desc = descSeq.toString().trim();
            for (String skipText : EXACT_SKIP_TEXTS) {
                if (desc.equalsIgnoreCase(skipText)) {
                    Log.d(TAG, "内容描述匹配跳过按钮: " + desc);
                    candidates.add(node);
                    return;
                }
            }
        }
        
        // 检查ID
        String id = node.getViewIdResourceName();
        if (id != null) {
            String lowerId = id.toLowerCase();
            for (String idKeyword : SKIP_ID_KEYWORDS) {
                if (lowerId.contains(idKeyword)) {
                    Log.d(TAG, "ID匹配跳过按钮: " + id);
                    candidates.add(node);
                    return;
                }
            }
        }
        
        // 递归检查子节点
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findSkipButtonRecursive(child, candidates);
            }
        }
    }
    
    private int getScreenWidth(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds.width() > 0 ? bounds.width() : 1080;
    }
    
    private int getScreenHeight(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds.height() > 0 ? bounds.height() : 1920;
    }
    
    private String getNodeDescription(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        if (node.getText() != null) {
            sb.append("text=").append(node.getText());
        }
        if (node.getContentDescription() != null) {
            sb.append(" desc=").append(node.getContentDescription());
        }
        if (node.getViewIdResourceName() != null) {
            sb.append(" id=").append(node.getViewIdResourceName());
        }
        return sb.toString();
    }

    /**
     * 执行点击操作
     */
    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        // 尝试直接点击
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        
        // 尝试点击父节点
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                boolean clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                return clicked;
            }
            AccessibilityNodeInfo grandParent = parent.getParent();
            parent.recycle();
            parent = grandParent;
        }
        
        // 尝试通过手势点击
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        float x = bounds.exactCenterX();
        float y = bounds.exactCenterY();
        
        return performGestureClick(x, y);
    }

    /**
     * 通过手势执行点击
     */
    private boolean performGestureClick(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        
        GestureDescription gesture = builder.build();
        return dispatchGesture(gesture, null, null);
    }

    /**
     * 保存跳过次数
     */
    private void saveSkipCount() {
        preferences.edit().putInt("skip_count", skipCount).apply();
    }

    /**
     * 获取跳过次数
     */
    public int getSkipCount() {
        return skipCount;
    }

    /**
     * 重置跳过次数
     */
    public void resetSkipCount() {
        skipCount = 0;
        saveSkipCount();
    }

    /**
     * 启动保活服务
     */
    private void startKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
