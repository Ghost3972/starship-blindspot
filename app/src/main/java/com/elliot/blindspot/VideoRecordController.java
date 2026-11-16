package com.elliot.blindspot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class VideoRecordController {
    static final String PREF_RECORD_ENABLED = "record_enabled";
    static final String PREF_RECORD_AUTO_START_ON_SERVICE = "record_auto_start_on_service";
    static final String PREF_RECORD_FPS = "record_fps";
    static final String PREF_RECORD_SEGMENT_SECONDS = "record_segment_seconds";
    static final String PREF_RECORD_JPEG_QUALITY = "record_jpeg_quality";
    static final String PREF_RECORD_MAX_WIDTH = "record_max_width";
    static final String PREF_RECORD_FOLDER_LIMIT_BYTES = "record_folder_limit_bytes";
    static final String PREF_RECORD_OVERLAY_ENABLED = "record_overlay_enabled";
    static final String PREF_RECORD_OVERLAY_SIZE_DP = "record_overlay_size_dp";
    static final String PREF_RECORD_OVERLAY_ALPHA_PERCENT = "record_overlay_alpha_percent";
    static final String PREF_RECORD_OVERLAY_X = "record_overlay_x";
    static final String PREF_RECORD_OVERLAY_Y = "record_overlay_y";
    static final String PREF_RECORD_CAPTURE_SOURCE = "record_capture_source";
    static final String PREF_RECORD_ALBUM_BUCKET_MS = "record_album_bucket_ms";

    static final String PREF_PHOTO_CONTINUOUS_ENABLED = "photo_continuous_enabled";
    static final String PREF_PHOTO_AUTO_START_ON_SERVICE = "photo_auto_start_on_service";
    static final String PREF_PHOTO_INTERVAL_MS = "photo_interval_ms";
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

    static final String ACTION_RECORD_STATE_CHANGED = "com.elliot.blindspot.RECORD_STATE_CHANGED";
    static final String EXTRA_RECORD_ENABLED = "record_enabled";
    static final String EXTRA_RECORD_LAST_PATH = "record_last_path";
    static final String EXTRA_RECORD_LAST_TS = "record_last_ts";
    static final String EXTRA_RECORD_DROPPED = "record_dropped";

    interface FrameRequester {
        void requestFrame(int maxWidth, FrameCallback cb);
    }

    interface StateListener {
        void onRecordChanged(boolean enabled);
    }

    interface FrameCallback {
        void onFrame(Bitmap bitmap);

        void onError(String reason);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final FrameRequester frameRequester;
    private final VideoStorage storage;

    private StateListener stateListener;

    private HandlerThread recordThread;
    private Handler recordHandler;
    private final Runnable autoStartRunnable;

    private volatile boolean recordEnabled = false;
    private volatile boolean captureInFlight = false;
    private volatile long lastCaptureUptimeMs = 0;

    private HandlerThread ioThread;
    private Handler ioHandler;
    private volatile boolean ioRunning;
    private final ArrayBlockingQueue<FramePacket> frameQueue = new ArrayBlockingQueue<>(120);
    private final ArrayDeque<byte[]> bufferPool = new ArrayDeque<>(16);
    private volatile long droppedFrames = 0;
    private final StealableByteArrayOutputStream jpegOut = new StealableByteArrayOutputStream(256 * 1024);

    private volatile String lastSegmentPath = null;
    private volatile long lastSegmentTs = 0;

    VideoRecordController(Context context, SharedPreferences prefs, FrameRequester frameRequester) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.frameRequester = frameRequester;
        this.storage = new VideoStorage(context, prefs);
        this.autoStartRunnable = () -> {
            boolean auto = readBoolean(PREF_RECORD_AUTO_START_ON_SERVICE, PREF_PHOTO_AUTO_START_ON_SERVICE, false);
            if (!auto) return;
            setRecordEnabled(true);
        };
    }

    void start() {
        if (recordThread != null) return;
        recordThread = new HandlerThread("VideoRecordThread");
        recordThread.start();
        recordHandler = new Handler(recordThread.getLooper());
        boolean auto = readBoolean(PREF_RECORD_AUTO_START_ON_SERVICE, PREF_PHOTO_AUTO_START_ON_SERVICE, false);
        boolean was = readBoolean(PREF_RECORD_ENABLED, PREF_PHOTO_CONTINUOUS_ENABLED, false);
        if (auto) {
            recordHandler.removeCallbacks(autoStartRunnable);
            recordHandler.postDelayed(autoStartRunnable, 2000);
        } else if (was) {
            setRecordEnabled(true);
        }
    }

    void stop() {
        Handler h = recordHandler;
        if (h != null) {
            h.removeCallbacks(autoStartRunnable);
            h.removeCallbacks(captureLoop);
        }
        setRecordEnabled(false);
        HandlerThread t = recordThread;
        recordThread = null;
        recordHandler = null;
        if (t != null) t.quitSafely();
    }

    boolean isRecordEnabled() {
        return recordEnabled;
    }

    long getDroppedFrames() {
        return droppedFrames;
    }

    void setRecordEnabled(boolean enabled) {
        recordEnabled = enabled;
        prefs.edit().putBoolean(PREF_RECORD_ENABLED, enabled).apply();
        Handler h = recordHandler;
        if (h != null) {
            h.removeCallbacks(captureLoop);
            if (enabled) h.post(captureLoop);
        }
        if (enabled) startIo();
        else stopIo();
        broadcastState(enabled, lastSegmentPath, lastSegmentTs, droppedFrames);
        if (stateListener != null) stateListener.onRecordChanged(enabled);
    }

    void setStateListener(StateListener l) {
        this.stateListener = l;
    }

    void recordOnceSeconds(int seconds) {
        int s = clamp(seconds, 1, 120);
        Handler h = recordHandler;
        if (h == null) return;
        h.post(() -> {
            setRecordEnabled(true);
            h.postDelayed(() -> setRecordEnabled(false), s * 1000L);
        });
    }

    private final Runnable captureLoop = new Runnable() {
        @Override
        public void run() {
            doCapture(true);
        }
    };

    private void doCapture(boolean scheduleNext) {
        Handler h = recordHandler;
        if (h == null) return;
        if (scheduleNext && !recordEnabled) return;

        int fps = readInt(PREF_RECORD_FPS, -1);
        if (fps <= 0) {
            long iv = Math.max(100, prefs.getLong(PREF_PHOTO_INTERVAL_MS, 1000));
            fps = clamp((int) Math.round(1000.0 / (double) iv), 1, 25);
        }
        fps = clamp(fps, 1, 25);
        long intervalMs = Math.max(40, Math.round(1000.0 / (double) fps));

        long now = SystemClock.uptimeMillis();
        long due = lastCaptureUptimeMs + intervalMs;
        if (now < due) {
            if (scheduleNext) h.postDelayed(captureLoop, due - now);
            return;
        }
        if (captureInFlight) {
            if (scheduleNext) h.postDelayed(captureLoop, intervalMs);
            return;
        }
        captureInFlight = true;
        lastCaptureUptimeMs = now;

        int maxWidth = Math.max(320, readInt(PREF_RECORD_MAX_WIDTH, readInt(PREF_PHOTO_MAX_WIDTH, 2560)));
        int quality = clamp(readInt(PREF_RECORD_JPEG_QUALITY, readInt(PREF_PHOTO_QUALITY, 85)), 0, 100);

        frameRequester.requestFrame(maxWidth, new FrameCallback() {
            @Override
            public void onFrame(Bitmap bitmap) {
                Handler hh = recordHandler;
                if (hh == null) return;
                hh.post(() -> {
                    try {
                        FramePacket p = encodeJpeg(bitmap, quality);
                        if (p != null) {
                            boolean ok = frameQueue.offer(p);
                            if (!ok) {
                                droppedFrames++;
                                recyclePacket(p);
                            }
                        }
                    } catch (Exception e) {
                        AppLog.w("VideoRecord", "encode error: " + e.getMessage());
                    } finally {
                        captureInFlight = false;
                        if (scheduleNext && recordEnabled) hh.postDelayed(captureLoop, intervalMs);
                    }
                });
            }

            @Override
            public void onError(String reason) {
                captureInFlight = false;
                AppLog.w("VideoRecord", "capture error: " + reason);
                if (scheduleNext && recordEnabled && h != null) h.postDelayed(captureLoop, intervalMs);
            }
        });
    }

    private FramePacket encodeJpeg(Bitmap bitmap, int quality) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        jpegOut.reset();
        boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut);
        if (!ok) {
            return null;
        }
        int len = jpegOut.size();
        byte[] replacement = takeBuffer(256 * 1024);
        byte[] used = jpegOut.stealTo(replacement);
        if (used == null) return null;
        return new FramePacket(used, len, System.currentTimeMillis(), bitmap.getWidth(), bitmap.getHeight());
    }

    private void startIo() {
        if (ioThread != null) return;
        ioRunning = true;
        ioThread = new HandlerThread("VideoRecordIoThread");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        ioHandler.post(ioLoop);
    }

    private void stopIo() {
        ioRunning = false;
        HandlerThread t = ioThread;
        ioThread = null;
        ioHandler = null;
        if (t != null) t.quitSafely();
    }

    private final Runnable ioLoop = new Runnable() {
        @Override
        public void run() {
            MovMjpegWriter writer = null;
            VideoStorage.SegmentFile current = null;
            int writerWidth = -1;
            int writerHeight = -1;
            int fps = clamp(readInt(PREF_RECORD_FPS, 10), 1, 25);
            int segmentSeconds = clamp(readInt(PREF_RECORD_SEGMENT_SECONDS, 10), 1, 120);
            long segmentStartTsMs = 0;
            long folderLimit = prefs.getLong(PREF_RECORD_FOLDER_LIMIT_BYTES,
                    prefs.getLong(PREF_PHOTO_FOLDER_LIMIT_BYTES, 0));
            long lastFlushUptime = SystemClock.uptimeMillis();
            while (ioRunning || !frameQueue.isEmpty()) {
                try {
                    FramePacket p = ioRunning ? frameQueue.poll(200, TimeUnit.MILLISECONDS) : frameQueue.poll();
                    if (p == null) {
                        long now = SystemClock.uptimeMillis();
                        if (writer != null && now - lastFlushUptime >= 1000) {
                            writer.flush();
                            lastFlushUptime = now;
                        }
                        continue;
                    }
                    boolean needRotate = current == null
                            || (segmentStartTsMs > 0 && p.tsMs - segmentStartTsMs >= segmentSeconds * 1000L);
                    if (!needRotate && writer != null && (p.width != writerWidth || p.height != writerHeight)) {
                        needRotate = true;
                    }
                    if (needRotate) {
                        if (writer != null) {
                            try {
                                writer.close();
                            } catch (Exception ignored) {
                            }
                            lastSegmentPath = current != null ? current.displayPath : lastSegmentPath;
                            lastSegmentTs = current != null ? current.timestampMs : lastSegmentTs;
                            broadcastState(recordEnabled, lastSegmentPath, lastSegmentTs, droppedFrames);
                            if (folderLimit > 0) storage.enforceFolderLimitBytes(folderLimit);
                        }
                        current = storage.createNewSegmentFile(buildBaseName());
                        segmentStartTsMs = p.tsMs > 0 ? p.tsMs : System.currentTimeMillis();
                        writerWidth = p.width;
                        writerHeight = p.height;
                        writer = new MovMjpegWriter(writerWidth, writerHeight, fps, current.timestampMs);
                        writer.open(current.file);
                        lastFlushUptime = SystemClock.uptimeMillis();
                    }
                    writer.writeJpegSample(p.data, p.length, p.tsMs);
                    recyclePacket(p);
                } catch (InterruptedException ignored) {
                } catch (Exception e) {
                    AppLog.w("VideoRecordIo", "io error: " + e.getMessage());
                }
            }
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    };

    private void broadcastState(boolean enabled, String lastPath, long lastTs, long dropped) {
        Intent i = new Intent(ACTION_RECORD_STATE_CHANGED);
        i.putExtra(EXTRA_RECORD_ENABLED, enabled);
        i.putExtra(EXTRA_RECORD_DROPPED, dropped);
        if (lastPath != null) i.putExtra(EXTRA_RECORD_LAST_PATH, lastPath);
        if (lastTs > 0) i.putExtra(EXTRA_RECORD_LAST_TS, lastTs);
        try {
            context.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    private String buildBaseName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
        return sdf.format(new Date());
    }

    private boolean readBoolean(String key, String legacyKey, boolean def) {
        if (prefs.contains(key)) return prefs.getBoolean(key, def);
        return prefs.getBoolean(legacyKey, def);
    }

    private int readInt(String key, int def) {
        try {
            return prefs.getInt(key, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    private byte[] takeBuffer(int minCapacity) {
        if (minCapacity <= 0) minCapacity = 1;
        byte[] best = null;
        for (byte[] b : bufferPool) {
            if (b != null && b.length >= minCapacity) {
                best = b;
                break;
            }
        }
        if (best != null) {
            bufferPool.remove(best);
            return best;
        }
        return new byte[Math.max(minCapacity, 256 * 1024)];
    }

    private void offerBuffer(byte[] b) {
        if (b == null) return;
        if (b.length > 2 * 1024 * 1024) return;
        if (bufferPool.size() >= 16) return;
        bufferPool.addLast(b);
    }

    private void recyclePacket(FramePacket p) {
        if (p == null) return;
        offerBuffer(p.data);
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static final class FramePacket {
        final byte[] data;
        final int length;
        final long tsMs;
        final int width;
        final int height;

        FramePacket(byte[] data, int length, long tsMs, int width, int height) {
            this.data = data;
            this.length = length;
            this.tsMs = tsMs;
            this.width = width;
            this.height = height;
        }
    }

    private static final class StealableByteArrayOutputStream extends ByteArrayOutputStream {
        StealableByteArrayOutputStream(int initialCapacity) {
            super(initialCapacity);
        }

        byte[] stealTo(byte[] replacement) {
            byte[] b = this.buf;
            this.buf = replacement != null ? replacement : new byte[Math.max(1, b != null ? b.length : 256 * 1024)];
            this.count = 0;
            return b;
        }
    }
}
