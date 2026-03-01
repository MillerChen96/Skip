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
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 跳过广告的无障碍服务
 * 
 * 参考开源项目 zfdang/Android-Touch-Helper (5.3k stars) 的实现
 * 
 * 核心策略：
 * 1. 同时监听 WINDOW_STATE_CHANGED 和 WINDOW_CONTENT_CHANGED
 * 2. 关键词匹配：文本长度 <= 关键词长度 + 6
 * 3. 多按钮时优先点击右上角的小按钮
 * 4. 使用 clickedWidgets 防止重复点击
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    
    // 跳过按钮关键词（扩展列表）
    // 参考 Android-Touch-Helper，默认只有"跳过"，用户可自定义
    private static final String[] KEYWORDS = {
        "跳过", "关闭", "跳过广告", "关闭广告", "立即跳过",
        "skip", "close", "dismiss", "跳过 >", "×", "X"
    };
    
    // 跳过按钮ID关键词
    private static final String[] SKIP_IDS = {
        "skip", "close", "jump", "dismiss", "cancel",
        "splash", "ad_skip", "ad_close", "btn_skip", "btn_close"
    };
    
    // 检测配置
    private static final long SKIP_AD_DURATION = 8000;  // 跳过广告检测持续时间
    
    private Handler handler;
    private SharedPreferences preferences;
    private int skipCount = 0;
    
    // 当前应用信息
    private String currentPackageName = "";
    private String currentActivityName = "";
    
    // 跳过广告状态
    private boolean skipAdRunning = false;
    private boolean skipAdByKeyword = false;
    private Set<String> clickedWidgets;  // 已点击的控件，防止重复点击
    
    // 屏幕尺寸
    private int screenWidth = 1080;
    private int screenHeight = 1920;
    
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
        clickedWidgets = new HashSet<>();
        Log.d(TAG, "服务创建");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        stopSkipAdProcess();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        CharSequence pkgNameSeq = event.getPackageName();
        CharSequence classNameSeq = event.getClassName();
        if (pkgNameSeq == null || classNameSeq == null) return;
        
        String pkgName = pkgNameSeq.toString();
        String className = classNameSeq.toString();
        
        // 忽略系统应用和自身
        if (isSystemPackage(pkgName)) return;
        
        // 判断是否是Activity
        boolean isActivity = !className.startsWith("android.") && !className.startsWith("androidx.");
        
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                // 应用切换
                if (!pkgName.equals(currentPackageName)) {
                    if (isActivity) {
                        Log.d(TAG, "应用切换: " + currentPackageName + " -> " + pkgName);
                        stopSkipAdProcess();
                        currentPackageName = pkgName;
                        currentActivityName = className;
                        startSkipAdProcess();
                    }
                } else {
                    // 同一应用内Activity切换
                    if (isActivity && !className.equals(currentActivityName)) {
                        Log.d(TAG, "Activity切换: " + currentActivityName + " -> " + className);
                        currentActivityName = className;
                    }
                }
                // 执行跳过广告检测
                if (skipAdByKeyword) {
                    final AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        new Thread(() -> {
                            findAndClickAllSkipButtons(root);
                            root.recycle();
                        }).start();
                    }
                }
                break;
                
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                // 窗口内容变化时也检测
                if (skipAdByKeyword && pkgName.equals(currentPackageName)) {
                    final AccessibilityNodeInfo source = event.getSource();
                    if (source != null) {
                        new Thread(() -> {
                            findAndClickAllSkipButtons(source);
                            source.recycle();
                        }).start();
                    }
                }
                break;
        }
    }
    
    /**
     * 开始跳过广告流程
     */
    private void startSkipAdProcess() {
        Log.d(TAG, "开始跳过广告流程");
        skipAdRunning = true;
        skipAdByKeyword = true;
        clickedWidgets.clear();
        
        // 设置超时停止
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::stopSkipAdProcess, SKIP_AD_DURATION);
    }
    
    /**
     * 停止跳过广告流程
     */
    private void stopSkipAdProcess() {
        if (!skipAdRunning) return;
        Log.d(TAG, "停止跳过广告流程");
        skipAdRunning = false;
        skipAdByKeyword = false;
        handler.removeCallbacksAndMessages(null);
    }
    
    /**
     * 查找并点击所有跳过按钮
     * 当有多个跳过按钮时，按优先级排序后依次点击
     */
    private void findAndClickAllSkipButtons(AccessibilityNodeInfo root) {
        if (root == null || !skipAdRunning) return;
        
        // 更新屏幕尺寸
        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);
        screenWidth = screenBounds.width();
        screenHeight = screenBounds.height();
        
        // 收集所有匹配的节点
        List<AccessibilityNodeInfo> matchedNodes = new ArrayList<>();
        collectMatchedNodes(root, matchedNodes);
        
        if (matchedNodes.isEmpty()) return;
        
        // 按优先级排序：右上角小按钮优先
        Collections.sort(matchedNodes, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect rectA = new Rect();
                Rect rectB = new Rect();
                a.getBoundsInScreen(rectA);
                b.getBoundsInScreen(rectB);
                
                // 计算优先级分数
                int scoreA = calculatePriorityScore(rectA);
                int scoreB = calculatePriorityScore(rectB);
                
                return scoreB - scoreA; // 降序
            }
            
            private int calculatePriorityScore(Rect rect) {
                int score = 0;
                
                // 右上角优先
                if (rect.right > screenWidth * 0.6f && rect.top < screenHeight * 0.3f) {
                    score += 100;
                }
                // 右下角次之
                else if (rect.right > screenWidth * 0.6f && rect.bottom > screenHeight * 0.7f) {
                    score += 80;
                }
                // 右侧其他位置
                else if (rect.right > screenWidth * 0.5f) {
                    score += 50;
                }
                
                // 小按钮优先（更可能是跳过按钮）
                int size = rect.width() * rect.height();
                if (size < 10000) {
                    score += 50;
                } else if (size < 20000) {
                    score += 30;
                }
                
                return score;
            }
        });
        
        // 尝试点击每个匹配的节点
        for (AccessibilityNodeInfo node : matchedNodes) {
            if (!skipAdRunning) break;
            
            String nodeDesc = describeNode(node);
            
            // 检查是否已点击过
            if (clickedWidgets.contains(nodeDesc)) {
                continue;
            }
            
            Log.d(TAG, "尝试点击跳过按钮: " + nodeDesc);
            clickedWidgets.add(nodeDesc);
            
            // 执行点击
            if (performClick(node)) {
                skipCount++;
                saveSkipCount();
                Log.d(TAG, "点击成功，总次数: " + skipCount);
                
                // 点击成功后稍等，避免连续点击
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
    
    /**
     * 收集所有匹配关键词的节点
     */
    private void collectMatchedNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> matchedNodes) {
        if (node == null) return;
        
        // 检查是否匹配
        if (isMatchKeyword(node)) {
            matchedNodes.add(node);
        }
        
        // 递归检查子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectMatchedNodes(child, matchedNodes);
            }
        }
    }
    
    /**
     * 检查节点是否匹配关键词
     * 参考 Android-Touch-Helper 的 skipAdByKeywords 方法
     */
    private boolean isMatchKeyword(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        
        // 检查文本
        if (!TextUtils.isEmpty(text)) {
            String textStr = text.toString();
            for (String keyword : KEYWORDS) {
                // 文本长度 <= 关键词长度 + 6
                if (textStr.length() <= keyword.length() + 6 && textStr.contains(keyword)) {
                    return true;
                }
            }
        }
        
        // 检查描述
        if (!TextUtils.isEmpty(description)) {
            String descStr = description.toString();
            for (String keyword : KEYWORDS) {
                if (descStr.length() <= keyword.length() + 6 && descStr.contains(keyword)) {
                    return true;
                }
            }
        }
        
        // 检查ID
        String id = node.getViewIdResourceName();
        if (id != null) {
            String lowerId = id.toLowerCase();
            for (String skipId : SKIP_IDS) {
                if (lowerId.contains(skipId)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 生成节点描述
     */
    private String describeNode(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        
        if (node.getText() != null) {
            sb.append("text:").append(node.getText());
        }
        if (node.getContentDescription() != null) {
            sb.append(" desc:").append(node.getContentDescription());
        }
        if (node.getViewIdResourceName() != null) {
            sb.append(" id:").append(node.getViewIdResourceName());
        }
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        sb.append(" bounds:").append(bounds.toShortString());
        
        sb.append("]");
        return sb.toString();
    }
    
    private boolean isSystemPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        return packageName.startsWith("com.android.") ||
               packageName.startsWith("android.") ||
               packageName.contains("launcher") ||
               packageName.contains("systemui") ||
               packageName.equals(getPackageName());
    }
    
    /**
     * 执行点击
     */
    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        // 尝试直接点击
        if (node.isClickable()) {
            boolean success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (success) return true;
        }
        
        // 尝试点击父节点
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                boolean success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                if (success) return true;
                break;
            }
            AccessibilityNodeInfo grandParent = parent.getParent();
            parent.recycle();
            parent = grandParent;
        }
        
        // 使用手势点击
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return performGestureClick(bounds.centerX(), bounds.centerY());
    }
    
    /**
     * 手势点击
     */
    private boolean performGestureClick(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 40));
        
        return dispatchGesture(builder.build(), null, null);
    }

    @Override
    public void onInterrupt() {
        stopSkipAdProcess();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "无障碍服务已连接");
        startKeepAliveService();
    }

    private void saveSkipCount() {
        preferences.edit().putInt("skip_count", skipCount).apply();
    }

    public int getSkipCount() {
        return skipCount;
    }

    public void resetSkipCount() {
        skipCount = 0;
        saveSkipCount();
    }

    private void startKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
