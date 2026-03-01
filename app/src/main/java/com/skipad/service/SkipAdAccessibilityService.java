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
import java.util.concurrent.TimeUnit;

/**
 * 跳过广告的无障碍服务
 * 
 * 完全参考 zfdang/Android-Touch-Helper (5.3k stars) 的实现
 * 
 * 核心策略：
 * 1. 只对已安装的第三方应用启动检测
 * 2. 使用线程池异步执行检测
 * 3. 广度优先遍历节点
 * 4. 关键词匹配：文本长度 <= 关键词长度 + 6
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    private static final String SELF_PACKAGE_NAME = "跳广告助手";
    
    // 跳过按钮关键词（参考 Android-Touch-Helper，默认只有"跳过"）
    private static final String[] KEYWORDS = {
        "跳过", "关闭", "跳过广告", "关闭广告",
        "skip", "close", "dismiss", "×", "X"
    };
    
    // 跳过按钮ID关键词
    private static final String[] SKIP_IDS = {
        "skip", "close", "jump", "dismiss", "cancel",
        "splash", "ad_skip", "ad_close"
    };
    
    // 检测配置（参考 Android-Touch-Helper）
    private static final int SKIP_AD_DURATION = 6;  // 跳过广告检测持续时间（秒）
    private static final int PACKAGE_POSITION_CLICK_FIRST_DELAY = 300;  // 首次点击延迟
    private static final int PACKAGE_POSITION_CLICK_RETRY_INTERVAL = 500; // 点击重试间隔
    private static final int PACKAGE_POSITION_CLICK_RETRY = 6;  // 最大重试次数
    
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
    
    // 需要检测的应用包名集合
    private Set<String> setPackages;
    
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
        
        // 初始化应用包名集合
        updatePackages();
        
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
        
        try {
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    handleWindowStateChanged(tempPkgName.toString(), tempClassName.toString());
                    break;
                    
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                    handleWindowContentChanged(tempPkgName.toString(), event.getSource());
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "处理事件异常: " + e.getMessage());
        }
    }
    
    /**
     * 处理窗口状态变化事件
     * 参考 Android-Touch-Helper 的实现
     */
    private void handleWindowStateChanged(String pkgName, String className) {
        // 排除输入法应用
        if (setIMEApps.contains(pkgName)) {
            return;
        }
        
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
                
                // 只有在需要检测的应用列表中才启动跳过广告流程
                if (setPackages.contains(pkgName)) {
                    startSkipAdProcess();
                }
            }
        } else {
            // 同一应用内Activity切换
            if (isActivity && !className.equals(currentActivityName)) {
                Log.d(TAG, "Activity切换: " + currentActivityName + " -> " + className);
                currentActivityName = className;
                // 注意：Android-Touch-Helper 注释掉了这里停止检测的逻辑
                // 因为有些广告Activity不是第一个Activity
            }
        }
        
        // 执行跳过广告检测
        if (skipAdByKeyword) {
            final AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                taskExecutorService.execute(() -> iterateNodesToSkipAd(root, null));
            }
        }
    }
    
    /**
     * 处理窗口内容变化事件
     */
    private void handleWindowContentChanged(String pkgName, AccessibilityNodeInfo source) {
        if (!setPackages.contains(pkgName)) {
            return;
        }
        
        if (skipAdByKeyword && source != null) {
            taskExecutorService.execute(() -> iterateNodesToSkipAd(source, null));
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
        handler.postDelayed(this::stopSkipAdProcess, SKIP_AD_DURATION * 1000L);
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
     * 完全参考 Android-Touch-Helper 的 iterateNodesToSkipAd 方法
     * 
     * @param root 根节点
     * @param set 传入set时按控件判断，否则按关键词判断
     */
    private void iterateNodesToSkipAd(AccessibilityNodeInfo root, Set<String> set) {
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
                    childNodes.add(node.getChild(n));
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
     * 完全参考 Android-Touch-Helper 的 skipAdByKeywords 方法
     */
    private boolean skipAdByKeywords(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        CharSequence description = node.getContentDescription();
        CharSequence text = node.getText();
        
        if (TextUtils.isEmpty(description) && TextUtils.isEmpty(text)) {
            return false;
        }
        
        // 尝试匹配关键词
        boolean isFound = false;
        for (String keyword : KEYWORDS) {
            // 文本或描述包含关键词，但长度不能太长（<= 关键词长度 + 6）
            if (text != null && 
                (text.toString().length() <= keyword.length() + 6) && 
                text.toString().contains(keyword) && 
                !text.toString().equals(SELF_PACKAGE_NAME)) {
                isFound = true;
            } else if (description != null && 
                       (description.toString().length() <= keyword.length() + 6) && 
                       description.toString().contains(keyword) && 
                       !description.toString().equals(SELF_PACKAGE_NAME)) {
                isFound = true;
            }
            
            if (isFound) {
                Log.d(TAG, "匹配关键词: " + keyword);
                break;
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
                        Log.d(TAG, "匹配ID: " + skipId);
                        break;
                    }
                }
            }
        }
        
        if (!isFound) return false;
        
        // 生成节点描述
        String nodeDesc = describeNode(node);
        Log.d(TAG, "找到跳过按钮: " + nodeDesc);
        
        // 检查是否已点击过
        if (clickedWidgets.contains(nodeDesc)) {
            return false;
        }
        
        clickedWidgets.add(nodeDesc);
        
        // 执行点击
        boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.d(TAG, "直接点击结果: " + clicked);
        
        if (!clicked) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            clicked = click(rect.centerX(), rect.centerY(), 0, 20);
            Log.d(TAG, "手势点击结果: " + clicked);
        }
        
        if (clicked) {
            skipCount++;
            saveSkipCount();
            Log.d(TAG, "点击成功，总次数: " + skipCount);
        }
        
        return true;
    }
    
    /**
     * 模拟点击
     */
    private boolean click(int X, int Y, long start_time, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        
        Path path = new Path();
        path.moveTo(X, Y);
        
        GestureDescription.Builder builder = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, start_time, duration));
        
        return dispatchGesture(builder.build(), null, null);
    }
    
    /**
     * 生成节点描述
     */
    private String describeNode(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Node");
        
        if (node.getClassName() != null) {
            sb.append(" class=").append(node.getClassName());
        }
        
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        sb.append(" Position=[").append(rect.left).append(",").append(rect.right)
          .append(",").append(rect.top).append(",").append(rect.bottom).append("]");
        
        if (node.getViewIdResourceName() != null) {
            sb.append(" ResourceId=").append(node.getViewIdResourceName());
        }
        if (node.getContentDescription() != null) {
            sb.append(" Description=").append(node.getContentDescription());
        }
        if (node.getText() != null) {
            sb.append(" Text=").append(node.getText());
        }
        
        return sb.toString();
    }
    
    /**
     * 更新应用包名集合
     * 参考 Android-Touch-Helper 的 updatePackage 方法
     */
    private void updatePackages() {
        setPackages = new HashSet<>();
        setIMEApps = new HashSet<>();
        Set<String> setTemps = new HashSet<>();
        
        // 获取所有启动器应用
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> resolveInfoList = getPackageManager().queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL);
        for (android.content.pm.ResolveInfo e : resolveInfoList) {
            setPackages.add(e.activityInfo.packageName);
        }
        
        // 获取所有桌面应用
        intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        resolveInfoList = getPackageManager().queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL);
        for (android.content.pm.ResolveInfo e : resolveInfoList) {
            setTemps.add(e.activityInfo.packageName);
        }
        
        // 获取所有输入法应用
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        List<android.view.inputmethod.InputMethodInfo> inputMethodInfoList = imm.getInputMethodList();
        for (android.view.inputmethod.InputMethodInfo e : inputMethodInfoList) {
            setIMEApps.add(e.getPackageName());
        }
        
        // 排除自身和系统应用
        setTemps.add(getPackageName());
        setTemps.add("com.android.settings");
        
        // 从检测列表中移除桌面、输入法和系统应用
        setPackages.removeAll(setTemps);
        setPackages.removeAll(setIMEApps);
        
        Log.d(TAG, "检测应用数量: " + setPackages.size());
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
