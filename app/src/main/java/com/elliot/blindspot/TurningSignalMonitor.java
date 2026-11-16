package com.elliot.blindspot;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 转向信号监听器
 * 通过读取系统日志检测车辆转向灯信号
 *
 * 仅通过 logcat 全量抓取后的关键词匹配触发：
 * - 匹配到左/右关键词 → 进入对应方向补盲
 * - 在超时时间内未再匹配到关键词 → 自动退出补盲
 */
public class TurningSignalMonitor {
    private static final String TAG = "TurningSignalMonitor";

    // 广播动作
    public static final String ACTION_TURNING_SIGNAL_CHANGED = "com.elliot.blindspot.TURNING_SIGNAL_CHANGED";
    public static final String EXTRA_TURNING_DIRECTION = "turning_direction";
    public static final String EXTRA_IS_TURNING_ON = "is_turning_on";

    // 转向方向常量
    public static final String DIRECTION_NONE = "none";
    public static final String DIRECTION_LEFT = "left";
    public static final String DIRECTION_RIGHT = "right";

    // 超时自动关闭（在用户设置时间内未再匹配到关键词则关闭悬浮窗）
    private static final long AUTO_CLOSE_TIMEOUT_MS_DEFAULT = 120 * 1000;

    private Context context;
    private Thread monitorThread;
    private volatile boolean isRunning = false;
    private Handler mainHandler;

    private volatile String currentDirection = DIRECTION_NONE;
    private Runnable autoCloseRunnable;
    private volatile long autoCloseTimeoutMs = AUTO_CLOSE_TIMEOUT_MS_DEFAULT;
    private long turningStartTime = 0;
    
    private int pendingCount = 0;
    
    // 调试统计
    private int logLinesRead = 0;
    private int matchedLinesCount = 0;
    
    // logcat 进程引用（用于停止时强制销毁）
    private volatile Process logcatProcess;
    private volatile long lastLogLineTime = 0;

    private volatile String turnKeywordLeftLower = "";
    private volatile String turnKeywordRightLower = "";
    private volatile long lastKeywordRefreshTime = 0;

    private TurningSignalCallback callback;
    private static TurningSignalMonitor instance;

    public interface TurningSignalCallback {
        void onTurningStart(String direction);

        void onTurningEnd();
    }

    private TurningSignalMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized TurningSignalMonitor getInstance(Context context) {
        if (instance == null) {
            instance = new TurningSignalMonitor(context);
        }
        return instance;
    }

    public void setCallback(TurningSignalCallback callback) {
        this.callback = callback;
    }

    public void startMonitoring() {
        if (isRunning && monitorThread != null && monitorThread.isAlive()) {
            return;
        }

        isRunning = true;
        logLinesRead = 0;
        matchedLinesCount = 0;
        refreshTurnKeywordsFromPrefs();
        
        // 始终启动 logcat 监听（兼容所有车型的转向灯检测 + 车辆数据解析）
        monitorThread = new Thread(this::monitorLogcat, "TurningSignalMonitor");
        monitorThread.start();
        AppLog.d(TAG, "Monitor started (logcat=active)");
    }

    public void stopMonitoring() {
        isRunning = false;
        cancelAutoClose();
        pendingCount = 0;
        // 强制销毁 logcat 子进程（避免孤儿进程残留）
        if (logcatProcess != null) {
            try {
                logcatProcess.destroyForcibly();
                android.util.Log.i(TAG, "logcat process forcibly destroyed");
            } catch (Exception ignored) {}
            logcatProcess = null;
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        currentDirection = DIRECTION_NONE;
        AppLog.d(TAG, "Monitor stopped. Lines read: " + logLinesRead + ", matched: " + matchedLinesCount);
    }

    public String getCurrentDirection() {
        return currentDirection;
    }

    public boolean isTurning() {
        return !DIRECTION_NONE.equals(currentDirection);
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void monitorLogcat() {
        android.util.Log.i(TAG, "=== TurningSignalMonitor Started ===");
        android.util.Log.i(TAG, "Thread: " + Thread.currentThread().getName());
        long backoffMs = 1000;

        while (isRunning) {
            Process process = null;
            BufferedReader reader = null;

            try {
                safeClearLogcatBuffer();

                process = tryStartLogcat();
                if (process == null) {
                    AppLog.e(TAG, "Failed to start logcat process (will retry)");
                    sleepBackoff(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 30000);
                    continue;
                }

                backoffMs = 1000;
                logcatProcess = process;
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                final Process finalProcess = process;
                new Thread(() -> readErrorStream(finalProcess), "LogcatErrorReader").start();

                AppLog.i(TAG, "Logcat started, reading logs...");

                long lastStatusTime = System.currentTimeMillis();
                String line;
                while (isRunning && (line = reader.readLine()) != null) {
                    lastLogLineTime = System.currentTimeMillis();
                    logLinesRead++;

                    if (isRelevantLogLine(line)) {
                        matchedLinesCount++;
                        AppLog.i(TAG, "[MATCH] " + line);
                        processLogLine(line);
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastStatusTime > 30000) {
                        AppLog.i(TAG, "Status: read=" + logLinesRead + ", matched=" + matchedLinesCount +
                                ", direction=" + currentDirection + ", lastLine=" + formatTime(lastLogLineTime));
                        lastStatusTime = now;
                    }
                }

                if (isRunning) {
                    AppLog.w(TAG, "Logcat stream ended (EOF). Will restart.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error in monitorLogcat", e);
                if (isRunning) {
                    AppLog.e(TAG, "Error monitoring logcat (will restart)", e);
                }
            } finally {
                closeQuietly(reader);
                if (process != null) {
                    try {
                        process.destroyForcibly();
                    } catch (Exception ignored) {}
                }
                if (logcatProcess == process) {
                    logcatProcess = null;
                }
            }

            if (isRunning) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        AppLog.i(TAG, "Monitor thread exited. Total lines: " + logLinesRead + ", matched: " + matchedLinesCount);
    }

    public boolean isMonitorThreadAlive() {
        return monitorThread != null && monitorThread.isAlive();
    }

    public long getLastLogLineTime() {
        return lastLogLineTime;
    }

    private void safeClearLogcatBuffer() {
        Process p = null;
        try {
            Process proc = Runtime.getRuntime().exec("logcat -c");
            p = proc;
            final Process p0 = proc;
            Thread waiter = new Thread(() -> {
                try {
                    p0.waitFor();
                } catch (Exception ignored) {
                }
            }, "LogcatClearWaiter");
            waiter.start();
            waiter.join(1500);
            if (waiter.isAlive()) {
                try {
                    p.destroy();
                } catch (Exception ignored) {}
                waiter.interrupt();
                AppLog.w(TAG, "logcat -c timeout, continue without clearing");
            }
        } catch (Exception e) {
            AppLog.w(TAG, "logcat -c failed: " + e.getMessage());
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {}
            }
        }
    }

    private void sleepBackoff(long backoffMs) throws InterruptedException {
        if (backoffMs <= 0) return;
        Thread.sleep(backoffMs);
    }

    private String formatTime(long timeMs) {
        if (timeMs <= 0) return "N/A";
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(timeMs));
    }
    
    /**
     * 尝试多种方式启动 logcat
     */
    private Process tryStartLogcat() {
        String[] commands = {
            // 读取所有日志：不使用 -s 过滤，关键词匹配在应用层完成
            "logcat -v brief",
            // root + 所有日志
            "su -c logcat -v brief"
        };
        
        for (String cmd : commands) {
            try {
                android.util.Log.i(TAG, "Trying: " + cmd);
                Process p = Runtime.getRuntime().exec(cmd);
                
                // 等待一小段时间检查进程是否存活
                Thread.sleep(200);
                
                try {
                    int exitCode = p.exitValue();
                    android.util.Log.w(TAG, "Command exited immediately with code: " + exitCode);
                    continue;
                } catch (IllegalThreadStateException e) {
                    // 进程还在运行，说明成功了
                    android.util.Log.i(TAG, "SUCCESS: " + cmd);
                    return p;
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "Failed: " + cmd + " - " + e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 检查日志行是否与转向灯相关（精确过滤）
     * 只匹配明确与转向灯控制相关的日志，避免误触发
     */
    private boolean isRelevantLogLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastKeywordRefreshTime > 1000) {
            refreshTurnKeywordsFromPrefs();
            lastKeywordRefreshTime = now;
        }

        String lowerLine = line.toLowerCase();

        if (!turnKeywordLeftLower.isEmpty() && lowerLine.contains(turnKeywordLeftLower)) {
            return true;
        }
        if (!turnKeywordRightLower.isEmpty() && lowerLine.contains(turnKeywordRightLower)) {
            return true;
        }
        return false;
    }
    
    /**
     * 读取错误流
     */
    private void readErrorStream(Process process) {
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String errLine;
            while ((errLine = errorReader.readLine()) != null) {
                android.util.Log.w(TAG, "[STDERR] " + errLine);
            }
        } catch (Exception ignored) {
        }
    }
    
    /**
     * 安全关闭 Reader
     */
    private void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void processLogLine(String line) {
        if (line == null || line.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastKeywordRefreshTime > 1000) {
            refreshTurnKeywordsFromPrefs();
            lastKeywordRefreshTime = now;
        }

        String lowerLine = line.toLowerCase();
        if (!turnKeywordLeftLower.isEmpty() && lowerLine.contains(turnKeywordLeftLower)) {
            handleTurningStart(DIRECTION_LEFT);
            return;
        }
        if (!turnKeywordRightLower.isEmpty() && lowerLine.contains(turnKeywordRightLower)) {
            handleTurningStart(DIRECTION_RIGHT);
        }
    }

    private void refreshTurnKeywordsFromPrefs() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("turning_blind_spot_prefs",
                    android.content.Context.MODE_PRIVATE);
            String left = prefs.getString(MainActivity.PREF_TURN_KEYWORD_LEFT, "");
            String right = prefs.getString(MainActivity.PREF_TURN_KEYWORD_RIGHT, "");
            turnKeywordLeftLower = left == null ? "" : left.trim().toLowerCase();
            turnKeywordRightLower = right == null ? "" : right.trim().toLowerCase();
            int sec = prefs.getInt(MainActivity.PREF_TURN_AUTO_CLOSE_TIMEOUT_SEC, 3);
            if (sec < 3) sec = 3;
            if (sec > 30) sec = 30;
            autoCloseTimeoutMs = sec * 1000L;
        } catch (Exception ignored) {
        }
    }

    private void handleTurningStart(String direction) {
        if (direction.equals(currentDirection)) {
            // 相同方向：刷新时间 + 重置自动关闭定时器（防止长转向时误关）
            pendingCount++;
            scheduleAutoClose(); // 每次收到信号都刷新 120 秒倒计时
            return;
        }
        pendingCount = 1;
        confirmTurningStart(direction);
    }
    
    /**
     * 确认转向开始（通过防抖验证后）
     */
    private void confirmTurningStart(String direction) {
        // 记录是否是方向切换（从左转到右转，或从右转到左转）
        boolean isSwitchingDirection = !DIRECTION_NONE.equals(currentDirection);
        String previousDirection = currentDirection;

        currentDirection = direction;
        turningStartTime = System.currentTimeMillis();
        
        android.util.Log.i(TAG, "=== TURNING START: " + direction + 
                (isSwitchingDirection ? " (switch from " + previousDirection + ")" : " (new)") + " ===");
        AppLog.d(TAG, "Turning started: " + direction + (isSwitchingDirection ? " (switching)" : ""));

        sendTurningBroadcast(direction, true);
        
        // 设置超时自动关闭（防止信号丢失）
        scheduleAutoClose();

        // 触发回调
        if (callback != null) {
            mainHandler.post(() -> {
                android.util.Log.i(TAG, "Calling callback.onTurningStart(" + direction + ")");
                callback.onTurningStart(direction);
            });
        }
    }

    private void handleTurningEnd() {
        if (DIRECTION_NONE.equals(currentDirection)) {
            return;
        }
        pendingCount = 0;
        final String directionToClose = currentDirection;
        currentDirection = DIRECTION_NONE;
        cancelAutoClose();
        
        long totalDuration = System.currentTimeMillis() - turningStartTime;
        android.util.Log.i(TAG, "=== TURNING END (immediate): " + directionToClose + 
                " (duration: " + totalDuration + "ms) ===");
        AppLog.d(TAG, "Turning ended immediately: " + directionToClose);

        sendTurningBroadcast(DIRECTION_NONE, false);

        if (callback != null) {
            android.util.Log.i(TAG, "Calling callback.onTurningEnd() immediately");
            mainHandler.post(() -> callback.onTurningEnd());
        }
    }
    
    /**
     * 设置超时自动关闭
     * 防止转向灯信号丢失导致悬浮窗一直显示
     */
    private void scheduleAutoClose() {
        cancelAutoClose();
        
        autoCloseRunnable = () -> {
            if (!isTurning()) {
                return;
            }
            
            long elapsed = System.currentTimeMillis() - turningStartTime;
            android.util.Log.w(TAG, "AUTO-CLOSE triggered after " + (elapsed / 1000) + "s");
            
            currentDirection = DIRECTION_NONE;
            sendTurningBroadcast(DIRECTION_NONE, false);
            
            if (callback != null) {
                mainHandler.post(() -> callback.onTurningEnd());
            }
        };
        
        mainHandler.postDelayed(autoCloseRunnable, autoCloseTimeoutMs);
    }
    
    /**
     * 取消超时自动关闭
     */
    private void cancelAutoClose() {
        if (autoCloseRunnable != null) {
            mainHandler.removeCallbacks(autoCloseRunnable);
            autoCloseRunnable = null;
        }
    }

    private void sendTurningBroadcast(String direction, boolean isTurningOn) {
        Intent intent = new Intent(ACTION_TURNING_SIGNAL_CHANGED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_TURNING_DIRECTION, direction);
        intent.putExtra(EXTRA_IS_TURNING_ON, isTurningOn);
        context.sendBroadcast(intent);
    }
}
