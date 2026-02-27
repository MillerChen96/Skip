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
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.skipad.util.SkipAdPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 跳过广告的无障碍服务
 * 自动检测并点击跳过按钮
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    
    // 跳过按钮的常见文本关键词
    private static final String[] SKIP_KEYWORDS = {
        "跳过", "关闭", "跳过广告", "关闭广告", "关闭按钮",
        "skip", "close", "skip ad", "close ad", "dismiss",
        "跳过 >", "跳过>", "> 跳过", ">跳过",
        "×", "X", "x", "跳过  ", "  跳过",
        "立即跳过", "点击跳过", "点击关闭",
        "广告跳过", "跳过此广告", "跳过视频",
        "跳过 splash", "跳过开屏", "跳过启动页",
        "3秒后跳过", "5秒后跳过", "倒计时跳过",
        "跳过 •", "• 跳过", "跳过·", "·跳过"
    };
    
    // 跳过按钮的常见ID关键词
    private static final String[] SKIP_ID_KEYWORDS = {
        "skip", "close", "jump", "dismiss", "cancel",
        "ad_skip", "ad_close", "skip_ad", "close_ad",
        "btn_skip", "btn_close", "tv_skip", "tv_close",
        "iv_skip", "iv_close", "img_skip", "img_close",
        "skip_button", "close_button", "jump_button",
        "splash_skip", "splash_close", "launch_skip",
        "skip_text", "close_text", "jump_text"
    };
    
    // 跳过按钮的常见描述关键词
    private static final String[] SKIP_DESC_KEYWORDS = {
        "跳过", "关闭", "skip", "close", "jump",
        "跳过广告", "关闭广告", "广告跳过"
    };
    
    // 需要跳过广告的常见应用包名
    private static final String[] TARGET_PACKAGES = {
        "com.tencent.mm",           // 微信
        "com.tencent.mobileqq",     // QQ
        "com.taobao.taobao",        // 淘宝
        "com.tmall.wireless",       // 天猫
        "com.jingdong.app.mall",    // 京东
        "com.sina.weibo",           // 微博
        "com.ss.android.article.news", // 今日头条
        "com.smile.gifmaker",       // 快手
        "com.ss.android.ugc.aweme", // 抖音
        "com.netease.cloudmusic",   // 网易云音乐
        "com.tencent.qqlive",       // 腾讯视频
        "com.youku.phone",          // 优酷
        "com.douban.frodo",         // 豆瓣
        "com.zhihu.android",        // 知乎
        "com.xunmeng.pinduoduo",    // 拼多多
        "com.eg.android.AlipayGphone", // 支付宝
        "com.baidu.searchbox",      // 百度
        "com.qiyi.video",           // 爱奇艺
        "com.cmbchina.cmbproduction", // 招商银行
        "com.ccb.smartpos"          // 建设银行
    };
    
    private Handler handler;
    private SharedPreferences preferences;
    private int skipCount = 0;
    private long lastClickTime = 0;
    private static final long CLICK_INTERVAL = 500; // 点击间隔，防止重复点击
    
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
        
        // 监听窗口状态变化和窗口内容变化
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            String packageName = event.getPackageName() != null ? 
                event.getPackageName().toString() : "";
            
            // 检查是否是目标应用
            if (isTargetPackage(packageName)) {
                // 延迟一小段时间，等待广告加载完成
                handler.postDelayed(() -> findAndClickSkipButton(), 300);
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "服务被中断");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "无障碍服务已连接");
        
        // 启动保活服务
        startKeepAliveService();
    }

    /**
     * 检查是否是目标应用包名
     */
    private boolean isTargetPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        
        for (String target : TARGET_PACKAGES) {
            if (packageName.equals(target)) {
                return true;
            }
        }
        return true; // 默认对所有应用生效
    }

    /**
     * 查找并点击跳过按钮
     */
    private void findAndClickSkipButton() {
        // 防止频繁点击
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < CLICK_INTERVAL) {
            return;
        }
        
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.d(TAG, "无法获取根节点");
            return;
        }
        
        // 查找跳过按钮
        List<AccessibilityNodeInfo> skipNodes = new ArrayList<>();
        
        // 方法1：通过文本查找
        for (String keyword : SKIP_KEYWORDS) {
            List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(keyword);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (isSkipButton(node)) {
                        skipNodes.add(node);
                    }
                }
            }
        }
        
        // 方法2：通过ID查找
        if (skipNodes.isEmpty()) {
            for (String idKeyword : SKIP_ID_KEYWORDS) {
                List<AccessibilityNodeInfo> nodes = findNodesByIdKeyword(rootNode, idKeyword);
                if (nodes != null && !nodes.isEmpty()) {
                    skipNodes.addAll(nodes);
                }
            }
        }
        
        // 方法3：通过内容描述查找
        if (skipNodes.isEmpty()) {
            for (String desc : SKIP_DESC_KEYWORDS) {
                List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(desc);
                if (nodes != null && !nodes.isEmpty()) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node.getContentDescription() != null &&
                            node.getContentDescription().toString().contains(desc)) {
                            skipNodes.add(node);
                        }
                    }
                }
            }
        }
        
        // 方法4：查找特定位置的关闭按钮（右上角或右下角的小按钮）
        if (skipNodes.isEmpty()) {
            findCloseButtonByPosition(rootNode, skipNodes);
        }
        
        // 尝试点击找到的跳过按钮
        for (AccessibilityNodeInfo node : skipNodes) {
            if (performClick(node)) {
                lastClickTime = currentTime;
                skipCount++;
                saveSkipCount();
                Log.d(TAG, "成功点击跳过按钮，总次数: " + skipCount);
                break;
            }
        }
        
        rootNode.recycle();
    }

    /**
     * 判断节点是否是跳过按钮
     */
    private boolean isSkipButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        // 检查文本
        CharSequence text = node.getText();
        if (text != null) {
            String textStr = text.toString().toLowerCase().trim();
            for (String keyword : SKIP_KEYWORDS) {
                if (textStr.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        
        // 检查内容描述
        CharSequence contentDesc = node.getContentDescription();
        if (contentDesc != null) {
            String descStr = contentDesc.toString().toLowerCase().trim();
            for (String keyword : SKIP_KEYWORDS) {
                if (descStr.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * 通过ID关键词查找节点
     */
    private List<AccessibilityNodeInfo> findNodesByIdKeyword(AccessibilityNodeInfo root, String keyword) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        if (root == null) return result;
        
        String id = root.getViewIdResourceName();
        if (id != null && id.toLowerCase().contains(keyword.toLowerCase())) {
            // 检查是否是可点击的按钮
            if (root.isClickable() || root.getClassName().toString().contains("Button") ||
                root.getClassName().toString().contains("ImageView") ||
                root.getClassName().toString().contains("TextView")) {
                result.add(root);
            }
        }
        
        // 递归查找子节点
        int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                result.addAll(findNodesByIdKeyword(child, keyword));
            }
        }
        
        return result;
    }

    /**
     * 根据位置查找关闭按钮（通常在右上角或右下角）
     */
    private void findCloseButtonByPosition(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> skipNodes) {
        if (root == null) return;
        
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        int screenWidth = bounds.width();
        int screenHeight = bounds.height();
        
        findCloseButtonByPositionRecursive(root, skipNodes, screenWidth, screenHeight);
    }
    
    private void findCloseButtonByPositionRecursive(AccessibilityNodeInfo node, 
            List<AccessibilityNodeInfo> skipNodes, int screenWidth, int screenHeight) {
        if (node == null) return;
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        
        // 检查是否是小尺寸的可点击元素（可能是关闭按钮）
        int width = bounds.width();
        int height = bounds.height();
        
        // 关闭按钮通常比较小（30-100dp）
        if (width > 0 && width < 200 && height > 0 && height < 200) {
            // 检查是否在右上角或右下角
            boolean isTopRight = bounds.right > screenWidth * 0.7f && bounds.top < screenHeight * 0.3f;
            boolean isBottomRight = bounds.right > screenWidth * 0.7f && bounds.bottom > screenHeight * 0.7f;
            
            if ((isTopRight || isBottomRight) && (node.isClickable() || node.isClickable())) {
                // 检查是否包含关闭相关的文本或描述
                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                
                if ((text != null && isCloseText(text.toString())) ||
                    (desc != null && isCloseText(desc.toString())) ||
                    (text == null && desc == null && width < 80 && height < 80)) {
                    skipNodes.add(node);
                }
            }
        }
        
        // 递归检查子节点
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findCloseButtonByPositionRecursive(child, skipNodes, screenWidth, screenHeight);
            }
        }
    }
    
    private boolean isCloseText(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();
        return lower.equals("×") || lower.equals("x") || lower.equals("跳过") ||
               lower.equals("关闭") || lower.equals("skip") || lower.equals("close");
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
