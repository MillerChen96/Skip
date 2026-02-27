package com.skipad.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 偏好设置工具类
 */
public class SkipAdPreferences {
    
    private static final String PREF_NAME = "skip_ad_prefs";
    
    private static final String KEY_SKIP_COUNT = "skip_count";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    
    private SharedPreferences preferences;
    
    public SkipAdPreferences(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    public int getSkipCount() {
        return preferences.getInt(KEY_SKIP_COUNT, 0);
    }
    
    public void setSkipCount(int count) {
        preferences.edit().putInt(KEY_SKIP_COUNT, count).apply();
    }
    
    public boolean isAutoStart() {
        return preferences.getBoolean(KEY_AUTO_START, true);
    }
    
    public void setAutoStart(boolean autoStart) {
        preferences.edit().putBoolean(KEY_AUTO_START, autoStart).apply();
    }
    
    public boolean isServiceEnabled() {
        return preferences.getBoolean(KEY_SERVICE_ENABLED, false);
    }
    
    public void setServiceEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply();
    }
}
