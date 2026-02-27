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
import java.util.Comparator;
import java.util.List;

/**
 * 跳过广告的无障碍服务
 * 
 * 核心策略：基于特征识别而非关键词穷举
 * 
 * 跳过按钮的通用特征：
 * 1. 位置：右上角或右下角
 * 2. 尺寸：小按钮（30-150dp）
 * 3. 文本：短文本（≤15字符），包含"跳过"、"关闭"、"skip"、"close"等
 * 4. 可点击
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    
    // 跳过按钮文本特征（用于评分）
    private static final String[] SKIP_TEXTS = {
        "跳过", "关闭", "skip", "close", "dismiss", "jump",
        "×", "X", "x", "跳过广告", "关闭广告"
    };
    
    // 跳过按钮ID特征
    private static final String[] SKIP_IDS = {
        "skip", "close", "jump", "dismiss", "cancel"
    };
    
    // 检测配置
    private static final long DETECT_WINDOW = 10000;  // 检测窗口：10秒
    private static final long DETECT_INTERVAL = 300;   // 检测间隔：300ms
    private static final int MAX_DETECT_COUNT = 15;    // 最大检测次数
    private static final long MIN_CLICK_INTERVAL = 1000; // 最小点击间隔
    
    // 按钮尺寸限制（dp转换为像素，假设屏幕密度为3）
    private static final int MIN_BUTTON_SIZE = 30;    // 最小30dp
    private static final int MAX_BUTTON_SIZE = 200;   // 最大200dp
    
    // 位置阈值
    private static final float RIGHT_THRESHOLD = 0.5f;    // 右半部分
    private static final float TOP_THRESHOLD = 0.35f;     // 顶部35%
    private static final float BOTTOM_THRESHOLD = 0.65f;  // 底部35%
    
    private Handler handler;
    private SharedPreferences preferences;
    private int skipCount = 0;
    
    private String currentPackage = "";
    private long appLaunchTime = 0;
    private int detectCount = 0;
    private boolean hasClicked = false;
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
        
        // 只监听窗口切换
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String packageName = event.getPackageName() != null ? 
                event.getPackageName().toString() : "";
            
            // 忽略系统和自身
            if (isSystemPackage(packageName)) return;
            
            // 应用切换
            if (!packageName.equals(currentPackage)) {
                Log.d(TAG, "应用切换: " + currentPackage + " -> " + packageName);
                currentPackage = packageName;
                appLaunchTime = System.currentTimeMillis();
                detectCount = 0;
                hasClicked = false;
                
                // 开始检测
                startDetection();
            }
        }
    }
    
    private void startDetection() {
        if (hasClicked || detectCount >= MAX_DETECT_COUNT) return;
        
        long elapsed = System.currentTimeMillis() - appLaunchTime;
        if (elapsed > DETECT_WINDOW) return;
        
        handler.postDelayed(this::detectSkipButton, DETECT_INTERVAL);
    }
    
    private void detectSkipButton() {
        if (hasClicked || detectCount >= MAX_DETECT_COUNT) return;
        
        long elapsed = System.currentTimeMillis() - appLaunchTime;
        if (elapsed > DETECT_WINDOW) return;
        
        detectCount++;
        
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            startDetection();
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
            }
        } else {
            startDetection();
        }
        
        root.recycle();
    }
    
    /**
     * 基于特征评分找到最佳跳过按钮
     */
    private AccessibilityNodeInfo findBestSkipButton(AccessibilityNodeInfo root) {
        List<ScoredNode> candidates = new ArrayList<>();
        
        // 收集所有可能的候选节点
        collectCandidates(root, candidates);
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        // 按评分排序
        Collections.sort(candidates, (a, b) -> Integer.compare(b.score, a.score));
        
        Log.d(TAG, "候选数量: " + candidates.size() + ", 最高分: " + candidates.get(0).score);
        
        // 返回评分最高的
        return candidates.get(0).node;
    }
    
    private void collectCandidates(AccessibilityNodeInfo node, List<ScoredNode> candidates) {
        if (node == null) return;
        
        int score = calculateScore(node);
        
        // 评分超过阈值的才加入候选
        if (score >= 30) {
            candidates.add(new ScoredNode(node, score));
        }
        
        // 递归检查子节点
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectCandidates(child, candidates);
            }
        }
    }
    
    /**
     * 计算节点作为跳过按钮的评分
     * 评分因素：
     * - 文本匹配 (+30)
     * - ID匹配 (+20)
     * - 位置在右上角/右下角 (+25)
     * - 尺寸合适 (+15)
     * - 可点击 (+10)
     */
    private int calculateScore(AccessibilityNodeInfo node) {
        int score = 0;
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int width = bounds.width();
        int height = bounds.height();
        
        // 1. 尺寸检查（必须是小按钮）
        if (width < MIN_BUTTON_SIZE || width > MAX_BUTTON_SIZE ||
            height < MIN_BUTTON_SIZE || height > MAX_BUTTON_SIZE) {
            return 0; // 尺寸不符合直接排除
        }
        score += 15;
        
        // 2. 位置评分
        boolean isRightSide = bounds.right > screenWidth * RIGHT_THRESHOLD;
        boolean isTopArea = bounds.top < screenHeight * TOP_THRESHOLD;
        boolean isBottomArea = bounds.bottom > screenHeight * BOTTOM_THRESHOLD;
        
        if (isRightSide && (isTopArea || isBottomArea)) {
            score += 25;
        } else if (isRightSide) {
            score += 10;
        }
        
        // 3. 文本评分
        CharSequence textSeq = node.getText();
        if (textSeq != null) {
            String text = textSeq.toString().trim().toLowerCase();
            if (text.length() <= 15) { // 短文本
                for (String skipText : SKIP_TEXTS) {
                    if (text.contains(skipText.toLowerCase())) {
                        score += 30;
                        break;
                    }
                }
                // 倒计时模式
                if (text.matches(".*\\d+.*[秒s].*") || text.matches(".*倒计时.*")) {
                    score += 25;
                }
            }
        }
        
        // 4. 内容描述评分
        CharSequence descSeq = node.getContentDescription();
        if (descSeq != null) {
            String desc = descSeq.toString().trim().toLowerCase();
            for (String skipText : SKIP_TEXTS) {
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
                    score += 20;
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
        sb.append(" size:").append(bounds.width()).append("x").append(bounds.height());
        
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
        
        // 尝试直接点击
        if (node.isClickable()) {
            boolean success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (success) {
                lastClickTime = now;
                return true;
            }
        }
        
        // 尝试点击父节点
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
        
        // 手势点击
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
    
    /**
     * 带评分的节点
     */
    private static class ScoredNode {
        AccessibilityNodeInfo node;
        int score;
        
        ScoredNode(AccessibilityNodeInfo node, int score) {
            this.node = node;
            this.score = score;
        }
    }
}
