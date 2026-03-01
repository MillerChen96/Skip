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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 跳过广告的无障碍服务
 * 
 * 参考 zfdang/Android-Touch-Helper (5.3k stars) 的实现
 * 
 * 核心策略：
 * 1. 对所有第三方应用启动检测（不限制包名列表）
 * 2. 使用线程池异步执行检测
 * 3. 广度优先遍历节点
 * 4. 关键词匹配：文本长度 <= 关键词长度 + 6
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    private static final String SELF_PACKAGE_NAME = "跳广告助手";
    
    // 跳过按钮关键词
    private static final String[] KEYWORDS = {
        "跳过", "关闭", "跳过广告", "关闭广告", "立即跳过",
        "skip", "close", "dismiss", "×", "X"
    };
    
    // 跳过按钮ID关键词
    private static final String[] SKIP_IDS = {
        "skip", "close", "jump", "dismiss", "cancel",
        "splash", "ad_skip", "ad_close"
    };
    
    // 检测配置
    private static final long SKIP_AD_DURATION = 8000;  // 跳过广告检测持续时间（毫秒）
    
    private Handler handler;
    private SharedPreferences preferences;
    private int skipCount = 0;
    
    // 线程池
    private ScheduledExecutorService taskExecutorService;
    
    // 当前应用信息
    private String currentPackageName = "";
    private String currentActivityName = "";
    
    // 跳过广告状态
    private volatile boolean skipAdRunning = false;
    private volatile boolean skipAdByKeyword = false;
    
    // 已点击的控件
    private Set<String> clickedWidgets;
    
    // 输入法应用包名（需要排除）
    private Set<String> setIMEApps;
    
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
        taskExecutorService = Executors.newSingleThreadScheduledExecutor();
        
        // 初始化输入法应用包名
        updateIMEApps();
        
        Log.d(TAG, "服务创建");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        stopSkipAdProcess();
        if (taskExecutorService != null) {
            taskExecutorService.shutdown();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        CharSequence tempPkgName = event.getPackageName();
        CharSequence tempClassName = event.getClassName();
        if (tempPkgName == null || tempClassName == null) return;
        
        String pkgName = tempPkgName.toString();
        String className = tempClassName.toString();
        
        // 排除系统应用和自身
        if (isSystemPackage(pkgName)) return;
        
        // 排除输入法应用
        if (setIMEApps != null && setIMEApps.contains(pkgName)) return;
        
        try {
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    handleWindowStateChanged(pkgName, className);
                    break;
                    
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                    handleWindowContentChanged(pkgName, event.getSource());
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "处理事件异常: " + e.getMessage());
        }
    }
    
    /**
     * 处理窗口状态变化事件
     */
    private void handleWindowStateChanged(String pkgName, String className) {
        // 判断是否是Activity
        boolean isActivity = !className.startsWith("android.") && !className.startsWith("androidx.");
        
        if (!pkgName.equals(currentPackageName)) {
            // 新应用
            if (isActivity) {
                Log.d(TAG, "应用切换: " + currentPackageName + " -> " + pkgName);
                
                // 停止当前跳过广告流程
                stopSkipAdProcess();
                
                currentPackageName = pkgName;
                currentActivityName = className;
                
                // 对所有第三方应用启动跳过广告流程
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
        performSkipAdDetection();
    }
    
    /**
     * 处理窗口内容变化事件
     */
    private void handleWindowContentChanged(String pkgName, AccessibilityNodeInfo source) {
        if (!pkgName.equals(currentPackageName)) return;
        
        if (skipAdByKeyword && source != null) {
            taskExecutorService.execute(() -> iterateNodesToSkipAd(source));
        }
    }
    
    /**
     * 执行跳过广告检测
     */
    private void performSkipAdDetection() {
        if (!skipAdByKeyword) return;
        
        final AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            taskExecutorService.execute(() -> iterateNodesToSkipAd(root));
        }
    }
    
    /**
     * 开始跳过广告流程
     */
    private void startSkipAdProcess() {
        Log.d(TAG, "开始跳过广告流程: " + currentPackageName);
        skipAdRunning = true;
        skipAdByKeyword = true;
        clickedWidgets.clear();
        
        // 设置超时停止
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::stopSkipAdProcess, SKIP_AD_DURATION);
        
        // 立即执行一次检测
        handler.postDelayed(this::performSkipAdDetection, 300);
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
     * 遍历节点跳过广告
     */
    private void iterateNodesToSkipAd(AccessibilityNodeInfo root) {
        if (root == null || !skipAdRunning) return;
        
        ArrayList<AccessibilityNodeInfo> topNodes = new ArrayList<>();
        topNodes.add(root);
        ArrayList<AccessibilityNodeInfo> childNodes = new ArrayList<>();
        
        int total = topNodes.size();
        int index = 0;
        AccessibilityNodeInfo node;
        boolean handled;
        
        while (index < total && skipAdRunning) {
            node = topNodes.get(index++);
            if (node != null) {
                // 按关键词判断
                handled = skipAdByKeywords(node);
                
                if (handled) {
                    node.recycle();
                    break;
                }
                
                // 添加子节点
                for (int n = 0; n < node.getChildCount(); n++) {
                    AccessibilityNodeInfo child = node.getChild(n);
                    if (child != null) {
                        childNodes.add(child);
                    }
                }
                node.recycle();
            }
            
            if (index == total) {
                // 当前层处理完毕，处理下一层
                topNodes.clear();
                topNodes.addAll(childNodes);
                childNodes.clear();
                index = 0;
                total = topNodes.size();
            }
        }
        
        // 回收未处理的节点
        while (index < total) {
            node = topNodes.get(index++);
            if (node != null) node.recycle();
        }
        index = 0;
        total = childNodes.size();
        while (index < total) {
            node = childNodes.get(index++);
            if (node != null) node.recycle();
        }
    }
    
    /**
     * 查找并点击包含keyword控件
     */
    private boolean skipAdByKeywords(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        CharSequence description = node.getContentDescription();
        CharSequence text = node.getText();
        
        // 尝试匹配关键词
        boolean isFound = false;
        String matchedKeyword = "";
        
        // 文本匹配
        if (!TextUtils.isEmpty(text)) {
            String textStr = text.toString();
            for (String keyword : KEYWORDS) {
                // 文本包含关键词，且长度不能太长
                int maxLen = Math.max(keyword.length() + 6, 10); // 至少允许10个字符
                if (textStr.length() <= maxLen && textStr.contains(keyword)) {
                    isFound = true;
                    matchedKeyword = keyword;
                    break;
                }
            }
        }
        
        // 描述匹配
        if (!isFound && !TextUtils.isEmpty(description)) {
            String descStr = description.toString();
            for (String keyword : KEYWORDS) {
                int maxLen = Math.max(keyword.length() + 6, 10);
                if (descStr.length() <= maxLen && descStr.contains(keyword)) {
                    isFound = true;
                    matchedKeyword = keyword;
                    break;
                }
            }
        }
        
        // ID匹配
        if (!isFound) {
            String id = node.getViewIdResourceName();
            if (id != null) {
                String lowerId = id.toLowerCase();
                for (String skipId : SKIP_IDS) {
                    if (lowerId.contains(skipId)) {
                        isFound = true;
                        matchedKeyword = "id:" + skipId;
                        break;
                    }
                }
            }
        }
        
        if (!isFound) return false;
        
        // 生成节点描述
        String nodeDesc = describeNode(node);
        Log.d(TAG, "找到跳过按钮: keyword=" + matchedKeyword + ", " + nodeDesc);
        
        // 检查是否已点击过
        if (clickedWidgets.contains(nodeDesc)) {
            Log.d(TAG, "已点击过，跳过");
            return false;
        }
        
        clickedWidgets.add(nodeDesc);
        
        // 执行点击
        boolean clicked = performClick(node);
        Log.d(TAG, "点击结果: " + clicked);
        
        if (clicked) {
            skipCount++;
            saveSkipCount();
            Log.d(TAG, "点击成功，总次数: " + skipCount);
        }
        
        return clicked;
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
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return click(rect.centerX(), rect.centerY());
    }
    
    /**
     * 手势点击
     */
    private boolean click(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.Builder builder = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 40));
        
        return dispatchGesture(builder.build(), null, null);
    }
    
    /**
     * 生成节点描述
     */
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
        
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        sb.append(" bounds=").append(rect.toShortString());
        
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * 更新输入法应用包名
     */
    private void updateIMEApps() {
        setIMEApps = new HashSet<>();
        try {
            android.view.inputmethod.InputMethodManager imm = 
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            List<android.view.inputmethod.InputMethodInfo> inputMethodInfoList = imm.getInputMethodList();
            for (android.view.inputmethod.InputMethodInfo e : inputMethodInfoList) {
                setIMEApps.add(e.getPackageName());
            }
        } catch (Exception e) {
            Log.e(TAG, "获取输入法列表失败: " + e.getMessage());
        }
    }
    
    private boolean isSystemPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        return packageName.startsWith("com.android.") ||
               packageName.startsWith("android.") ||
               packageName.contains("launcher") ||
               packageName.contains("systemui") ||
               packageName.equals(getPackageName());
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
