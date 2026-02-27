package com.skipad.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.skipad.service.KeepAliveService;

/**
 * 守护进程广播接收器
 * 用于监听系统事件并保持服务运行
 */
public class DaemonReceiver extends BroadcastReceiver {

    private static final String TAG = "DaemonReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        
        String action = intent.getAction();
        Log.d(TAG, "收到广播: " + action);
        
        // 监听屏幕亮起、屏幕关闭、用户解锁等事件
        switch (action) {
            case Intent.ACTION_SCREEN_ON:
                Log.d(TAG, "屏幕亮起");
                startKeepAliveService(context);
                break;
                
            case Intent.ACTION_SCREEN_OFF:
                Log.d(TAG, "屏幕关闭");
                // 屏幕关闭时也保持服务运行
                startKeepAliveService(context);
                break;
                
            case Intent.ACTION_USER_PRESENT:
                Log.d(TAG, "用户解锁");
                startKeepAliveService(context);
                break;
                
            case "com.skipad.DAEMON_RECEIVER":
                Log.d(TAG, "守护广播，重启服务");
                startKeepAliveService(context);
                break;
        }
    }
    
    private void startKeepAliveService(Context context) {
        Intent serviceIntent = new Intent(context, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
