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
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    
    // 跳过按钮文本关键词（包含匹配）
    private static final String[] SKIP_TEXT_KEYWORDS = {
        "跳过", "关闭", "skip", "close", "dismiss",
        "跳过广告", "关闭广告", "跳过此广告", "跳过开屏"
    };
    
    // 倒计时模式（如 "3秒后跳过", "5s", "倒计时3秒" 等）
    private static final Pattern COUNTDOWN_PATTERN = Pattern.compile(
        "(\\d+\\s*[秒s].*跳|跳.*\\d+\\s*[秒s]|倒计时|countdown|\\d+\\s*s)",
        Pattern.CASE_INSENSITIVE
    );
    
    // 跳过按钮的常见ID关键词
    private static final String[] SKIP_ID_KEYWORDS = {
        "skip", "close", "jump", "dismiss",
        "splash_skip", "splash_close", "ad_skip", "ad_close",
        "skip_ad", "close_ad", "launch_skip", "launch_close",
        "btn_skip", "btn_close", "tv_skip", "tv_close",
        "iv_skip", "iv_close", "skip_button", "close_button"
    };
    
    // 需要特别支持的应用包名
    private static final Map<String, String[]> APP_SKIP_KEYWORDS = new HashMap<>();
    static {
        APP_SKIP_KEYWORDS.put("com.autonavi.minimap", new String[]{"跳过", "跳过广告", "关闭"}); // 高德地图
        APP_SKIP_KEYWORDS.put("com.douban.frodo", new String[]{"跳过", "跳过广告", "关闭"}); // 豆瓣
        APP_SKIP_KEYWORDS.put("com.taobao.taobao", new String[]{"跳过", "关闭", "skip"}); // 淘宝
        APP_SKIP_KEYWORDS.put("com.jingdong.app.mall", new String[]{"跳过", "关闭"}); // 京东
        APP_SKIP_KEYWORDS.put("com.ss.android.ugc.aweme", new String[]{"跳过", "跳过广告"}); // 抖音
        APP_SKIP_KEYWORDS.put("com.tencent.mm", new String[]{"跳过", "关闭"}); // 微信
    }
    
    private Handler handler;
    private SharedPreferences preferences;
    private int skipCount = 0;
    
    // 记录每个应用的启动时间
    private Map<String, Long> appLaunchTimes = new HashMap<>();
    // 记录每个应用本次启动是否已经成功点击过跳过按钮
    private Set<String> clickedApps = new HashSet<>();
    
    // 应用启动后检测广告的时间窗口（毫秒）
    private static final long AD_DETECT_WINDOW = 10000; // 10秒
    
    // 最小点击间隔
    private static final long MIN_CLICK_INTERVAL = 1500;
    
    // 检测间隔
    private static final long DETECT_INTERVAL = 500;
    
    private long lastClickTime = 0;
    private String lastPackageName = "";
    private int detectCount = 0;
    
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
        
        // 监听窗口状态变化
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
                // 清除该应用的点击标记
                clickedApps.remove(packageName);
                detectCount = 0;
                
                lastPackageName = packageName;
            }
            
            // 开始检测
            startAdDetection(packageName);
        }
    }
    
    /**
     * 开始广告检测
     */
    private void startAdDetection(String packageName) {
        // 如果已经点击过，不再检测
        if (clickedApps.contains(packageName)) {
            return;
        }
        
        // 检查是否在检测时间窗口内
        Long launchTime = appLaunchTimes.get(packageName);
        if (launchTime == null) {
            launchTime = System.currentTimeMillis();
            appLaunchTimes.put(packageName, launchTime);
        }
        
        long timeSinceLaunch = System.currentTimeMillis() - launchTime;
        if (timeSinceLaunch > AD_DETECT_WINDOW) {
            return;
        }
        
        // 延迟检测，等待界面加载
        handler.postDelayed(() -> {
            if (!clickedApps.contains(packageName)) {
                detectAndClickSkipButton(packageName);
            }
        }, DETECT_INTERVAL);
    }
    
    /**
     * 检查是否是系统包
     */
    private boolean isSystemPackage(String packageName) {
        if (packageName == null) return true;
        return packageName.startsWith("com.android.") ||
               packageName.startsWith("android.") ||
               packageName.equals("com.android.systemui") ||
               packageName.contains("launcher");
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
     * 检测并点击跳过按钮
     */
    private void detectAndClickSkipButton(String packageName) {
        // 防止频繁点击
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < MIN_CLICK_INTERVAL) {
            return;
        }
        
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.d(TAG, "无法获取根节点");
            scheduleNextDetection(packageName);
            return;
        }
        
        // 查找跳过按钮
        AccessibilityNodeInfo skipButton = findSkipButton(rootNode, packageName);
        
        if (skipButton != null) {
            Log.d(TAG, "找到跳过按钮: " + getNodeDescription(skipButton));
            
            if (performClick(skipButton)) {
                lastClickTime = currentTime;
                skipCount++;
                saveSkipCount();
                clickedApps.add(packageName);
                Log.d(TAG, "成功点击跳过按钮，总次数: " + skipCount);
            }
        } else {
            detectCount++;
            // 最多检测10次
            if (detectCount < 10) {
                scheduleNextDetection(packageName);
            }
        }
        
        rootNode.recycle();
    }
    
    /**
     * 安排下一次检测
     */
    private void scheduleNextDetection(String packageName) {
        Long launchTime = appLaunchTimes.get(packageName);
        if (launchTime != null) {
            long timeSinceLaunch = System.currentTimeMillis() - launchTime;
            if (timeSinceLaunch < AD_DETECT_WINDOW && !clickedApps.contains(packageName)) {
                handler.postDelayed(() -> detectAndClickSkipButton(packageName), DETECT_INTERVAL);
            }
        }
    }
    
    /**
     * 查找跳过按钮
     */
    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root, String packageName) {
        if (root == null) return null;
        
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        
        // 获取该应用的特定关键词
        String[] appKeywords = APP_SKIP_KEYWORDS.get(packageName);
        
        // 遍历所有节点查找跳过按钮
        findSkipButtonRecursive(root, candidates, appKeywords);
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        // 优先选择右上角的按钮
        int screenWidth = getScreenWidth(root);
        int screenHeight = getScreenHeight(root);
        
        for (AccessibilityNodeInfo node : candidates) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            
            // 右上角区域 (右上角 40% 宽度，顶部 30% 高度)
            boolean isTopRight = bounds.right > screenWidth * 0.5f && 
                                 bounds.top < screenHeight * 0.35f;
            
            // 右下角区域
            boolean isBottomRight = bounds.right > screenWidth * 0.5f && 
                                    bounds.bottom > screenHeight * 0.65f;
            
            if (isTopRight || isBottomRight) {
                return node;
            }
        }
        
        // 否则返回第一个候选
        return candidates.get(0);
    }
    
    private void findSkipButtonRecursive(AccessibilityNodeInfo node, 
            List<AccessibilityNodeInfo> candidates, String[] appKeywords) {
        if (node == null) return;
        
        boolean isCandidate = false;
        
        // 1. 检查文本
        CharSequence textSeq = node.getText();
        if (textSeq != null) {
            String text = textSeq.toString().trim();
            
            // 检查应用特定关键词
            if (appKeywords != null) {
                for (String keyword : appKeywords) {
                    if (text.contains(keyword) || text.equalsIgnoreCase(keyword)) {
                        Log.d(TAG, "应用特定关键词匹配: " + text);
                        isCandidate = true;
                        break;
                    }
                }
            }
            
            // 检查通用关键词
            if (!isCandidate) {
                for (String keyword : SKIP_TEXT_KEYWORDS) {
                    if (text.toLowerCase().contains(keyword.toLowerCase())) {
                        // 确保文本不太长（排除正常内容）
                        if (text.length() <= 20) {
                            Log.d(TAG, "通用关键词匹配: " + text);
                            isCandidate = true;
                            break;
                        }
                    }
                }
            }
            
            // 检查倒计时模式
            if (!isCandidate && COUNTDOWN_PATTERN.matcher(text).find()) {
                Log.d(TAG, "倒计时模式匹配: " + text);
                isCandidate = true;
            }
        }
        
        // 2. 检查内容描述
        if (!isCandidate) {
            CharSequence descSeq = node.getContentDescription();
            if (descSeq != null) {
                String desc = descSeq.toString().trim();
                for (String keyword : SKIP_TEXT_KEYWORDS) {
                    if (desc.toLowerCase().contains(keyword.toLowerCase())) {
                        if (desc.length() <= 20) {
                            Log.d(TAG, "内容描述匹配: " + desc);
                            isCandidate = true;
                            break;
                        }
                    }
                }
            }
        }
        
        // 3. 检查ID
        if (!isCandidate) {
            String id = node.getViewIdResourceName();
            if (id != null) {
                String lowerId = id.toLowerCase();
                for (String idKeyword : SKIP_ID_KEYWORDS) {
                    if (lowerId.contains(idKeyword.toLowerCase())) {
                        Log.d(TAG, "ID匹配: " + id);
                        isCandidate = true;
                        break;
                    }
                }
            }
        }
        
        // 4. 检查是否是小尺寸的可点击元素（可能是关闭按钮）
        if (!isCandidate) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int width = bounds.width();
            int height = bounds.height();
            
            // 小尺寸按钮 (30-150dp)
            if (width > 30 && width < 150 && height > 30 && height < 150) {
                // 检查是否包含关闭符号
                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                
                String textStr = text != null ? text.toString().trim() : "";
                String descStr = desc != null ? desc.toString().trim() : "";
                
                if (textStr.equals("×") || textStr.equals("X") || textStr.equals("x") ||
                    descStr.equals("×") || descStr.equals("X") || descStr.equals("x") ||
                    textStr.equals("关闭") || descStr.equals("关闭")) {
                    Log.d(TAG, "小尺寸关闭按钮: " + textStr);
                    isCandidate = true;
                }
            }
        }
        
        if (isCandidate) {
            candidates.add(node);
        }
        
        // 递归检查子节点
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findSkipButtonRecursive(child, candidates, appKeywords);
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
