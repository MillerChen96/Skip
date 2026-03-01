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
 * 策略：
 * 1. 只在应用启动后的广告检测窗口内工作
 * 2. 使用特征评分识别跳过按钮
 * 3. 平衡准确性和召回率
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    
    // 跳过按钮文本特征
    private static final String[] SKIP_TEXTS = {
        "跳过", "关闭", "skip", "close", "dismiss", "jump",
        "跳过广告", "关闭广告", "×", "X"
    };
    
    // 跳过按钮ID特征
    private static final String[] SKIP_IDS = {
        "skip", "close", "jump", "dismiss", "cancel",
        "splash", "ad_", "launch"
    };
    
    // 检测配置
    private static final long AD_DETECT_WINDOW = 8000;   // 广告检测窗口：8秒
    private static final long DETECT_INTERVAL = 300;     // 检测间隔：300ms
    private static final int MAX_DETECT_COUNT = 15;      // 最大检测次数
    private static final long MIN_CLICK_INTERVAL = 1000; // 最小点击间隔
    
    // 按钮尺寸限制（像素，假设屏幕密度3，实际会根据屏幕调整）
    private static final int MIN_BUTTON_SIZE_DP = 20;    // 最小20dp
    private static final int MAX_BUTTON_SIZE_DP = 180;   // 最大180dp
    
    // 位置阈值（更宽松）
    private static final float RIGHT_THRESHOLD = 0.4f;   // 右侧60%区域
    private static final float TOP_THRESHOLD = 0.35f;    // 顶部35%
    private static final float BOTTOM_THRESHOLD = 0.65f; // 底部35%
    
    // 评分阈值
    private static final int MIN_SCORE_THRESHOLD = 45;   // 最低评分阈值
    
    private Handler handler;
    private SharedPreferences preferences;
    private int skipCount = 0;
    
    private String currentPackage = "";
    private long appLaunchTime = 0;
    private int detectCount = 0;
    private boolean hasClicked = false;
    private boolean isDetecting = false;
    private long lastClickTime = 0;
    
    private int screenWidth = 1080;
    private int screenHeight = 1920;
    private float density = 3.0f; // 屏幕密度
    
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
        
        // 获取屏幕密度
        density = getResources().getDisplayMetrics().density;
        
        Log.d(TAG, "服务创建, 屏幕密度: " + density);
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
        
        // 只监听窗口状态变化
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String packageName = event.getPackageName() != null ? 
                event.getPackageName().toString() : "";
            
            // 忽略系统和自身
            if (isSystemPackage(packageName)) return;
            
            // 应用切换
            if (!packageName.equals(currentPackage)) {
                Log.d(TAG, "应用切换: " + currentPackage + " -> " + packageName);
                
                // 重置状态
                stopDetection();
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
        if (isDetecting || hasClicked) return;
        
        long elapsed = System.currentTimeMillis() - appLaunchTime;
        if (elapsed > AD_DETECT_WINDOW) {
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
            Log.d(TAG, "超过检测窗口");
            stopDetection();
            return;
        }
        
        if (detectCount >= MAX_DETECT_COUNT) {
            Log.d(TAG, "达到最大检测次数");
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
        
        // 查找跳过按钮
        AccessibilityNodeInfo skipButton = findBestSkipButton(root);
        
        if (skipButton != null) {
            Log.d(TAG, "找到跳过按钮: " + describeNode(skipButton));
            
            if (performClick(skipButton)) {
                hasClicked = true;
                skipCount++;
                saveSkipCount();
                Log.d(TAG, "点击成功，总次数: " + skipCount);
                stopDetection();
                return;
            }
        }
        
        root.recycle();
        scheduleNextDetection();
    }
    
    private void scheduleNextDetection() {
        if (!isDetecting || hasClicked) return;
        
        handler.postDelayed(this::detectSkipButton, DETECT_INTERVAL);
    }
    
    /**
     * 查找最佳跳过按钮
     */
    private AccessibilityNodeInfo findBestSkipButton(AccessibilityNodeInfo root) {
        List<ScoredNode> candidates = new ArrayList<>();
        
        collectCandidates(root, candidates);
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        // 按评分排序
        Collections.sort(candidates, (a, b) -> Integer.compare(b.score, a.score));
        
        ScoredNode best = candidates.get(0);
        Log.d(TAG, "候选: " + candidates.size() + "个, 最高分: " + best.score + ", " + describeNode(best.node));
        
        // 评分达到阈值才返回
        if (best.score >= MIN_SCORE_THRESHOLD) {
            return best.node;
        }
        
        return null;
    }
    
    private void collectCandidates(AccessibilityNodeInfo node, List<ScoredNode> candidates) {
        if (node == null) return;
        
        int score = calculateScore(node);
        
        // 收集评分超过30的候选
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
     * 计算节点评分
     * 
     * 评分规则：
     * - 尺寸合适: +10
     * - 位置在右上角/右下角: +20
     * - 文本匹配: +30~50
     * - ID匹配: +25
     * - 内容描述匹配: +25
     * - 可点击: +5
     * - 倒计时模式: +20
     */
    private int calculateScore(AccessibilityNodeInfo node) {
        int score = 0;
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int width = bounds.width();
        int height = bounds.height();
        
        // 转换为dp
        int widthDp = (int) (width / density);
        int heightDp = (int) (height / density);
        
        // 1. 尺寸检查
        if (widthDp < MIN_BUTTON_SIZE_DP || widthDp > MAX_BUTTON_SIZE_DP ||
            heightDp < MIN_BUTTON_SIZE_DP || heightDp > MAX_BUTTON_SIZE_DP) {
            return 0; // 尺寸不符合，直接排除
        }
        score += 10;
        
        // 2. 位置评分
        boolean isRightSide = bounds.right > screenWidth * RIGHT_THRESHOLD;
        boolean isTopArea = bounds.top < screenHeight * TOP_THRESHOLD;
        boolean isBottomArea = bounds.bottom > screenHeight * BOTTOM_THRESHOLD;
        
        if (isRightSide && (isTopArea || isBottomArea)) {
            score += 20;
        } else if (isRightSide) {
            score += 10;
        } else if (isTopArea || isBottomArea) {
            score += 5;
        }
        
        // 3. 文本评分
        CharSequence textSeq = node.getText();
        if (textSeq != null) {
            String text = textSeq.toString().trim();
            String lowerText = text.toLowerCase();
            
            // 完全匹配
            for (String skipText : SKIP_TEXTS) {
                if (text.equals(skipText) || lowerText.equals(skipText.toLowerCase())) {
                    score += 50;
                    break;
                }
            }
            
            // 包含匹配
            if (score < 50) {
                for (String skipText : SKIP_TEXTS) {
                    if (lowerText.contains(skipText.toLowerCase())) {
                        score += 30;
                        break;
                    }
                }
            }
            
            // 倒计时模式
            if (text.matches(".*\\d+.*[秒s].*") || text.contains("倒计时")) {
                score += 20;
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
                    score += 25;
                    break;
                }
            }
        }
        
        // 6. 可点击评分
        if (node.isClickable()) {
            score += 5;
        }
        
        return score;
    }
    
    private String describeNode(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        
        if (node.getText() != null) {
            sb.append("text='").append(node.getText()).append("'");
        }
        if (node.getContentDescription() != null) {
            sb.append(" desc='").append(node.getContentDescription()).append("'");
        }
        if (node.getViewIdResourceName() != null) {
            sb.append(" id='").append(node.getViewIdResourceName()).append("'");
        }
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int widthDp = (int) (bounds.width() / density);
        int heightDp = (int) (bounds.height() / density);
        sb.append(" size=").append(widthDp).append("x").append(heightDp).append("dp");
        
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
                if (success) {
                    lastClickTime = now;
                    parent.recycle();
                    return true;
                }
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
        
        boolean result = dispatchGesture(builder.build(), null, null);
        if (result) {
            lastClickTime = System.currentTimeMillis();
        }
        return result;
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
        final AccessibilityNodeInfo node;
        final int score;
        
        ScoredNode(AccessibilityNodeInfo node, int score) {
            this.node = node;
            this.score = score;
        }
    }
}
