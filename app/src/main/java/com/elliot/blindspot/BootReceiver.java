package com.elliot.blindspot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 开机启动广播接收器
 * 监听系统开机广播，自动启动转向补盲前台服务
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final String PREFS_NAME = "turning_blind_spot_prefs";
    private static final String PREF_AUTO_START_ON_BOOT = "auto_start_on_boot";
    
    private static final long BOOT_DELAY_MS = 1500;
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        AppLog.d(TAG, "Received broadcast: " + action);
        
        if (action == null) {
            return;
        }
        
        // 检查用户是否启用自启动
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // 未同意免责声明前不允许自启动
        if (!prefs.getBoolean("disclaimer_accepted", false)) {
            AppLog.d(TAG, "Disclaimer not accepted, skipping auto start");
            android.util.Log.e("TURNING_DEBUG", "BootReceiver: disclaimer not accepted, skipping");
            return;
        }
        
        boolean autoStart = prefs.getBoolean(PREF_AUTO_START_ON_BOOT, true);
        if (!autoStart) {
            AppLog.d(TAG, "Auto start disabled by user, skipping");
            android.util.Log.e("TURNING_DEBUG", "BootReceiver: auto start disabled, skipping");
            return;
        }
        
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                android.util.Log.e("TURNING_DEBUG", "BootReceiver: starting service for action=" + action);
                scheduleStartService(context);
                break;
            default:
                break;
        }
    }
    
    /**
     * 延迟启动服务
     * 悬浮窗锚点已保住进程，延迟可以缩短
     */
    private void scheduleStartService(Context context) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> startService(context), BOOT_DELAY_MS);
    }
    
    private void startService(Context context) {
        try {
            if (TurningBlindSpotService.isRunning()) {
                AppLog.d(TAG, "Service already running");
                android.util.Log.e("TURNING_DEBUG", "BootReceiver: service already running");
            } else {
                // 通过 BlindspotApp 的重试机制启动服务（更可靠）
                BlindspotApp app = BlindspotApp.getInstance();
                if (app != null) {
                    android.util.Log.e("TURNING_DEBUG", "BootReceiver: delegating to BlindspotApp.ensureServiceRunning()");
                    app.ensureServiceRunning();
                } else {
                    // 回退：直接启动（不太可能到这里，因为 Application 先于 Receiver 创建）
                    android.util.Log.w("TURNING_DEBUG", "BootReceiver: BlindspotApp not available, direct start");
                    Intent serviceIntent = new Intent(context, TurningBlindSpotService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                }
                AppLog.d(TAG, "Service start requested");
                android.util.Log.e("TURNING_DEBUG", "BootReceiver: service start requested");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to start service", e);
            android.util.Log.e("TURNING_DEBUG", "BootReceiver: failed - " + e.getMessage());
            
            // 即使失败，利用 BlindspotApp 的重试机制
            BlindspotApp app = BlindspotApp.getInstance();
            if (app != null) {
                new Handler(Looper.getMainLooper()).postDelayed(app::ensureServiceRunning, 3000);
            }
        }
    }
}
