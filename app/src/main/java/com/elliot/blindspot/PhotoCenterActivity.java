package com.elliot.blindspot;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoCenterActivity extends Activity {
    private static final String PREFS_NAME = "turning_blind_spot_prefs";

    private SharedPreferences prefs;
    private ExecutorService ioExecutor;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private View layoutSettings;
    private View layoutAlbum;
    private Button btnTabSettings;
    private Button btnTabAlbum;

    private EditText etRecordFps;
    private EditText etSegmentSec;
    private Spinner spStoreMode;
    private EditText etUsbPath;
    private TextView tvStoreHint;
    private EditText etLimitMb;
    private Button btnCleanupNow;
    private TextView tvFolderStats;
    private Button btnRefreshStats;
    private Spinner spFormat;
    private EditText etMaxWidth;
    private TextView tvQuality;
    private SeekBar sbQuality;
    private Spinner spCaptureSource;
    private Switch swAutoOnService;
    private Button btnSaveSettings;
    private Switch swPhotoOverlay;
    private TextView tvOverlaySize;
    private SeekBar sbOverlaySize;
    private TextView tvOverlayAlpha;
    private SeekBar sbOverlayAlpha;

    private Button btnRefreshAlbum;
    private RecyclerView rvTimeline;
    private TextView tvPlaybackTitle;
    private Button btnPlay;
    private Button btnPause;
    private Button btnStop;
    private SeekBar sbProgress;
    private ImageView ivFront;
    private ImageView ivBack;
    private ImageView ivLeft;
    private ImageView ivRight;

    private final LruCache<String, android.graphics.Bitmap> thumbCache = new LruCache<String, android.graphics.Bitmap>(
            Math.max(2048, (int) (Runtime.getRuntime().maxMemory() / 1024 / 24))) {
        @Override
        protected int sizeOf(String key, android.graphics.Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private SegmentTimelineAdapter timelineAdapter;

    private volatile File currentSegmentFile;
    private volatile MovMjpegReader currentReader;
    private volatile int currentSampleIndex;
    private volatile boolean playing;
    private volatile boolean paused;
    private volatile boolean decodeInFlight;
    private volatile long playbackIntervalMs = 100;
    private volatile long currentDurationMs = 0;
    private volatile boolean userSeeking = false;
    private Runnable playbackLoop;

    private android.graphics.Bitmap quadFront;
    private android.graphics.Bitmap quadBack;
    private android.graphics.Bitmap quadLeft;
    private android.graphics.Bitmap quadRight;
    private android.graphics.Canvas quadFrontCanvas;
    private android.graphics.Canvas quadBackCanvas;
    private android.graphics.Canvas quadLeftCanvas;
    private android.graphics.Canvas quadRightCanvas;
    private final android.graphics.Rect quadSrc = new android.graphics.Rect();
    private final android.graphics.Rect quadDst = new android.graphics.Rect();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_center);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        ioExecutor = Executors.newSingleThreadExecutor();

        Button btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        btnTabSettings = findViewById(R.id.btn_tab_settings);
        btnTabAlbum = findViewById(R.id.btn_tab_album);
        layoutSettings = findViewById(R.id.layout_settings);
        layoutAlbum = findViewById(R.id.layout_album);

        etRecordFps = findViewById(R.id.et_record_fps);
        etSegmentSec = findViewById(R.id.et_segment_sec);
        spStoreMode = findViewById(R.id.sp_store_mode);
        etUsbPath = findViewById(R.id.et_usb_path);
        tvStoreHint = findViewById(R.id.tv_store_hint);
        etLimitMb = findViewById(R.id.et_limit_mb);
        btnCleanupNow = findViewById(R.id.btn_cleanup_now);
        tvFolderStats = findViewById(R.id.tv_folder_stats);
        btnRefreshStats = findViewById(R.id.btn_refresh_stats);
        spFormat = findViewById(R.id.sp_format);
        etMaxWidth = findViewById(R.id.et_max_width);
        tvQuality = findViewById(R.id.tv_quality);
        sbQuality = findViewById(R.id.sb_quality);
        spCaptureSource = findViewById(R.id.sp_capture_source);
        swAutoOnService = findViewById(R.id.sw_auto_on_service);
        btnSaveSettings = findViewById(R.id.btn_save_settings);
        swPhotoOverlay = findViewById(R.id.sw_photo_overlay);
        tvOverlaySize = findViewById(R.id.tv_overlay_size);
        sbOverlaySize = findViewById(R.id.sb_overlay_size);
        tvOverlayAlpha = findViewById(R.id.tv_overlay_alpha);
        sbOverlayAlpha = findViewById(R.id.sb_overlay_alpha);

        btnRefreshAlbum = findViewById(R.id.btn_refresh_album);
        rvTimeline = findViewById(R.id.rv_timeline);
        tvPlaybackTitle = findViewById(R.id.tv_playback_title);
        btnPlay = findViewById(R.id.btn_play);
        btnPause = findViewById(R.id.btn_pause);
        btnStop = findViewById(R.id.btn_stop);
        sbProgress = findViewById(R.id.sb_progress);
        ivFront = findViewById(R.id.iv_front);
        ivBack = findViewById(R.id.iv_back);
        ivLeft = findViewById(R.id.iv_left);
        ivRight = findViewById(R.id.iv_right);

        btnTabSettings.setOnClickListener(v -> showTab(true));
        btnTabAlbum.setOnClickListener(v -> showTab(false));

        initSettingsUi();
        initPlaybackUi();

        showTab(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        if (quadFront != null && !quadFront.isRecycled()) quadFront.recycle();
        if (quadBack != null && !quadBack.isRecycled()) quadBack.recycle();
        if (quadLeft != null && !quadLeft.isRecycled()) quadLeft.recycle();
        if (quadRight != null && !quadRight.isRecycled()) quadRight.recycle();
        quadFront = null;
        quadBack = null;
        quadLeft = null;
        quadRight = null;
        quadFrontCanvas = null;
        quadBackCanvas = null;
        quadLeftCanvas = null;
        quadRightCanvas = null;
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }

    private void showTab(boolean settings) {
        layoutSettings.setVisibility(settings ? View.VISIBLE : View.GONE);
        layoutAlbum.setVisibility(settings ? View.GONE : View.VISIBLE);
        btnTabSettings.setBackgroundResource(settings ? R.drawable.btn_primary : R.drawable.btn_secondary);
        btnTabAlbum.setBackgroundResource(settings ? R.drawable.btn_secondary : R.drawable.btn_primary);
        btnTabSettings.setTextColor(getColor(settings ? R.color.white : R.color.text_primary));
        btnTabAlbum.setTextColor(getColor(settings ? R.color.text_primary : R.color.white));
        if (settings) stopPlayback();
        if (!settings) loadSegmentsAsync(false);
    }

    private void initSettingsUi() {
        ensureStoragePermissionOrRequest();
        ArrayAdapter<String> storeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        storeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        storeAdapter.add("内置存储（DCIM）");
        storeAdapter.add("U盘（路径）");
        spStoreMode.setAdapter(storeAdapter);

        ArrayAdapter<String> fmtAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        fmtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fmtAdapter.add("mov(mjpeg)");
        spFormat.setAdapter(fmtAdapter);

        ArrayAdapter<String> srcAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        srcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        srcAdapter.add("OpenGL（推荐）");
        srcAdapter.add("TextureView（兼容）");
        spCaptureSource.setAdapter(srcAdapter);

        int fps = prefs.getInt(VideoRecordController.PREF_RECORD_FPS, 0);
        if (fps <= 0) {
            long iv = Math.max(100, prefs.getLong(PhotoCaptureController.PREF_PHOTO_INTERVAL_MS, 1000));
            fps = Math.max(1, Math.min(25, (int) Math.round(1000.0 / (double) iv)));
        }
        etRecordFps.setText(String.valueOf(fps));

        int seg = prefs.getInt(VideoRecordController.PREF_RECORD_SEGMENT_SECONDS, 10);
        seg = Math.max(1, Math.min(120, seg));
        etSegmentSec.setText(String.valueOf(seg));

        long limitBytes = prefs.getLong(VideoRecordController.PREF_RECORD_FOLDER_LIMIT_BYTES,
                prefs.getLong(PhotoCaptureController.PREF_PHOTO_FOLDER_LIMIT_BYTES, 0));
        long limitMb = limitBytes <= 0 ? 0 : (limitBytes / (1024 * 1024));
        etLimitMb.setText(String.valueOf(limitMb));

        int maxW = Math.max(320, prefs.getInt(VideoRecordController.PREF_RECORD_MAX_WIDTH,
                prefs.getInt(PhotoCaptureController.PREF_PHOTO_MAX_WIDTH, 2560)));
        etMaxWidth.setText(String.valueOf(maxW));

        int quality = clamp(prefs.getInt(VideoRecordController.PREF_RECORD_JPEG_QUALITY,
                prefs.getInt(PhotoCaptureController.PREF_PHOTO_QUALITY, 85)), 0, 100);
        sbQuality.setProgress(quality);
        tvQuality.setText("压缩质量：" + quality);
        sbQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvQuality.setText("压缩质量：" + progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        swAutoOnService.setChecked(prefs.getBoolean(VideoRecordController.PREF_RECORD_AUTO_START_ON_SERVICE,
                prefs.getBoolean(PhotoCaptureController.PREF_PHOTO_AUTO_START_ON_SERVICE, false)));

        boolean overlayEnabled = prefs.getBoolean(VideoRecordController.PREF_RECORD_OVERLAY_ENABLED,
                prefs.getBoolean(PhotoCaptureController.PREF_PHOTO_OVERLAY_ENABLED, false));
        int overlaySizeDp = clamp(prefs.getInt(VideoRecordController.PREF_RECORD_OVERLAY_SIZE_DP,
                prefs.getInt(PhotoCaptureController.PREF_PHOTO_OVERLAY_SIZE_DP, 96)), 32, 240);
        int overlayAlpha = clamp(prefs.getInt(VideoRecordController.PREF_RECORD_OVERLAY_ALPHA_PERCENT,
                prefs.getInt(PhotoCaptureController.PREF_PHOTO_OVERLAY_ALPHA_PERCENT, 70)), 5, 100);
        swPhotoOverlay.setChecked(overlayEnabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sbOverlaySize.setMin(32);
            sbOverlayAlpha.setMin(5);
        }
        sbOverlaySize.setMax(240);
        sbOverlayAlpha.setMax(100);
        sbOverlaySize.setProgress(overlaySizeDp);
        sbOverlayAlpha.setProgress(overlayAlpha);
        tvOverlaySize.setText("大小：" + overlaySizeDp + "dp");
        tvOverlayAlpha.setText("透明度：" + overlayAlpha + "%");
        sbOverlaySize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = clamp(progress, 32, 240);
                tvOverlaySize.setText("大小：" + v + "dp");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sbOverlayAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = clamp(progress, 5, 100);
                tvOverlayAlpha.setText("透明度：" + v + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        spFormat.setSelection(0);

        String src = prefs.getString(VideoRecordController.PREF_RECORD_CAPTURE_SOURCE, null);
        if (src == null || src.isEmpty()) {
            src = prefs.getString(PhotoCaptureController.PREF_PHOTO_CAPTURE_SOURCE, VideoRecordController.CAPTURE_SOURCE_GL);
        }
        spCaptureSource.setSelection(VideoRecordController.CAPTURE_SOURCE_TEXTURE.equals(src) ? 1 : 0);

        String mode = prefs.getString(VideoStorage.PREF_RECORD_STORE_MODE,
                prefs.getString(PhotoStorage.PREF_PHOTO_STORE_MODE, VideoStorage.STORE_MODE_INTERNAL));
        spStoreMode.setSelection(modeIndex(mode));
        etUsbPath.setText(prefs.getString(VideoStorage.PREF_RECORD_STORE_PATH,
                prefs.getString(PhotoStorage.PREF_PHOTO_STORE_PATH, "")));

        updateStoreModeUi(mode);
        spStoreMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String m = position == 1 ? VideoStorage.STORE_MODE_USB_PATH
                        : VideoStorage.STORE_MODE_INTERNAL;
                updateStoreModeUi(m);
                refreshFolderStatsAsync();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        btnCleanupNow.setOnClickListener(v -> cleanupNow());
        btnRefreshStats.setOnClickListener(v -> refreshFolderStatsAsync());
        btnSaveSettings.setOnClickListener(v -> saveSettings());
        refreshFolderStatsAsync();
    }

    private void updateStoreModeUi(String mode) {
        etUsbPath.setVisibility(VideoStorage.STORE_MODE_USB_PATH.equals(mode) ? View.VISIBLE : View.GONE);
        if (VideoStorage.STORE_MODE_USB_PATH.equals(mode)) {
            ensureStoragePermissionOrRequest();
            String detected = StorageHelper.getSdCardFromMounts();
            if (detected == null) detected = StorageHelper.getSdCardFromExternalFilesDirs(this);
            if (detected != null && detected.length() > 0) {
                String current = etUsbPath.getText() != null ? etUsbPath.getText().toString().trim() : "";
                if (current.isEmpty()) {
                    etUsbPath.setText(detected);
                    prefs.edit().putString(VideoStorage.PREF_RECORD_STORE_PATH, detected).apply();
                }
                File dcim = new File(detected, Environment.DIRECTORY_DCIM);
                if (!dcim.exists()) dcim.mkdirs();
                boolean writable = dcim.exists() && dcim.canWrite();
                tvStoreHint.setText("检测到U盘：" + detected + "\n可写："
                        + (writable ? "是" : "否")
                        + "\n将写入：" + detected + "/E02REC/");
            } else {
                tvStoreHint.setText("未检测到U盘。请插入U盘后再试，或手动填入 /storage/XXXX-XXXX");
            }
        } else {
            tvStoreHint.setText("默认：DCIM/E02REC");
        }
    }

    private void cleanupNow() {
        long mb = safeParseLong(etLimitMb.getText() != null ? etLimitMb.getText().toString() : "0", 0);
        long bytes = Math.max(0, mb) * 1024L * 1024L;
        if (bytes <= 0) {
            Toast.makeText(this, "上限为0，未启用自动清理", Toast.LENGTH_SHORT).show();
            return;
        }
        ioExecutor.execute(() -> {
            try {
                new VideoStorage(getApplicationContext(), prefs).enforceFolderLimitBytes(bytes);
                runOnUiThread(() -> {
                    Toast.makeText(this, "清理完成", Toast.LENGTH_SHORT).show();
                    refreshFolderStatsAsync();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "清理失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void refreshFolderStatsAsync() {
        if (tvFolderStats == null) return;
        tvFolderStats.setText("当前占用：统计中…");
        ioExecutor.execute(() -> {
            VideoStorage.FolderStats s = new VideoStorage(getApplicationContext(), prefs).getFolderStats();
            String text = "当前占用：" + formatBytes(s.bytes) + "，" + s.count + " 段";
            runOnUiThread(() -> tvFolderStats.setText(text));
        });
    }

    private void saveSettings() {
        int fps = (int) safeParseLong(etRecordFps.getText() != null ? etRecordFps.getText().toString() : "10", 10);
        fps = clamp(fps, 1, 25);

        int seg = (int) safeParseLong(etSegmentSec.getText() != null ? etSegmentSec.getText().toString() : "10", 10);
        seg = clamp(seg, 1, 120);

        long limitMb = safeParseLong(etLimitMb.getText() != null ? etLimitMb.getText().toString() : "0", 0);
        long limitBytes = Math.max(0, limitMb) * 1024L * 1024L;

        int maxW = (int) safeParseLong(etMaxWidth.getText() != null ? etMaxWidth.getText().toString() : "2560", 2560);
        maxW = Math.max(320, maxW);

        int quality = clamp(sbQuality.getProgress(), 0, 100);
        String src = spCaptureSource.getSelectedItemPosition() == 1
                ? VideoRecordController.CAPTURE_SOURCE_TEXTURE
                : VideoRecordController.CAPTURE_SOURCE_GL;

        int storePos = spStoreMode.getSelectedItemPosition();
        String mode = storePos == 1 ? VideoStorage.STORE_MODE_USB_PATH
                : VideoStorage.STORE_MODE_INTERNAL;
        String usbPath = etUsbPath.getText() != null ? etUsbPath.getText().toString().trim() : "";

        prefs.edit()
                .putInt(VideoRecordController.PREF_RECORD_FPS, fps)
                .putInt(VideoRecordController.PREF_RECORD_SEGMENT_SECONDS, seg)
                .putLong(VideoRecordController.PREF_RECORD_FOLDER_LIMIT_BYTES, limitBytes)
                .putInt(VideoRecordController.PREF_RECORD_MAX_WIDTH, maxW)
                .putInt(VideoRecordController.PREF_RECORD_JPEG_QUALITY, quality)
                .putString(VideoRecordController.PREF_RECORD_CAPTURE_SOURCE, src)
                .putBoolean(VideoRecordController.PREF_RECORD_AUTO_START_ON_SERVICE, swAutoOnService.isChecked())
                .putBoolean(VideoRecordController.PREF_RECORD_OVERLAY_ENABLED, swPhotoOverlay.isChecked())
                .putInt(VideoRecordController.PREF_RECORD_OVERLAY_SIZE_DP, clamp(sbOverlaySize.getProgress(), 32, 240))
                .putInt(VideoRecordController.PREF_RECORD_OVERLAY_ALPHA_PERCENT, clamp(sbOverlayAlpha.getProgress(), 5, 100))
                .putString(VideoStorage.PREF_RECORD_STORE_MODE, mode)
                .putString(VideoStorage.PREF_RECORD_STORE_PATH, usbPath)
                .apply();

        updateStoreModeUi(mode);
        refreshFolderStatsAsync();
        try {
            sendBroadcast(new Intent(TurningBlindSpotService.ACTION_SETTINGS_CHANGED));
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void initPlaybackUi() {
        btnRefreshAlbum.setOnClickListener(v -> loadSegmentsAsync(true));
        rvTimeline.setLayoutManager(new LinearLayoutManager(this));
        timelineAdapter = new SegmentTimelineAdapter();
        rvTimeline.setAdapter(timelineAdapter);
        btnPlay.setOnClickListener(v -> startPlayback());
        btnPause.setOnClickListener(v -> togglePause());
        btnStop.setOnClickListener(v -> stopPlayback());
        if (sbProgress != null) {
            sbProgress.setMax(100);
            sbProgress.setProgress(0);
            sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                private boolean resumeAfterSeek;

                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    userSeeking = true;
                    resumeAfterSeek = playing && !paused;
                    paused = true;
                    if (btnPause != null) btnPause.setText("继续");
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    long targetMs = seekBar.getProgress();
                    seekToMs(targetMs, resumeAfterSeek);
                }
            });
        }
        loadSegmentsAsync(true);
    }

    private void loadSegmentsAsync(boolean autoSelectFirst) {
        if (timelineAdapter == null) return;
        ioExecutor.execute(() -> {
            List<SegmentItem> items = listSegments();
            mainHandler.post(() -> {
                timelineAdapter.setItems(items);
                if (autoSelectFirst && items != null && !items.isEmpty()) {
                    selectSegment(items.get(0));
                }
            });
        });
    }

    private List<SegmentItem> listSegments() {
        List<SegmentItem> out = new ArrayList<>();
        File[] files = new VideoStorage(getApplicationContext(), prefs).listAllSegments();
        if (files == null || files.length == 0) return out;
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        int limit = Math.min(files.length, 5000);
        for (int i = 0; i < limit; i++) {
            File f = files[i];
            long ts = parseTimestamp(f.getName(), f.lastModified());
            out.add(new SegmentItem(f, ts));
        }
        return out;
    }

    private void selectSegment(SegmentItem it) {
        if (it == null || it.file == null) return;
        currentSegmentFile = it.file;
        if (tvPlaybackTitle != null) {
            tvPlaybackTitle.setText(it.file.getName());
        }
        timelineAdapter.setSelectedPath(it.file.getAbsolutePath());
        stopPlayback();
        renderFirstFrameAsync(it.file);
    }

    private void renderFirstFrameAsync(File file) {
        if (file == null) return;
        decodeInFlight = true;
        ioExecutor.execute(() -> {
            android.graphics.Bitmap frame = null;
            MovMjpegReader r = null;
            long durMs = 0;
            try {
                r = MovMjpegReader.open(file);
                durMs = r.getTotalDurationMs();
                playbackIntervalMs = computePlaybackIntervalMs(r);
                byte[] jpeg = r.readJpegSample(0);
                if (jpeg != null) frame = decodeJpeg(jpeg);
            } catch (Exception ignored) {
            } finally {
                try {
                    if (r != null) r.close();
                } catch (Exception ignored) {
                }
            }
            android.graphics.Bitmap bmp = frame;
            long finalDurMs = durMs;
            mainHandler.post(() -> {
                if (bmp != null) renderQuadrants(bmp);
                currentDurationMs = Math.max(0, finalDurMs);
                if (sbProgress != null) {
                    int max = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, currentDurationMs));
                    sbProgress.setMax(max);
                    sbProgress.setProgress(0);
                }
                decodeInFlight = false;
            });
        });
    }

    private void startPlayback() {
        if (currentSegmentFile == null) return;
        if (playing) {
            paused = false;
            if (btnPause != null) btnPause.setText("暂停");
            return;
        }
        playing = true;
        paused = false;
        currentSampleIndex = 0;
        closeCurrentReader();
        playbackLoop = new Runnable() {
            @Override
            public void run() {
                if (!playing) return;
                if (paused) {
                    mainHandler.postDelayed(this, playbackIntervalMs);
                    return;
                }
                if (decodeInFlight) {
                    mainHandler.postDelayed(this, playbackIntervalMs);
                    return;
                }
                decodeInFlight = true;
                ioExecutor.execute(() -> {
                    android.graphics.Bitmap frame = null;
                    long delayMs = playbackIntervalMs;
                    try {
                        MovMjpegReader r = currentReader;
                        if (r == null) {
                            r = MovMjpegReader.open(currentSegmentFile);
                            currentReader = r;
                            currentDurationMs = Math.max(0, r.getTotalDurationMs());
                            playbackIntervalMs = computePlaybackIntervalMs(r);
                            currentSampleIndex = 0;
                            int max = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, currentDurationMs));
                            mainHandler.post(() -> {
                                if (sbProgress != null) sbProgress.setMax(max);
                            });
                        }
                        int count = r.getSampleCount();
                        if (count <= 0 || currentSampleIndex >= count) {
                            playing = false;
                        } else {
                            int idx = currentSampleIndex;
                            byte[] jpeg = r.readJpegSample(idx);
                            delayMs = Math.max(30L, r.getSampleDurationMs(idx));
                            currentSampleIndex = idx + 1;
                            if (jpeg != null) frame = decodeJpeg(jpeg);
                        }
                    } catch (Exception ignored) {
                        playing = false;
                    }
                    android.graphics.Bitmap bmp = frame;
                    long nextDelay = delayMs;
                    mainHandler.post(() -> {
                        if (bmp != null) renderQuadrants(bmp);
                        decodeInFlight = false;
                        if (sbProgress != null && !userSeeking) {
                            MovMjpegReader r = currentReader;
                            long pos = r != null ? r.getPositionMsForSampleIndex(currentSampleIndex) : 0;
                            sbProgress.setProgress((int) Math.min(Integer.MAX_VALUE, Math.max(0L, pos)));
                        }
                        if (playing && playbackLoop != null) {
                            mainHandler.postDelayed(playbackLoop, nextDelay);
                        } else {
                            closeCurrentReader();
                        }
                    });
                });
            }
        };
        mainHandler.post(playbackLoop);
    }

    private void seekToMs(long targetMs, boolean resumeAfter) {
        userSeeking = false;
        File f = currentSegmentFile;
        if (f == null) {
            if (resumeAfter) paused = false;
            return;
        }
        decodeInFlight = true;
        ioExecutor.execute(() -> {
            android.graphics.Bitmap frame = null;
            try {
                MovMjpegReader r = currentReader;
                boolean temp = false;
                if (r == null) {
                    r = MovMjpegReader.open(f);
                    temp = true;
                }
                currentDurationMs = Math.max(0, r.getTotalDurationMs());
                int idx = r.findSampleIndexByPositionMs(targetMs);
                byte[] jpeg = r.readJpegSample(idx);
                if (jpeg != null) frame = decodeJpeg(jpeg);
                if (!temp) {
                    currentSampleIndex = idx;
                }
                if (temp) {
                    try {
                        r.close();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            android.graphics.Bitmap bmp = frame;
            mainHandler.post(() -> {
                if (bmp != null) renderQuadrants(bmp);
                decodeInFlight = false;
                if (sbProgress != null) {
                    int max = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, currentDurationMs));
                    sbProgress.setMax(max);
                    sbProgress.setProgress((int) Math.min(Integer.MAX_VALUE, Math.max(0L, targetMs)));
                }
                if (resumeAfter) {
                    paused = false;
                    if (btnPause != null) btnPause.setText("暂停");
                    if (playing && playbackLoop != null) {
                        mainHandler.removeCallbacks(playbackLoop);
                        mainHandler.post(playbackLoop);
                    }
                }
            });
        });
    }

    private void togglePause() {
        if (!playing) return;
        paused = !paused;
        if (btnPause != null) btnPause.setText(paused ? "继续" : "暂停");
    }

    private void stopPlayback() {
        playing = false;
        paused = false;
        decodeInFlight = false;
        if (btnPause != null) btnPause.setText("暂停");
        if (playbackLoop != null) {
            mainHandler.removeCallbacks(playbackLoop);
            playbackLoop = null;
        }
        closeCurrentReader();
    }

    private void closeCurrentReader() {
        MovMjpegReader r = currentReader;
        currentReader = null;
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static long computePlaybackIntervalMs(MovMjpegReader r) {
        if (r == null) return 100;
        int timescale = r.getTimescale();
        int dur = r.getSampleDuration();
        if (timescale <= 0 || dur <= 0) return 100;
        double ms = (dur * 1000.0) / (double) timescale;
        return Math.max(30, Math.round(ms));
    }

    private android.graphics.Bitmap decodeJpeg(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void ensureQuadBuffers(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (quadFront == null || quadFront.isRecycled() || quadFront.getWidth() != w || quadFront.getHeight() != h) {
            if (quadFront != null && !quadFront.isRecycled()) quadFront.recycle();
            quadFront = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
            quadFrontCanvas = new android.graphics.Canvas(quadFront);
        }
        if (quadBack == null || quadBack.isRecycled() || quadBack.getWidth() != w || quadBack.getHeight() != h) {
            if (quadBack != null && !quadBack.isRecycled()) quadBack.recycle();
            quadBack = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
            quadBackCanvas = new android.graphics.Canvas(quadBack);
        }
        if (quadLeft == null || quadLeft.isRecycled() || quadLeft.getWidth() != w || quadLeft.getHeight() != h) {
            if (quadLeft != null && !quadLeft.isRecycled()) quadLeft.recycle();
            quadLeft = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
            quadLeftCanvas = new android.graphics.Canvas(quadLeft);
        }
        if (quadRight == null || quadRight.isRecycled() || quadRight.getWidth() != w || quadRight.getHeight() != h) {
            if (quadRight != null && !quadRight.isRecycled()) quadRight.recycle();
            quadRight = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
            quadRightCanvas = new android.graphics.Canvas(quadRight);
        }
    }

    private void renderQuadrants(android.graphics.Bitmap full) {
        if (full == null || full.isRecycled()) return;
        int outW = ivFront != null ? ivFront.getWidth() : 0;
        int outH = ivFront != null ? ivFront.getHeight() : 0;
        if (outW <= 0 || outH <= 0) {
            if (ivFront != null) {
                ivFront.post(() -> renderQuadrants(full));
            }
            return;
        }
        ensureQuadBuffers(outW, outH);
        quadDst.set(0, 0, outW, outH);
        drawOne(full, CameraLayoutConfig.Direction.FRONT, quadFrontCanvas);
        drawOne(full, CameraLayoutConfig.Direction.BACK, quadBackCanvas);
        drawOne(full, CameraLayoutConfig.Direction.LEFT, quadLeftCanvas);
        drawOne(full, CameraLayoutConfig.Direction.RIGHT, quadRightCanvas);
        if (ivFront != null) ivFront.setImageBitmap(quadFront);
        if (ivBack != null) ivBack.setImageBitmap(quadBack);
        if (ivLeft != null) ivLeft.setImageBitmap(quadLeft);
        if (ivRight != null) ivRight.setImageBitmap(quadRight);
    }

    private void drawOne(android.graphics.Bitmap full, CameraLayoutConfig.Direction dir, android.graphics.Canvas canvas) {
        if (canvas == null) return;
        int w = full.getWidth();
        int h = full.getHeight();
        float[] r = CameraLayoutConfig.getCropRectNormalized(prefs, dir);
        int l = Math.round(r[0] * w);
        int t = Math.round(r[1] * h);
        int rr = Math.round(r[2] * w);
        int bb = Math.round(r[3] * h);
        l = Math.max(0, Math.min(l, w - 2));
        t = Math.max(0, Math.min(t, h - 2));
        rr = Math.max(l + 2, Math.min(rr, w));
        bb = Math.max(t + 2, Math.min(bb, h));
        quadSrc.set(l, t, rr, bb);
        canvas.drawColor(android.graphics.Color.BLACK);
        canvas.drawBitmap(full, quadSrc, quadDst, null);
    }

    private static int modeIndex(String mode) {
        if (VideoStorage.STORE_MODE_USB_PATH.equals(mode)) return 1;
        return 0;
    }

    private static int indexOf(ArrayAdapter<String> adapter, String val) {
        if (adapter == null || val == null) return 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            String s = adapter.getItem(i);
            if (val.equalsIgnoreCase(s)) return i;
        }
        return 0;
    }

    private static long parseTimestamp(String name, long fallback) {
        if (name == null) return fallback;
        String base = name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
            Date d = sdf.parse(base);
            if (d != null) return d.getTime();
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static long safeParseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) bytes = 0;
        double b = bytes;
        double kb = b / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        if (gb >= 1.0) return String.format(Locale.US, "%.2fGB", gb);
        if (mb >= 1.0) return String.format(Locale.US, "%.1fMB", mb);
        if (kb >= 1.0) return String.format(Locale.US, "%.1fKB", kb);
        return bytes + "B";
    }

    private void ensureStoragePermissionOrRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[] { android.Manifest.permission.READ_MEDIA_VIDEO }, 1201);
            return;
        }
        boolean readGranted = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        boolean writeGranted = Build.VERSION.SDK_INT > Build.VERSION_CODES.P
                || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (readGranted && writeGranted) return;
        String[] perms = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                ? new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE }
                : new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE };
        requestPermissions(perms, 1201);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1201) {
            refreshFolderStatsAsync();
            String mode = prefs.getString(VideoStorage.PREF_RECORD_STORE_MODE, VideoStorage.STORE_MODE_INTERNAL);
            updateStoreModeUi(mode);
        }
    }

    static final class SegmentItem {
        final File file;
        final long timestampMs;

        SegmentItem(File file, long timestampMs) {
            this.file = file;
            this.timestampMs = timestampMs;
        }
    }

    final class SegmentTimelineAdapter extends RecyclerView.Adapter<SegmentTimelineAdapter.VH> {
        private final java.text.SimpleDateFormat timeFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
        private List<SegmentItem> items = new ArrayList<>();
        private String selectedPath;

        void setItems(List<SegmentItem> items) {
            this.items = items != null ? items : new ArrayList<>();
            notifyDataSetChanged();
        }

        void setSelectedPath(String p) {
            selectedPath = p;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_album_group, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            SegmentItem it = items.get(position);
            String path = it.file != null ? it.file.getAbsolutePath() : "";
            holder.itemView.setTag(path);
            holder.tvRange.setText(timeFmt.format(new Date(it.timestampMs)));
            holder.tvMeta.setText("大小：" + formatBytes(safeLen(it.file)));
            holder.itemView.setAlpha(path != null && path.equals(selectedPath) ? 1.0f : 0.78f);
            android.graphics.Bitmap cached = path != null ? thumbCache.get(path) : null;
            if (cached != null) {
                holder.ivThumb.setImageBitmap(cached);
            } else {
                holder.ivThumb.setImageDrawable(null);
                if (path != null && it.file != null && it.file.exists()) {
                    thumbExecutor.execute(() -> {
                        android.graphics.Bitmap bmp = decodeFirstFrameThumb(it.file, 320);
                        if (bmp != null && path != null) thumbCache.put(path, bmp);
                        holder.itemView.post(() -> {
                            Object tag = holder.itemView.getTag();
                            if (tag != null && tag.equals(path) && bmp != null) {
                                holder.ivThumb.setImageBitmap(bmp);
                            }
                        });
                    });
                }
            }
            holder.itemView.setOnClickListener(v -> selectSegment(it));
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final TextView tvRange;
            final TextView tvMeta;

            VH(View itemView) {
                super(itemView);
                ivThumb = itemView.findViewById(R.id.iv_thumb);
                tvRange = itemView.findViewById(R.id.tv_range);
                tvMeta = itemView.findViewById(R.id.tv_meta);
            }
        }
    }

    private android.graphics.Bitmap decodeFirstFrameThumb(File f, int reqWidth) {
        if (f == null || !f.exists()) return null;
        byte[] jpeg = null;
        try (MovMjpegReader r = MovMjpegReader.open(f)) {
            jpeg = r.readJpegSample(0);
        } catch (Exception ignored) {
        }
        if (jpeg == null || jpeg.length == 0) return null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            int w = o.outWidth;
            if (w <= 0) return null;
            int inSample = 1;
            while (w / inSample > reqWidth) inSample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = Math.max(1, inSample);
            o2.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o2);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long safeLen(File f) {
        try {
            return f != null ? f.length() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    static final class PhotoItem {
        final String name;
        final long timestampMs;
        final Uri uri;

        PhotoItem(String name, long timestampMs, Uri uri) {
            this.name = name;
            this.timestampMs = timestampMs;
            this.uri = uri;
        }
    }

    static final class PhotoAdapter extends RecyclerView.Adapter<PhotoVH> {
        private final Context context;
        private final LruCache<String, android.graphics.Bitmap> cache;
        private final ExecutorService thumbExecutor;
        private List<PhotoItem> items = new ArrayList<>();

        PhotoAdapter(Context context) {
            this.context = context.getApplicationContext();
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 16);
            cache = new LruCache<String, android.graphics.Bitmap>(Math.max(4096, maxKb)) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
            thumbExecutor = Executors.newFixedThreadPool(2);
        }

        void setItems(List<PhotoItem> items) {
            this.items = items != null ? items : new ArrayList<>();
            notifyDataSetChanged();
        }

        List<PhotoItem> getItems() {
            return items;
        }

        @Override
        public PhotoVH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo_grid, parent, false);
            return new PhotoVH(v);
        }

        @Override
        public void onBindViewHolder(PhotoVH holder, int position) {
            PhotoItem it = items.get(position);
            String key = it.uri.toString();
            holder.itemView.setTag(key);
            holder.tvTime.setText(formatTime(it.timestampMs));
            android.graphics.Bitmap cached = cache.get(key);
            if (cached != null) {
                holder.ivThumb.setImageBitmap(cached);
            } else {
                holder.ivThumb.setImageDrawable(null);
                thumbExecutor.execute(() -> {
                    android.graphics.Bitmap bmp = decodeThumb(context, it.uri, 320);
                    if (bmp != null) cache.put(key, bmp);
                    holder.itemView.post(() -> {
                        Object tag = holder.itemView.getTag();
                        if (tag != null && tag.equals(key) && bmp != null) {
                            holder.ivThumb.setImageBitmap(bmp);
                        }
                    });
                });
            }
            holder.itemView.setOnClickListener(v -> {
                Intent i = new Intent(v.getContext(), PhotoViewerActivity.class);
                i.putExtra("uri", it.uri.toString());
                i.putExtra("name", it.name);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                v.getContext().startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        private static String formatTime(long ts) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                return sdf.format(new Date(ts));
            } catch (Exception ignored) {
                return "--:--:--";
            }
        }

        private static android.graphics.Bitmap decodeThumb(Context context, Uri uri, int reqWidth) {
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                    if (is == null) return null;
                    android.graphics.BitmapFactory.decodeStream(is, null, o);
                }
                int w = o.outWidth;
                int h = o.outHeight;
                if (w <= 0 || h <= 0) return null;
                int inSample = 1;
                while (w / inSample > reqWidth) inSample *= 2;
                android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                o2.inSampleSize = Math.max(1, inSample);
                o2.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                try (InputStream is2 = context.getContentResolver().openInputStream(uri)) {
                    if (is2 == null) return null;
                    return android.graphics.BitmapFactory.decodeStream(is2, null, o2);
                }
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    static final class PhotoVH extends RecyclerView.ViewHolder {
        final ImageView ivThumb;
        final TextView tvTime;

        PhotoVH(View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.iv_thumb);
            tvTime = itemView.findViewById(R.id.tv_time);
        }
    }

    static final class AlbumGroup {
        final long startMs;
        final long endMs;
        final int count;
        final long minTs;
        final long maxTs;
        final Uri thumbUri;

        AlbumGroup(long startMs, long endMs, int count, long minTs, long maxTs, Uri thumbUri) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.count = count;
            this.minTs = minTs;
            this.maxTs = maxTs;
            this.thumbUri = thumbUri;
        }
    }

    private static final class AlbumGroupBuilder {
        final long startMs;
        final long endMs;
        int count = 0;
        long minTs = Long.MAX_VALUE;
        long maxTs = 0;
        Uri thumbUri;

        AlbumGroupBuilder(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        void add(PhotoItem it) {
            count++;
            if (it.timestampMs < minTs) minTs = it.timestampMs;
            if (it.timestampMs > maxTs) {
                maxTs = it.timestampMs;
                thumbUri = it.uri;
            }
        }

        AlbumGroup build() {
            long min = minTs == Long.MAX_VALUE ? startMs : minTs;
            long max = maxTs <= 0 ? startMs : maxTs;
            return new AlbumGroup(startMs, endMs, count, min, max, thumbUri);
        }
    }

    static final class AlbumGroupAdapter extends RecyclerView.Adapter<AlbumGroupVH> {
        private final Context context;
        private final LruCache<String, android.graphics.Bitmap> cache;
        private final ExecutorService thumbExecutor;
        private List<AlbumGroup> groups = new ArrayList<>();

        AlbumGroupAdapter(Context context) {
            this.context = context.getApplicationContext();
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 24);
            cache = new LruCache<String, android.graphics.Bitmap>(Math.max(2048, maxKb)) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
            thumbExecutor = Executors.newFixedThreadPool(2);
        }

        void setGroups(List<AlbumGroup> groups) {
            this.groups = groups != null ? groups : new ArrayList<>();
            notifyDataSetChanged();
        }

        @Override
        public AlbumGroupVH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_album_group, parent, false);
            return new AlbumGroupVH(v);
        }

        @Override
        public void onBindViewHolder(AlbumGroupVH holder, int position) {
            AlbumGroup g = groups.get(position);
            holder.tvRange.setText(formatRange(g.minTs, g.maxTs));
            holder.tvMeta.setText("分片：" + g.count);
            String key = g.thumbUri != null ? g.thumbUri.toString() : ("group:" + g.startMs);
            holder.itemView.setTag(key);
            android.graphics.Bitmap cached = cache.get(key);
            if (cached != null) {
                holder.ivThumb.setImageBitmap(cached);
            } else {
                holder.ivThumb.setImageDrawable(null);
                if (g.thumbUri != null) {
                    thumbExecutor.execute(() -> {
                        android.graphics.Bitmap bmp = decodeThumb(context, g.thumbUri, 320);
                        if (bmp != null) cache.put(key, bmp);
                        holder.itemView.post(() -> {
                            Object tag = holder.itemView.getTag();
                            if (tag != null && tag.equals(key) && bmp != null) {
                                holder.ivThumb.setImageBitmap(bmp);
                            }
                        });
                    });
                }
            }

            holder.itemView.setOnClickListener(v -> {
                Intent i = new Intent(v.getContext(), PhotoGroupActivity.class);
                i.putExtra("bucket_start", g.startMs);
                i.putExtra("bucket_ms", g.endMs - g.startMs);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                v.getContext().startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return groups != null ? groups.size() : 0;
        }

        private static String formatRange(long minTs, long maxTs) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                return sdf.format(new Date(minTs)) + " ~ " + sdf.format(new Date(maxTs));
            } catch (Exception ignored) {
                return "--";
            }
        }

        private static android.graphics.Bitmap decodeThumb(Context context, Uri uri, int reqWidth) {
            try {
                String p = uri.getPath();
                if (p == null || p.isEmpty()) return null;
                File f = new File(p);
                if (!f.exists()) return null;
                byte[] jpeg;
                try (MovMjpegReader r = MovMjpegReader.open(f)) {
                    jpeg = r.readJpegSample(0);
                }
                if (jpeg == null || jpeg.length == 0) return null;
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                int w = o.outWidth;
                int h = o.outHeight;
                if (w <= 0 || h <= 0) return null;
                int inSample = 1;
                while (w / inSample > reqWidth) inSample *= 2;
                android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                o2.inSampleSize = Math.max(1, inSample);
                o2.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o2);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    static final class AlbumGroupVH extends RecyclerView.ViewHolder {
        final ImageView ivThumb;
        final TextView tvRange;
        final TextView tvMeta;

        AlbumGroupVH(View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.iv_thumb);
            tvRange = itemView.findViewById(R.id.tv_range);
            tvMeta = itemView.findViewById(R.id.tv_meta);
        }
    }
}
