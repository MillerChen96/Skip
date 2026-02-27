package com.skipad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.skipad.service.KeepAliveService;
import com.skipad.service.SkipAdAccessibilityService;
import com.skipad.util.SkipAdPreferences;

import java.util.List;

/**
 * 主界面Activity
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY_PERMISSION = 1001;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 1002;
    
    private TextView tvServiceStatus;
    private TextView tvSkipCount;
    private Button btnOpenAccessibility;
    private Button btnStartService;
    private Button btnStopService;
    private Button btnBatteryOptimization;
    private Switch switchAutoStart;
    
    private SkipAdPreferences preferences;
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        preferences = new SkipAdPreferences(this);
        sharedPreferences = getSharedPreferences("skip_ad_prefs", MODE_PRIVATE);
        
        initViews();
        setupListeners();
        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateSkipCount();
    }

    private void initViews() {
        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvSkipCount = findViewById(R.id.tv_skip_count);
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility);
        btnStartService = findViewById(R.id.btn_start_service);
        btnStopService = findViewById(R.id.btn_stop_service);
        btnBatteryOptimization = findViewById(R.id.btn_battery_optimization);
        switchAutoStart = findViewById(R.id.switch_auto_start);
        
        switchAutoStart.setChecked(preferences.isAutoStart());
    }

    private void setupListeners() {
        btnOpenAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        
        btnStartService.setOnClickListener(v -> startKeepAliveService());
        
        btnStopService.setOnClickListener(v -> stopKeepAliveService());
        
        btnBatteryOptimization.setOnClickListener(v -> requestIgnoreBatteryOptimization());
        
        switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.setAutoStart(isChecked);
        });
    }

    /**
     * 检查权限
     */
    private void checkPermissions() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
        }
        
        // 检查电池优化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                btnBatteryOptimization.setVisibility(View.VISIBLE);
            } else {
                btnBatteryOptimization.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 请求悬浮窗权限
     */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
        }
    }

    /**
     * 请求忽略电池优化
     */
    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION);
        }
    }

    /**
     * 打开无障碍设置
     */
    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "请在列表中找到\"跳广告助手\"并开启", Toast.LENGTH_LONG).show();
    }

    /**
     * 启动保活服务
     */
    private void startKeepAliveService() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            openAccessibilitySettings();
            return;
        }
        
        Intent intent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
        updateServiceStatus();
    }

    /**
     * 停止保活服务
     */
    private void stopKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        stopService(intent);
        
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
        updateServiceStatus();
    }

    /**
     * 检查无障碍服务是否开启
     */
    private boolean isAccessibilityServiceEnabled() {
        AccessibilityServiceInfo info = null;
        try {
            android.view.accessibility.AccessibilityManager am = 
                (android.view.accessibility.AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> services = 
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            
            for (AccessibilityServiceInfo service : services) {
                if (service.getId().contains(getPackageName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // 备用检查方法
        return SkipAdAccessibilityService.isServiceEnabled();
    }

    /**
     * 更新服务状态
     */
    private void updateServiceStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        
        if (enabled) {
            tvServiceStatus.setText(R.string.service_running);
            tvServiceStatus.setTextColor(getColor(R.color.green));
            btnStartService.setEnabled(false);
            btnStopService.setEnabled(true);
        } else {
            tvServiceStatus.setText(R.string.service_stopped);
            tvServiceStatus.setTextColor(getColor(R.color.red));
            btnStartService.setEnabled(true);
            btnStopService.setEnabled(false);
        }
    }

    /**
     * 更新跳过次数
     */
    private void updateSkipCount() {
        int count = sharedPreferences.getInt("skip_count", 0);
        tvSkipCount.setText(getString(R.string.skip_count) + ": " + count);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已获取", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_BATTERY_OPTIMIZATION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(this, "已加入电池优化白名单", Toast.LENGTH_SHORT).show();
                    btnBatteryOptimization.setVisibility(View.GONE);
                }
            }
        }
    }
}
