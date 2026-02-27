package com.skipad.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.skipad.MainActivity;
import com.skipad.R;
import com.skipad.receiver.DaemonReceiver;

/**
 * 后台保活服务
 * 使用前台服务和多种保活机制确保服务不被杀死
 */
public class KeepAliveService extends Service {

    private static final String TAG = "KeepAliveService";
    private static final String CHANNEL_ID = "skip_ad_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    private PowerManager.WakeLock wakeLock;
    private DaemonReceiver daemonReceiver;
    private Handler handler;
    private Runnable heartbeatRunnable;
    
    // 心跳间隔（5分钟）
    private static final long HEARTBEAT_INTERVAL = 5 * 60 * 1000;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "保活服务创建");
        
        handler = new Handler(Looper.getMainLooper());
        
        // 创建通知渠道
        createNotificationChannel();
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 获取WakeLock
        acquireWakeLock();
        
        // 注册守护广播接收器
        registerDaemonReceiver();
        
        // 启动心跳
        startHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "保活服务启动");
        
        // 如果服务被杀死，自动重启
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "保活服务销毁");
        
        // 释放WakeLock
        releaseWakeLock();
        
        // 注销广播接收器
        unregisterDaemonReceiver();
        
        // 停止心跳
        stopHeartbeat();
        
        // 发送广播尝试重启服务
        sendBroadcast(new Intent("com.skipad.DAEMON_RECEIVER"));
    }

    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "跳广告助手服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持跳广告服务运行");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 创建前台服务通知
     */
    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("跳广告助手")
            .setContentText("服务运行中，自动跳过广告")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false);
        
        return builder.build();
    }

    /**
     * 获取WakeLock防止CPU休眠
     */
    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SkipAd::KeepAlive"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60 * 1000L); // 10分钟
        }
    }

    /**
     * 释放WakeLock
     */
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    /**
     * 注册守护广播接收器
     */
    private void registerDaemonReceiver() {
        try {
            daemonReceiver = new DaemonReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.skipad.DAEMON_RECEIVER");
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(daemonReceiver, filter);
        } catch (Exception e) {
            Log.e(TAG, "注册守护广播接收器失败: " + e.getMessage());
        }
    }

    /**
     * 注销守护广播接收器
     */
    private void unregisterDaemonReceiver() {
        if (daemonReceiver != null) {
            try {
                unregisterReceiver(daemonReceiver);
            } catch (Exception e) {
                Log.e(TAG, "注销守护广播接收器失败: " + e.getMessage());
            }
            daemonReceiver = null;
        }
    }

    /**
     * 启动心跳
     */
    private void startHeartbeat() {
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "心跳检测...");
                
                // 检查无障碍服务是否运行
                if (!SkipAdAccessibilityService.isServiceEnabled()) {
                    Log.d(TAG, "无障碍服务未运行，尝试重启");
                    // 尝试重启无障碍服务（需要用户手动开启）
                }
                
                // 重新获取WakeLock
                if (wakeLock != null && !wakeLock.isHeld()) {
                    wakeLock.acquire(10 * 60 * 1000L);
                }
                
                // 继续下一次心跳
                handler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        };
        
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
    }

    /**
     * 停止心跳
     */
    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            handler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }
}
