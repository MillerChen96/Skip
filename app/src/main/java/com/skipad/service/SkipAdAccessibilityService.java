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
import java.util.Collections;
import java.util.List;

/**
 * 跳过广告的无障碍服务
 * 
 * 核心策略：
 * 1. 只在应用启动后短暂的广告检测窗口内工作
 * 2. 应用切换到其他Activity后立即停止检测
 * 3. 使用严格的特征评分，避免误点击
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    
    // 跳过按钮文本特征
    private static final String[] SKIP_TEXTS = {
        "跳过", "关闭", "skip", "close", "dismiss", "jump",
        "×", "X", "x", "跳过广告", "关闭广告"
    };
    
    // 跳过按钮ID特征
    private static final String[] SKIP_IDS = {
        "skip", "close", "jump", "dismiss", "cancel"
    };
    
    // 检测配置
    private static final long AD_DETECT_WINDOW = 6000;   // 广告检测窗口：6秒
    private static final long DETECT_INTERVAL = 200;     // 检测间隔：200ms
    private static final int MAX_DETECT_COUNT = 10;      // 最大检测次数
    private static final long MIN_CLICK_INTERVAL = 1500; // 最小点击间隔
    
    // 按钮尺寸限制（像素）
    private static final int MIN_BUTTON_SIZE = 25;       // 最小25dp
    private static final int MAX_BUTTON_SIZE = 120;      // 最大120dp（更严格）
    
    // 位置阈值
    private static final float RIGHT_THRESHOLD = 0.6f;   // 右60%区域
    private static final float TOP_THRESHOLD = 0.3f;     // 顶部30%
    private static final float BOTTOM_THRESHOLD = 0.7f;  // 底部30%
    
    private Handler handler;
    private SharedPreferences preferences;
    private int skipCount = 0;
    
    private String currentPackage = "";
    private String currentActivity = "";
    private long appLaunchTime = 0;
    private int detectCount = 0;
    private boolean hasClicked = false;
    private boolean isDetecting = false;
    private long lastClickTime = 0;
    
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
        Log.d(TAG, "服务创建");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
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
            
            // 忽略系统和自身
            if (isSystemPackage(packageName)) return;
            
            // 检测Activity切换
            if (!packageName.equals(currentPackage)) {
                // 应用切换
                Log.d(TAG, "应用切换: " + currentPackage + " -> " + packageName);
                resetDetection();
                currentPackage = packageName;
                appLaunchTime = System.currentTimeMillis();
                startDetection();
            } else if (!className.equals(currentActivity)) {
                // 同一应用内Activity切换
                Log.d(TAG, "Activity切换: " + currentActivity + " -> " + className);
                
                // 如果已经点击过跳过按钮，停止检测
                if (hasClicked) {
                    Log.d(TAG, "已点击跳过按钮，停止检测");
                    stopDetection();
                } else {
                    // 检查是否还在广告检测窗口内
                    long elapsed = System.currentTimeMillis() - appLaunchTime;
                    if (elapsed > AD_DETECT_WINDOW) {
                        Log.d(TAG, "超过检测窗口，停止检测");
                        stopDetection();
                    } else {
                        // 继续检测
                        currentActivity = className;
                    }
                }
            }
        }
    }
    
    private void resetDetection() {
        currentActivity = "";
        detectCount = 0;
        hasClicked = false;
        stopDetection();
    }
    
    private void startDetection() {
        if (isDetecting || hasClicked) return;
        
        long elapsed = System.currentTimeMillis() - appLaunchTime;
        if (elapsed > AD_DETECT_WINDOW) {
            Log.d(TAG, "超过检测窗口，不启动检测");
            return;
        }
        
        isDetecting = true;
        detectSkipButton();
    }
    
    private void stopDetection() {
        isDetecting = false;
        handler.removeCallbacksAndMessages(null);
    }
    
    private void detectSkipButton() {
        if (!isDetecting || hasClicked) return;
        
        long elapsed = System.currentTimeMillis() - appLaunchTime;
        if (elapsed > AD_DETECT_WINDOW) {
            Log.d(TAG, "超过检测窗口，停止检测");
            stopDetection();
            return;
        }
        
        if (detectCount >= MAX_DETECT_COUNT) {
            Log.d(TAG, "达到最大检测次数，停止检测");
            stopDetection();
            return;
        }
        
        detectCount++;
        
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleNextDetection();
            return;
        }
        
        // 更新屏幕尺寸
        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);
        screenWidth = screenBounds.width();
        screenHeight = screenBounds.height();
        
        // 查找最佳跳过按钮候选
        AccessibilityNodeInfo skipButton = findBestSkipButton(root);
        
        if (skipButton != null) {
            Log.d(TAG, "找到跳过按钮: " + describeNode(skipButton));
            
            if (performClick(skipButton)) {
                hasClicked = true;
                skipCount++;
                saveSkipCount();
                Log.d(TAG, "点击成功，总次数: " + skipCount);
                stopDetection();
            } else {
                scheduleNextDetection();
            }
        } else {
            scheduleNextDetection();
        }
        
        root.recycle();
    }
    
    private void scheduleNextDetection() {
        if (!isDetecting || hasClicked) return;
        
        long elapsed = System.currentTimeMillis() - appLaunchTime;
        if (elapsed > AD_DETECT_WINDOW) {
            stopDetection();
            return;
        }
        
        handler.postDelayed(this::detectSkipButton, DETECT_INTERVAL);
    }
    
    /**
     * 查找最佳跳过按钮（更严格的评分）
     */
    private AccessibilityNodeInfo findBestSkipButton(AccessibilityNodeInfo root) {
        List<ScoredNode> candidates = new ArrayList<>();
        
        collectCandidates(root, candidates);
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        // 按评分排序
        Collections.sort(candidates, (a, b) -> Integer.compare(b.score, a.score));
        
        Log.d(TAG, "候选数量: " + candidates.size() + ", 最高分: " + candidates.get(0).score);
        
        // 评分必须 ≥ 60 才认为是跳过按钮（更严格）
        if (candidates.get(0).score >= 60) {
            return candidates.get(0).node;
        }
        
        return null;
    }
    
    private void collectCandidates(AccessibilityNodeInfo node, List<ScoredNode> candidates) {
        if (node == null) return;
        
        int score = calculateScore(node);
        
        if (score >= 40) {
            candidates.add(new ScoredNode(node, score));
        }
        
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectCandidates(child, candidates);
            }
        }
    }
    
    /**
     * 计算节点作为跳过按钮的评分（更严格）
     * 
     * 评分因素：
     * - 文本完全匹配 (+50)
     * - ID包含skip/close (+30)
     * - 位置在右上角/右下角 (+25)
     * - 尺寸很小 (+15)
     * - 可点击 (+10)
     * 
     * 总分需要 ≥ 60 才被认为是跳过按钮
     */
    private int calculateScore(AccessibilityNodeInfo node) {
        int score = 0;
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int width = bounds.width();
        int height = bounds.height();
        
        // 1. 尺寸检查（更严格）
        if (width < MIN_BUTTON_SIZE || width > MAX_BUTTON_SIZE ||
            height < MIN_BUTTON_SIZE || height > MAX_BUTTON_SIZE) {
            return 0;
        }
        score += 15;
        
        // 2. 位置评分（必须在右上角或右下角）
        boolean isRightSide = bounds.right > screenWidth * RIGHT_THRESHOLD;
        boolean isTopArea = bounds.top < screenHeight * TOP_THRESHOLD;
        boolean isBottomArea = bounds.bottom > screenHeight * BOTTOM_THRESHOLD;
        
        if (!isRightSide) {
            return 0; // 必须在右侧
        }
        
        if (isTopArea || isBottomArea) {
            score += 25;
        } else {
            return 0; // 必须在顶部或底部
        }
        
        // 3. 文本评分（更严格）
        CharSequence textSeq = node.getText();
        if (textSeq != null) {
            String text = textSeq.toString().trim().toLowerCase();
            if (text.length() <= 12) { // 短文本
                for (String skipText : SKIP_TEXTS) {
                    if (text.equals(skipText.toLowerCase())) {
                        score += 50; // 完全匹配
                        break;
                    }
                }
                // 包含匹配（分数较低）
                if (score < 50) {
                    for (String skipText : SKIP_TEXTS) {
                        if (text.contains(skipText.toLowerCase())) {
                            score += 30;
                            break;
                        }
                    }
                }
                // 倒计时模式
                if (text.matches(".*\\d+.*[秒s].*") || text.matches(".*倒计时.*")) {
                    score += 35;
                }
            }
        }
        
        // 4. 内容描述评分
        CharSequence descSeq = node.getContentDescription();
        if (descSeq != null) {
            String desc = descSeq.toString().trim().toLowerCase();
            for (String skipText : SKIP_TEXTS) {
                if (desc.equals(skipText.toLowerCase())) {
                    score += 45;
                    break;
                }
                if (desc.contains(skipText.toLowerCase())) {
                    score += 25;
                    break;
                }
            }
        }
        
        // 5. ID评分
        String id = node.getViewIdResourceName();
        if (id != null) {
            String lowerId = id.toLowerCase();
            for (String skipId : SKIP_IDS) {
                if (lowerId.contains(skipId)) {
                    score += 30;
                    break;
                }
            }
        }
        
        // 6. 可点击评分
        if (node.isClickable()) {
            score += 10;
        }
        
        return score;
    }
    
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
        sb.append(" pos:").append(bounds.toShortString());
        
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
    
    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        long now = System.currentTimeMillis();
        if (now - lastClickTime < MIN_CLICK_INTERVAL) {
            return false;
        }
        
        if (node.isClickable()) {
            boolean success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (success) {
                lastClickTime = now;
                return true;
            }
        }
        
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                boolean success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                if (success) {
                    lastClickTime = now;
                    return true;
                }
                break;
            }
            AccessibilityNodeInfo grandParent = parent.getParent();
            parent.recycle();
            parent = grandParent;
        }
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return performGestureClick(bounds.exactCenterX(), bounds.exactCenterY());
    }
    
    private boolean performGestureClick(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        
        return dispatchGesture(builder.build(), null, null);
    }

    @Override
    public void onInterrupt() {}

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
    
    private static class ScoredNode {
        AccessibilityNodeInfo node;
        int score;
        
        ScoredNode(AccessibilityNodeInfo node, int score) {
            this.node = node;
            this.score = score;
        }
    }
}
