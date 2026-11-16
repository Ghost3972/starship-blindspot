package com.elliot.blindspot;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 权限自动授予工具
 * 尝试通过多种方式自动获取APP所需的系统权限
 * 
 * 策略：
 * 1. 尝试通过su命令授予系统权限
 * 2. 尝试通过本地ADB执行授权命令
 * 3. 尝试通过系统API申请权限
 */
public class PermissionGranter {
    private static final String TAG = "PermissionGranter";

    // 需要的权限列表
    private static final String[] REQUIRED_PERMISSIONS = {
            "android.permission.CAMERA",
            "android.permission.READ_LOGS",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.MANAGE_ACTIVITY_STACKS",
            "android.permission.INTERNAL_SYSTEM_WINDOW",
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            // Android Car API 权限（车速/档位/刹车实时读取）
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_DYNAMICS_STATE",
            "android.car.permission.CAR_VENDOR_EXTENSION"
    };

    private static final String[] APPOPS_PERMISSIONS = {
            "SYSTEM_ALERT_WINDOW"
    };

    private final Context context;
    private final ExecutorService executor;
    private PermissionGrantCallback callback;

    public interface PermissionGrantCallback {
        void onProgress(String message);

        void onSuccess(List<String> grantedPermissions);

        void onPartialSuccess(List<String> grantedPermissions, List<String> failedPermissions);

        void onFailure(String error);
    }

    public PermissionGranter(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * 异步尝试授予所有权限
     */
    public void grantAllPermissionsAsync(PermissionGrantCallback callback) {
        this.callback = callback;
        executor.execute(this::grantAllPermissions);
    }

    /**
     * 同步授予所有权限
     */
    private void grantAllPermissions() {
        String packageName = context.getPackageName();
        List<String> grantedPermissions = new ArrayList<>();
        List<String> failedPermissions = new ArrayList<>();

        notifyProgress("开始自动授权...");

        // 步骤1: 启用网络ADB
        notifyProgress("1/4 尝试启用网络ADB...");
        boolean adbEnabled = enableNetworkAdb();
        if (adbEnabled) {
            notifyProgress("✓ 网络ADB已启用");
        } else {
            notifyProgress("✗ 网络ADB启用失败（可能需要USB连接）");
        }

        // 步骤2: 通过su授予权限
        notifyProgress("2/4 尝试通过su授予权限...");
        for (String permission : REQUIRED_PERMISSIONS) {
            if (grantPermissionViaSu(packageName, permission)) {
                grantedPermissions.add(permission);
                notifyProgress("  ✓ " + getPermissionShortName(permission));
            } else {
                failedPermissions.add(permission);
                notifyProgress("  ✗ " + getPermissionShortName(permission));
            }
        }

        // 步骤3: 授予AppOps权限
        notifyProgress("3/4 尝试授予AppOps权限...");
        for (String appop : APPOPS_PERMISSIONS) {
            if (grantAppOpViaSu(packageName, appop)) {
                grantedPermissions.add(appop);
                notifyProgress("  ✓ " + appop);
            } else {
                failedPermissions.add(appop);
                notifyProgress("  ✗ " + appop);
            }
        }

        // 步骤4: 通过本地ADB授予权限（如果su失败）
        if (!failedPermissions.isEmpty() && adbEnabled) {
            notifyProgress("4/4 尝试通过本地ADB授予剩余权限...");
            List<String> retryGranted = grantPermissionsViaLocalAdb(packageName, failedPermissions);
            grantedPermissions.addAll(retryGranted);
            failedPermissions.removeAll(retryGranted);
            for (String perm : retryGranted) {
                notifyProgress("  ✓ " + getPermissionShortName(perm));
            }
        }

        // 回调结果
        if (failedPermissions.isEmpty()) {
            notifySuccess(grantedPermissions);
        } else if (!grantedPermissions.isEmpty()) {
            notifyPartialSuccess(grantedPermissions, failedPermissions);
        } else {
            notifyFailure("所有权限授予失败，请使用USB连接后运行授权脚本");
        }
    }

    /**
     * 启用网络ADB（需要su权限）
     */
    private boolean enableNetworkAdb() {
        try {
            // 方法1: 通过setprop启用ADB TCP模式
            String[] commands = {
                    "su 0 setprop service.adb.tcp.port 5555",
                    "su 0 stop adbd",
                    "su 0 start adbd"
            };

            for (String cmd : commands) {
                executeCommand(cmd);
                Thread.sleep(500);
            }

            // 验证是否监听5555端口
            String result = executeCommand("su 0 netstat -an | grep 5555");
            if (result == null) {
                result = executeCommand("su 0 ss -an | grep 5555");
            }

            return result != null && result.contains("5555");

        } catch (Exception e) {
            Log.e(TAG, "Failed to enable network ADB", e);
            return false;
        }
    }

    /**
     * 通过su授予权限
     */
    private boolean grantPermissionViaSu(String packageName, String permission) {
        try {
            String cmd = "su 0 pm grant " + packageName + " " + permission;
            String result = executeCommand(cmd);

            // 检查是否成功（无输出或Success表示成功）
            if (result == null || result.trim().isEmpty() || result.contains("Success")) {
                return true;
            }

            // 检查错误信息
            if (result.contains("Unknown permission") || result.contains("not found")) {
                Log.w(TAG, "Permission " + permission + " not recognized by system");
                return false;
            }

            Log.e(TAG, "Failed to grant " + permission + ": " + result);
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Exception granting " + permission, e);
            return false;
        }
    }

    /**
     * 通过su授予AppOps权限
     */
    private boolean grantAppOpViaSu(String packageName, String appop) {
        try {
            String cmd = "su 0 appops set " + packageName + " " + appop + " allow";
            String result = executeCommand(cmd);

            // 验证是否成功
            String verifyCmd = "su 0 appops get " + packageName + " " + appop;
            String verifyResult = executeCommand(verifyCmd);

            return verifyResult != null && verifyResult.contains("allow");

        } catch (Exception e) {
            Log.e(TAG, "Exception granting appop " + appop, e);
            return false;
        }
    }

    /**
     * 通过本地ADB授予权限
     */
    private List<String> grantPermissionsViaLocalAdb(String packageName, List<String> permissions) {
        List<String> granted = new ArrayList<>();

        try {
            AdbShellExecutor adbExecutor = new AdbShellExecutor();

            for (String permission : permissions) {
                String cmd = "pm grant " + packageName + " " + permission;
                try {
                    String result = adbExecutor.execute(cmd);
                    if (result == null || result.trim().isEmpty() || result.contains("Success")) {
                        granted.add(permission);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "ADB grant failed for " + permission, e);
                }
            }

            adbExecutor.shutdown();

        } catch (Exception e) {
            Log.e(TAG, "Failed to use local ADB", e);
        }

        return granted;
    }

    /**
     * 执行shell命令
     */
    private String executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[] { "sh", "-c", command });

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errReader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            reader.close();
            errReader.close();

            return exitCode == 0 ? output.toString() : null;

        } catch (Exception e) {
            Log.e(TAG, "Failed to execute: " + command, e);
            return null;
        }
    }

    /**
     * 获取权限简短名称
     */
    private String getPermissionShortName(String permission) {
        int lastDot = permission.lastIndexOf('.');
        return lastDot >= 0 ? permission.substring(lastDot + 1) : permission;
    }

    /**
     * 通知进度
     */
    private void notifyProgress(String message) {
        Log.d(TAG, message);
        if (callback != null) {
            callback.onProgress(message);
        }
    }

    /**
     * 通知成功
     */
    private void notifySuccess(List<String> grantedPermissions) {
        Log.d(TAG, "All permissions granted: " + grantedPermissions.size());
        if (callback != null) {
            callback.onSuccess(grantedPermissions);
        }
    }

    /**
     * 通知部分成功
     */
    private void notifyPartialSuccess(List<String> grantedPermissions, List<String> failedPermissions) {
        Log.w(TAG, "Partial success - granted: " + grantedPermissions.size() + ", failed: " + failedPermissions.size());
        if (callback != null) {
            callback.onPartialSuccess(grantedPermissions, failedPermissions);
        }
    }

    /**
     * 通知失败
     */
    private void notifyFailure(String error) {
        Log.e(TAG, "Permission grant failed: " + error);
        if (callback != null) {
            callback.onFailure(error);
        }
    }

    /**
     * 关闭执行器
     */
    public void shutdown() {
        executor.shutdown();
    }
}
