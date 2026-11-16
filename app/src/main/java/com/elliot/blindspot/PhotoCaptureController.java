package com.elliot.blindspot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class PhotoCaptureController {
    static final String PREF_PHOTO_CONTINUOUS_ENABLED = "photo_continuous_enabled";
    static final String PREF_PHOTO_AUTO_START_ON_SERVICE = "photo_auto_start_on_service";
    static final String PREF_PHOTO_INTERVAL_MS = "photo_interval_ms";
    static final String PREF_PHOTO_FORMAT = "photo_format";
    static final String PREF_PHOTO_QUALITY = "photo_quality";
    static final String PREF_PHOTO_MAX_WIDTH = "photo_max_width";
    static final String PREF_PHOTO_FOLDER_LIMIT_BYTES = "photo_folder_limit_bytes";
    static final String PREF_PHOTO_OVERLAY_ENABLED = "photo_overlay_enabled";
    static final String PREF_PHOTO_OVERLAY_SIZE_DP = "photo_overlay_size_dp";
    static final String PREF_PHOTO_OVERLAY_ALPHA_PERCENT = "photo_overlay_alpha_percent";
    static final String PREF_PHOTO_OVERLAY_X = "photo_overlay_x";
    static final String PREF_PHOTO_OVERLAY_Y = "photo_overlay_y";
    static final String PREF_PHOTO_CAPTURE_SOURCE = "photo_capture_source";
    static final String PREF_PHOTO_ALBUM_BUCKET_MS = "photo_album_bucket_ms";

    static final String CAPTURE_SOURCE_GL = "gl";
    static final String CAPTURE_SOURCE_TEXTURE = "texture";

    static final String ACTION_PHOTO_STATE_CHANGED = "com.elliot.blindspot.PHOTO_STATE_CHANGED";
    static final String EXTRA_PHOTO_ENABLED = "photo_enabled";
    static final String EXTRA_PHOTO_LAST_PATH = "photo_last_path";
    static final String EXTRA_PHOTO_LAST_TS = "photo_last_ts";

    interface FrameRequester {
        void requestFrame(int maxWidth, FrameCallback cb);
    }

    interface StateListener {
        void onContinuousChanged(boolean enabled);
    }

    interface FrameCallback {
        void onFrame(android.graphics.Bitmap bitmap);

        void onError(String reason);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final FrameRequester frameRequester;
    private final PhotoStorage storage;

    private StateListener stateListener;

    private HandlerThread thread;
    private Handler handler;
    private final Runnable autoStartRunnable;

    private volatile boolean continuousEnabled = false;
    private volatile boolean captureInFlight = false;
    private volatile long lastCaptureUptimeMs = 0;

    PhotoCaptureController(Context context, SharedPreferences prefs, FrameRequester frameRequester) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.frameRequester = frameRequester;
        this.storage = new PhotoStorage(context, prefs);
        this.autoStartRunnable = () -> {
            boolean auto = this.prefs.getBoolean(PREF_PHOTO_AUTO_START_ON_SERVICE, false);
            if (!auto) return;
            setContinuousEnabled(true);
        };
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("PhotoCaptureThread");
        thread.start();
        handler = new Handler(thread.getLooper());
        boolean auto = prefs.getBoolean(PREF_PHOTO_AUTO_START_ON_SERVICE, false);
        boolean was = prefs.getBoolean(PREF_PHOTO_CONTINUOUS_ENABLED, false);
        if (auto) {
            handler.removeCallbacks(autoStartRunnable);
            handler.postDelayed(autoStartRunnable, 2000);
        } else if (was) {
            setContinuousEnabled(true);
        }
    }

    void stop() {
        Handler h = handler;
        if (h != null) {
            h.removeCallbacks(autoStartRunnable);
            h.removeCallbacks(captureLoop);
        }
        setContinuousEnabled(false);
        HandlerThread t = thread;
        thread = null;
        handler = null;
        if (t != null) t.quitSafely();
    }

    boolean isContinuousEnabled() {
        return continuousEnabled;
    }

    void setContinuousEnabled(boolean enabled) {
        continuousEnabled = enabled;
        prefs.edit().putBoolean(PREF_PHOTO_CONTINUOUS_ENABLED, enabled).apply();
        Handler h = handler;
        if (h != null) {
            h.removeCallbacks(captureLoop);
            if (enabled) h.post(captureLoop);
        }
        broadcastState(enabled, null, 0);
        if (stateListener != null) stateListener.onContinuousChanged(enabled);
    }

    void setStateListener(StateListener l) {
        this.stateListener = l;
    }

    void captureOnce() {
        Handler h = handler;
        if (h == null) return;
        h.post(() -> doCapture(false));
    }

    private final Runnable captureLoop = new Runnable() {
        @Override
        public void run() {
            doCapture(true);
        }
    };

    private void doCapture(boolean scheduleNext) {
        Handler h = handler;
        if (h == null) return;

        if (scheduleNext && !continuousEnabled) return;

        long interval = Math.max(100, prefs.getLong(PREF_PHOTO_INTERVAL_MS, 1000));
        long now = SystemClock.uptimeMillis();
        long due = lastCaptureUptimeMs + interval;
        if (now < due) {
            if (scheduleNext) h.postDelayed(captureLoop, due - now);
            return;
        }
        if (captureInFlight) {
            if (scheduleNext) h.postDelayed(captureLoop, interval);
            return;
        }
        captureInFlight = true;
        lastCaptureUptimeMs = now;

        int maxWidth = Math.max(320, prefs.getInt(PREF_PHOTO_MAX_WIDTH, 2560));

        frameRequester.requestFrame(maxWidth, new FrameCallback() {
            @Override
            public void onFrame(android.graphics.Bitmap bitmap) {
                Handler hh = handler;
                if (hh == null) return;
                hh.post(() -> {
                    try {
                        saveBitmap(bitmap);
                    } catch (Exception e) {
                        AppLog.w("PhotoCapture", "save error: " + e.getMessage());
                    } finally {
                        captureInFlight = false;
                        if (scheduleNext && continuousEnabled) {
                            long iv = Math.max(100, prefs.getLong(PREF_PHOTO_INTERVAL_MS, 1000));
                            hh.postDelayed(captureLoop, iv);
                        }
                    }
                });
            }

            @Override
            public void onError(String reason) {
                captureInFlight = false;
                AppLog.w("PhotoCapture", "capture error: " + reason);
                if (scheduleNext && continuousEnabled && h != null) {
                    h.postDelayed(captureLoop, interval);
                }
            }
        });
    }

    private void saveBitmap(android.graphics.Bitmap bitmap) throws Exception {
        String fmt = prefs.getString(PREF_PHOTO_FORMAT, "jpeg");
        int quality = clamp(prefs.getInt(PREF_PHOTO_QUALITY, 85), 0, 100);
        long folderLimit = prefs.getLong(PREF_PHOTO_FOLDER_LIMIT_BYTES, 0);

        String baseName = buildBaseName();
        PhotoStorage.SaveResult r = storage.saveBitmap(bitmap, baseName, fmt, quality);
        if (folderLimit > 0) storage.enforceFolderLimitBytes(folderLimit);
        broadcastState(continuousEnabled, r.displayPath, r.timestampMs);
    }

    private String buildBaseName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
        String ts = sdf.format(new Date());
        return ts;
    }

    private void broadcastState(boolean enabled, String lastPath, long lastTs) {
        Intent i = new Intent(ACTION_PHOTO_STATE_CHANGED);
        i.putExtra(EXTRA_PHOTO_ENABLED, enabled);
        if (lastPath != null) i.putExtra(EXTRA_PHOTO_LAST_PATH, lastPath);
        if (lastTs > 0) i.putExtra(EXTRA_PHOTO_LAST_TS, lastTs);
        try {
            context.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
