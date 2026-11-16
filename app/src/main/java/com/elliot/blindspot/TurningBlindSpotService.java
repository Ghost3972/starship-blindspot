package com.elliot.blindspot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.app.ActivityManager;
import android.app.Presentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Arrays;

/**
 * 转向补盲服务
 * 监听转向信号，显示盲区摄像头画面

 * 
 */
public class TurningBlindSpotService extends Service implements TurningSignalMonitor.TurningSignalCallback {
    private static final String TAG = "TurningBlindSpotService";
    private static final String CHANNEL_ID = "turning_blind_spot_channel";
    private static final int NOTIFICATION_ID = 1001;

    // SharedPreferences 相关
    private static final String PREFS_NAME = "turning_blind_spot_prefs";
    private static final String PREF_WINDOW_X = "window_x";
    private static final String PREF_WINDOW_Y = "window_y";
    private static final String PREF_WINDOW_WIDTH = "window_width";
    private static final String PREF_WINDOW_HEIGHT = "window_height";
    private static final String PREF_SHOW_INDICATOR = "show_indicator";
    private static final String PREF_SHOW_FLOATING_WINDOW = "show_floating_window";
    public static final String PREF_CAMERA_ID = "camera_id";

    // 画质设置
    private static final String PREF_BRIGHTNESS = "brightness";
    private static final String PREF_CONTRAST = "contrast";
    private static final String PREF_WHITE_BALANCE = "white_balance";
    private static final String PREF_DISPLAY_DELAY = "display_delay";
    public static final String ACTION_SETTINGS_CHANGED = "com.elliot.blindspot.SETTINGS_CHANGED";
    public static final String ACTION_RECORD_START = "com.elliot.blindspot.RECORD_START";
    public static final String ACTION_RECORD_STOP = "com.elliot.blindspot.RECORD_STOP";
    public static final String ACTION_RECORD_TAKE_PHOTO = "com.elliot.blindspot.RECORD_TAKE_PHOTO";
    public static final String ACTION_PHOTO_START_CONTINUOUS = "com.elliot.blindspot.PHOTO_START_CONTINUOUS";
    public static final String ACTION_PHOTO_STOP_CONTINUOUS = "com.elliot.blindspot.PHOTO_STOP_CONTINUOUS";
    public static final String ACTION_PHOTO_TAKE_ONCE = "com.elliot.blindspot.PHOTO_TAKE_ONCE";

    // 默认转向预览尺寸（提升画质 - 与象限比例一致 1280:800 = 8:5）
    private static final int DEFAULT_PREVIEW_WIDTH = 1280;
    private static final int DEFAULT_PREVIEW_HEIGHT = 800;
    private static final int MIN_PREVIEW_SIZE = 400; // 最小尺寸
    private static final int MAX_PREVIEW_WIDTH = 1600; // 最大宽度
    private static final int MAX_PREVIEW_HEIGHT = 900; // 最大高度

    // 相机参数（E02 默认）
    private static final int CAMERA_WIDTH = 5120;
    private static final int CAMERA_HEIGHT = 800;

    // 目标地图应用包名
    private static final String TARGET_MAP_PACKAGE = "com.flyme.auto.map";

    // 动画参数（0 = 无动画，立即显示/隐藏）
    private static final int MIN_DISPLAY_TIME_MS = 3000;
    private static final int ENTER_ANIMATION_MS = 0;
    private static final int EXIT_ANIMATION_MS = 0;
    private static final int CORNER_RADIUS_DP = 28; // 圆角半径

    // 静态状态
    private static boolean isServiceRunning = false;

    // UI 组件
    private WindowManager windowManager;
    private FrameLayout previewContainer;
    private TextureView previewTextureView;
    private android.widget.ImageView blindspotImageView; // 补盲裁切显示（覆盖在 TextureView 上）
    private Runnable blindspotRefreshRunnable; // 补盲画面刷新循环
    private static final int BLINDSPOT_REFRESH_INTERVAL_MS = 33; // 30fps 刷新
    // 预分配可复用 Bitmap（避免每帧 GC 压力）
    private android.graphics.Bitmap reusableCroppedBitmap; // 1280x800 象限裁切
    private android.graphics.Bitmap lastDisplayBitmap; // ImageView 上一帧（回收用）
    /** 调整大小时显示的快照，ACTION_UP 时回收 */
    private android.graphics.Bitmap resizeSnapshotBitmap;
    private android.graphics.Canvas reusableCroppedCanvas;
    private final android.graphics.Rect srcRect = new android.graphics.Rect();
    private final android.graphics.Rect dstRect = new android.graphics.Rect();
    private WindowManager.LayoutParams layoutParams;
    private WindowManager activeWindowManager;

    // 编辑模式广播控制
    public static final String ACTION_TOGGLE_EDIT_MODE = "com.elliot.blindspot.TOGGLE_EDIT_MODE";
    private BroadcastReceiver editModeReceiver;

    // 画面共享（供 HUD/仪表盘 Activity 使用）
    public static final String ACTION_FRAME_UPDATE = "com.elliot.blindspot.FRAME_UPDATE";

    // 转向补盲用的裁剪帧（HUD 显示）
    private static volatile android.graphics.Bitmap sharedFrame = null;
    private static volatile android.graphics.Bitmap previousFrame = null;
    private static volatile String sharedDirection = TurningSignalMonitor.DIRECTION_LEFT;

    // 校准/布局编辑等使用的全景帧
    private static volatile android.graphics.Bitmap sharedFullFrame = null;
    // previousFullFrame 已移除（死代码，从未被赋值）

    private Runnable frameShareRunnable;
    private static final int FRAME_SHARE_INTERVAL_MS = 33; // 30fps，平衡流畅度和性能

    // 共享帧源分辨率（平衡画质和性能）
    private static final int FRAME_SHARE_SOURCE_WIDTH = 1600;
    private static final int FRAME_SHARE_SOURCE_HEIGHT = 1000;

    // 共享帧尺寸（兼顾 HUD 和仪表盘清晰度）
    private static final int SHARED_FRAME_WIDTH = 1280;
    private static final int SHARED_FRAME_HEIGHT = 800;

    // 常驻全景帧抓取（即使不转向也持续更新 sharedFullFrame）
    private Runnable fullFrameShareRunnable;
    private static final int FULL_FRAME_INTERVAL_MS = 33; // 30fps 提高流畅度
    private android.os.HandlerThread frameShareThread;
    private android.os.Handler frameShareHandler;
    // 预分配 Bitmap 复用（避免每帧 ~16MB 内存分配导致 GC 压力）
    private android.graphics.Bitmap reusableFullFrameBitmap;

    // 禁用 ImageReader - 使用 getBitmap 提供更好的兼容性和稳定性
    private android.media.ImageReader frameImageReader;
    private boolean useImageReader = false;

    // 预览帧共享配置（低分辨率，高帧率）
    private static final int PREVIEW_WIDTH = 1280; // 预览用低分辨率
    private static final int PREVIEW_HEIGHT = 800;
    private static final int PREVIEW_FPS = 20; // 20fps 平衡流畅度和性能
    private static final int PREVIEW_INTERVAL_MS = 1000 / PREVIEW_FPS; // 50ms

    public static android.graphics.Bitmap getSharedFrame() {
        return sharedFrame;
    }

    public static String getSharedDirection() {
        return sharedDirection;
    }

    /**
     * 获取全景帧（供校准/布局编辑预览使用）
     */
    public static android.graphics.Bitmap getSharedFullFrame() {
        return sharedFullFrame;
    }

    // ==================== 跨进程画面共享（通过文件）====================

    // 共享画面文件路径（使用应用外部存储目录，避免权限问题）
    private String sharedFramePath = null;
    private static final int SHARED_FRAME_QUALITY = 85; // JPEG 质量
    private static long lastFrameSaveTime = 0;
    private static final long FRAME_SAVE_INTERVAL_MS = 50; // 每50ms保存一帧（20fps）

    private String getSharedFramePath() {
        if (sharedFramePath == null) {
            // 使用应用的外部缓存目录（不需要特殊权限）
            java.io.File cacheDir = getExternalCacheDir();
            if (cacheDir == null) {
                cacheDir = getCacheDir(); // 回退到内部缓存
            }
            sharedFramePath = new java.io.File(cacheDir, "shared_frame.jpg").getAbsolutePath();
            android.util.Log.d("TURNING_DEBUG", "共享画面路径: " + sharedFramePath);
        }
        return sharedFramePath;
    }

    /**
     * 获取共享画面文件路径（供外部应用查询）
     */
    public static String getSharedFrameFilePath(android.content.Context context) {
        java.io.File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null) {
            cacheDir = context.getCacheDir();
        }
        // 补盲应用的路径
        return "/storage/emulated/0/Android/data/com.elliot.blindspot/cache/shared_frame.jpg";
    }

    private static TurningBlindSpotService instance;

    // 触摸拖拽相关
    private float touchStartX, touchStartY;
    private int windowStartX, windowStartY;
    private int currentWindowX, currentWindowY; // 保存当前窗口位置（layoutParams 会被系统重置）
    private boolean isAbsolutePositionMode = false; // 是否已切换到绝对定位模式
    private int startWidth, startHeight;
    private boolean isDragging = false;
    private boolean isResizing = false;
    private boolean isEditMode = false; // 编辑模式标志（调整大小模式）
    private View resizeHandle;
    private View editModeIndicator; // 编辑模式指示器
    private View directionIndicatorView; // 方向提示条（需保持在补盲 ImageView 之上）
    private int currentWidth, currentHeight;
    private SharedPreferences prefs;

    // 长按拖拽相关（长按 2 秒进入移动位置模式）
    private boolean isLongPressing = false;
    private long touchDownTime = 0;
    private static final int LONG_PRESS_TIMEOUT = 3000; // 长按 3 秒进入移动位置模式
    private Handler longPressHandler;
    private Runnable longPressRunnable;

    // 点击计数相关（5次点击进入调整大小模式）
    private int tapCount = 0;
    private long lastTapTime = 0;
    private static final int TAP_TIMEOUT = 500; // 点击超时时间（毫秒）
    private static final int TAP_COUNT_FOR_RESIZE = 5; // 需要点击5次进入调整大小模式
    /** 悬浮窗正常显示时的触摸标志（退出编辑模式时恢复，避免锁死无法拖动） */
    private static final int NORMAL_WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    private Handler editModeHandler;
    private Runnable exitEditModeRunnable;

    // 相机相关
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String panoramicCameraId;

    // 状态
    private boolean isTurningMode = false;
    private String currentDirection = TurningSignalMonitor.DIRECTION_NONE;
    private long showTime = 0;
    private Handler mainHandler;
    private Runnable pendingExitRunnable;

    // Overlay 保活看门狗（防止地图等应用覆盖补盲窗口）
    private Runnable overlayWatchdogRunnable;
    private static final long OVERLAY_WATCHDOG_INTERVAL_MS = 500;

    // 唤醒锁
    private PowerManager.WakeLock wakeLock;

    // 转向信号监听器
    private TurningSignalMonitor turningSignalMonitor;

    // 外部信号广播接收器（用于模拟器）
    private BroadcastReceiver externalSignalReceiver;

    /** GPU 渲染管道模式：GL Shader 裁切 + readPixels 帧抓取 */
    private boolean useGLPipeline = true;
    private CameraGLRenderer glPipelineRenderer; // GL 管道渲染器实例
    private android.graphics.Bitmap glFrameShareBitmap; // GL 帧共享复用 Bitmap（全景帧副本）
    private android.graphics.Canvas glFrameShareCanvas;

    // ADB Shell 执行器（用于权限授予/调试命令等）
    private AdbShellExecutor adbExecutor;

    private VideoRecordController videoRecordController;
    private android.graphics.Bitmap photoReusableBitmap;
    private android.graphics.Canvas photoReusableCanvas;
    private final android.graphics.Rect photoSrcRect = new android.graphics.Rect();
    private final android.graphics.Rect photoDstRect = new android.graphics.Rect();

    private FrameLayout photoOverlayContainer;
    private View photoOverlayHighlight;
    private TextView photoOverlayLabel;
    private WindowManager.LayoutParams photoOverlayParams;
    private ValueAnimator photoOverlayAnimator;
    private float photoOverlayDownRawX;
    private float photoOverlayDownRawY;
    private int photoOverlayDownX;
    private int photoOverlayDownY;
    private boolean photoOverlayMoved;

    // 相机预热帧跳过（重新打开后前几帧可能是残留/错误画面）
    private volatile int warmupFramesRemaining = 0;
    private static final int WARMUP_FRAMES_TO_SKIP = 5;

    // 资源监控（长时间后台运行优化）
    private ActivityManager activityManager;
    private Handler resourceMonitorHandler;
    private Runnable resourceMonitorRunnable;
    private static final long RESOURCE_CHECK_INTERVAL_MS = 30000; // 30秒检查一次
    private static final float LOW_MEMORY_THRESHOLD = 0.2f; // 可用内存低于20%时触发
    private int consecutiveLowMemoryCount = 0;

    public static boolean isRunning() {
        return isServiceRunning;
    }

    /**
     * 服务的 ADB 连接状态（供 MainActivity 显示用）
     */
    private static volatile boolean sAdbConnected = false;

    public static boolean isAdbConnected() {
        return sAdbConnected;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "Service onCreate");
        android.util.Log.e("TURNING_DEBUG", "========== TurningBlindSpotService onCreate ==========");

        isServiceRunning = true;
        instance = this; // 保存实例引用，供静态方法调用
        mainHandler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 读取设置
        useGLPipeline = true;
        AppLog.d(TAG, "Settings: GL_Pipeline=" + useGLPipeline);
        android.util.Log.e("TURNING_DEBUG", "Settings: GL_Pipeline=" + useGLPipeline);

        // 尝试从 BlindspotApp 接管已预热的摄像头资源
        // GL 管道模式下不收养（需要完整的 GL SurfaceTexture 初始化流程，收养后重配不可靠）
        boolean cameraAdopted = useGLPipeline ? false : adoptCameraFromApp();

        if (!cameraAdopted) {
            // 未能接管，创建自己的相机线程
            cameraThread = new HandlerThread("CameraThread");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }

        // 获取唤醒锁
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurningBlindSpot::WakeLock");

        // 创建通知渠道
        createNotificationChannel();

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());

        if (!cameraAdopted) {
            // 未能接管，自行查找相机
            findPanoramicCamera();
        }

        // 初始化转向信号监听器（立即执行，核心功能）
        turningSignalMonitor = TurningSignalMonitor.getInstance(this);
        turningSignalMonitor.setCallback(this);
        turningSignalMonitor.startMonitoring();

        // 注册外部信号广播接收器（立即执行）
        registerExternalSignalReceiver();

        // 注册编辑模式广播接收器（立即执行）
        registerEditModeReceiver();

        // 初始化 ADB 执行器（轻量，立即执行）
        adbExecutor = new AdbShellExecutor();

        if (cameraAdopted) {
            // 收养模式：直接使用 TextureView + getBitmap 帧共享
            // （收养的摄像头已有 capture session，重建为 GL 管道不稳定）
            android.util.Log.e("TURNING_DEBUG", "Camera adopted from BlindspotApp, starting frame sharing immediately");
            useDirectTextureView = true;
            setupAdoptedTextureViewListener();
            startPreviewFrameSharing();
        } else {
        // 未接管，创建常驻相机预览（即使不转向也持续运行，保证补盲随时可用）
            createPersistentCameraPreview();
        }

        // 初始化资源监控（长时间后台运行优化）
        initResourceMonitor();

        videoRecordController = new VideoRecordController(this, prefs, this::requestRecordFrame);
        videoRecordController.setStateListener(this::updatePhotoOverlayActive);
        videoRecordController.start();
        updatePhotoOverlayFromPrefs();

        AppLog.d(TAG, "Service started with persistent camera preview");
    }

    /**
     * 初始化资源监控（CPU/内存调度优化）
     */
    private void initResourceMonitor() {
        activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        resourceMonitorHandler = new Handler(Looper.getMainLooper());

        resourceMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                checkResourceUsage();
                resourceMonitorHandler.postDelayed(this, RESOURCE_CHECK_INTERVAL_MS);
            }
        };

        // 启动资源监控
        resourceMonitorHandler.postDelayed(resourceMonitorRunnable, RESOURCE_CHECK_INTERVAL_MS);
        android.util.Log.e("TURNING_DEBUG", "Resource monitor started (interval=" +
                (RESOURCE_CHECK_INTERVAL_MS / 1000) + "s)");
    }

    /**
     * 检查资源使用情况
     */
    private void checkResourceUsage() {
        try {
            // 获取内存信息
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memInfo);

            long availMem = memInfo.availMem;
            long totalMem = memInfo.totalMem;
            float memoryUsageRatio = 1.0f - ((float) availMem / totalMem);
            boolean isLowMemory = memInfo.lowMemory;

            // 获取进程内存
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            float heapUsageRatio = (float) usedMemory / maxMemory;

            // 日志输出（每分钟一次详细信息）
            android.util.Log.d("RESOURCE_MONITOR", String.format(
                    "Memory: system=%.1f%% (avail=%dMB), heap=%.1f%% (used=%dMB/%dMB), lowMem=%b",
                    memoryUsageRatio * 100,
                    availMem / (1024 * 1024),
                    heapUsageRatio * 100,
                    usedMemory / (1024 * 1024),
                    maxMemory / (1024 * 1024),
                    isLowMemory));

            // 低内存处理
            if (isLowMemory || heapUsageRatio > 0.85f) {
                consecutiveLowMemoryCount++;
                android.util.Log.w("RESOURCE_MONITOR", "Low memory detected! count=" + consecutiveLowMemoryCount);

                if (consecutiveLowMemoryCount >= 2) {
                    // 连续两次低内存，触发内存释放
                    performMemoryCleanup();
                    consecutiveLowMemoryCount = 0;
                }
            } else {
                consecutiveLowMemoryCount = 0;
            }

        } catch (Exception e) {
            android.util.Log.e("RESOURCE_MONITOR", "Error checking resources: " + e.getMessage());
        }
    }

    /**
     * 执行内存清理
     */
    private void performMemoryCleanup() {
        // 释放预分配的 Bitmap 缓存以回收内存
        android.util.Log.w("RESOURCE_MONITOR", "Performing memory cleanup...");
        if (reusableCroppedBitmap != null && !reusableCroppedBitmap.isRecycled()) {
            reusableCroppedBitmap.recycle();
            reusableCroppedBitmap = null;
            reusableCroppedCanvas = null;
        }
        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMB = runtime.maxMemory() / (1024 * 1024);
        android.util.Log.w("RESOURCE_MONITOR", "Cleanup done: heap=" + usedMB + "MB/" + maxMB + "MB");
    }

    /**
     * 停止资源监控
     */
    private void stopResourceMonitor() {
        if (resourceMonitorHandler != null && resourceMonitorRunnable != null) {
            resourceMonitorHandler.removeCallbacks(resourceMonitorRunnable);
            resourceMonitorRunnable = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_RECORD_START.equals(action) || ACTION_PHOTO_START_CONTINUOUS.equals(action)) {
                if (videoRecordController != null) videoRecordController.setRecordEnabled(true);
            } else if (ACTION_RECORD_STOP.equals(action) || ACTION_PHOTO_STOP_CONTINUOUS.equals(action)) {
                if (videoRecordController != null) videoRecordController.setRecordEnabled(false);
            } else if (ACTION_RECORD_TAKE_PHOTO.equals(action) || ACTION_PHOTO_TAKE_ONCE.equals(action)) {
                takePhotoAsync();
            }
        }
        return START_STICKY;
    }

    private void takePhotoAsync() {
        try {
            int maxW = Math.max(320, prefs.getInt(VideoRecordController.PREF_RECORD_MAX_WIDTH,
                    prefs.getInt(PhotoCaptureController.PREF_PHOTO_MAX_WIDTH, 2560)));
            int quality = clampInt(prefs.getInt(VideoRecordController.PREF_RECORD_JPEG_QUALITY,
                    prefs.getInt(PhotoCaptureController.PREF_PHOTO_QUALITY, 85)), 0, 100);
            requestRecordFrame(maxW, new VideoRecordController.FrameCallback() {
                @Override
                public void onFrame(Bitmap bitmap) {
                    if (bitmap == null || bitmap.isRecycled()) return;
                    new Thread(() -> {
                        try {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                                    java.util.Locale.US);
                            String base = sdf.format(new java.util.Date());
                            java.io.File f = new VideoStorage(getApplicationContext(), prefs).createNewPhotoFile(base);
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
                            }
                            AppLog.d(TAG, "photo saved: " + f.getAbsolutePath());
                        } catch (Exception e) {
                            AppLog.w(TAG, "photo save error: " + e.getMessage());
                        }
                    }, "TakePhotoThread").start();
                }

                @Override
                public void onError(String reason) {
                    AppLog.w(TAG, "photo capture error: " + reason);
                }
            });
        } catch (Exception e) {
            AppLog.w(TAG, "take photo error: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // 注册的外部 Surface 列表（保留用于未来扩展）
    private final java.util.List<android.view.Surface> registeredSurfaces = new java.util.ArrayList<>();

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "Service onDestroy");

        isServiceRunning = false;
        instance = null; // 清除实例引用

        // 停止资源监控
        stopResourceMonitor();

        if (videoRecordController != null) {
            videoRecordController.stop();
            videoRecordController = null;
        }
        removePhotoOverlay();
        if (photoReusableBitmap != null && !photoReusableBitmap.isRecycled()) {
            photoReusableBitmap.recycle();
            photoReusableBitmap = null;
            photoReusableCanvas = null;
        }

        // 注销外部信号广播接收器
        if (externalSignalReceiver != null) {
            try {
                unregisterReceiver(externalSignalReceiver);
            } catch (Exception ignored) {
            }
        }
        // 注销编辑模式广播接收器
        if (editModeReceiver != null) {
            try {
                unregisterReceiver(editModeReceiver);
            } catch (Exception ignored) {
            }
        }

        // 停止转向信号监听
        if (turningSignalMonitor != null) {
            turningSignalMonitor.stopMonitoring();
        }

        // 关闭相机
        closeCamera();

        // 移除预览窗口
        removePreviewWindow();

        // 停止相机线程
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }

        // 释放唤醒锁
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // 停止画面共享
        stopPreviewFrameSharing();

        // 关闭 ADB 执行器
        if (adbExecutor != null) {
            adbExecutor.shutdown();
            adbExecutor = null;
        }
    }

    private void requestRecordFrame(int maxWidth, VideoRecordController.FrameCallback cb) {
        String src = prefs.getString(VideoRecordController.PREF_RECORD_CAPTURE_SOURCE, null);
        if (src == null || src.length() == 0) {
            src = prefs.getString(PhotoCaptureController.PREF_PHOTO_CAPTURE_SOURCE, VideoRecordController.CAPTURE_SOURCE_GL);
        }
        boolean preferGl = VideoRecordController.CAPTURE_SOURCE_GL.equals(src);
        if (preferGl && useGLPipeline && glPipelineRenderer != null && glPipelineRenderer.isRunning()) {
            float[] crop = new float[] { 0f, 0f, 1f, 1f };
            glPipelineRenderer.requestStillCapture(crop, maxWidth, new CameraGLRenderer.StillCaptureCallback() {
                @Override
                public void onCaptured(android.graphics.Bitmap frame) {
                    cb.onFrame(frame);
                }

                @Override
                public void onError(String error) {
                    requestRecordFrameCpu(maxWidth, cb);
                }
            });
            return;
        }
        requestRecordFrameCpu(maxWidth, cb);
    }

    private void requestRecordFrameCpu(int maxWidth, VideoRecordController.FrameCallback cb) {
        try {
            android.graphics.Bitmap src = sharedFullFrame;
            if (src == null || src.isRecycled()) {
                if (previewTextureView != null && previewTextureView.isAvailable()) {
                    int sw = previewTextureView.getWidth();
                    int sh = previewTextureView.getHeight();
                    if (sw > 0 && sh > 0) {
                        android.graphics.Bitmap tmp = android.graphics.Bitmap.createBitmap(sw, sh,
                                android.graphics.Bitmap.Config.ARGB_8888);
                        src = previewTextureView.getBitmap(tmp);
                    }
                }
            }
            if (src == null || src.isRecycled()) {
                cb.onError("no frame");
                return;
            }

            int srcW = src.getWidth();
            int srcH = src.getHeight();
            int outW = Math.min(Math.max(320, maxWidth), srcW);
            int outH = Math.round((outW * 1f * srcH) / srcW);
            if ((outH & 1) == 1) outH++;
            if ((outW & 1) == 1) outW++;

            android.graphics.Bitmap out;
            synchronized (photoSrcRect) {
                if (photoReusableBitmap == null || photoReusableBitmap.isRecycled()
                        || photoReusableBitmap.getWidth() != outW || photoReusableBitmap.getHeight() != outH) {
                    if (photoReusableBitmap != null && !photoReusableBitmap.isRecycled()) photoReusableBitmap.recycle();
                    photoReusableBitmap = android.graphics.Bitmap.createBitmap(outW, outH,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    photoReusableCanvas = new android.graphics.Canvas(photoReusableBitmap);
                }
                photoSrcRect.set(0, 0, srcW, srcH);
                photoDstRect.set(0, 0, outW, outH);
                photoReusableCanvas.drawColor(android.graphics.Color.BLACK);
                photoReusableCanvas.drawBitmap(src, photoSrcRect, photoDstRect, null);
                out = photoReusableBitmap;
            }
            cb.onFrame(out);
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    private void updatePhotoOverlayFromPrefs() {
        try {
            boolean enabled = prefs.contains(VideoRecordController.PREF_RECORD_OVERLAY_ENABLED)
                    ? prefs.getBoolean(VideoRecordController.PREF_RECORD_OVERLAY_ENABLED, false)
                    : prefs.getBoolean(PhotoCaptureController.PREF_PHOTO_OVERLAY_ENABLED, false);
            if (!enabled) {
                removePhotoOverlay();
                return;
            }

            if (photoOverlayContainer == null) {
                createPhotoOverlay();
            }
            if (photoOverlayContainer == null) return;

            float density = getResources().getDisplayMetrics().density;
            int sizeDp = clampInt(prefs.contains(VideoRecordController.PREF_RECORD_OVERLAY_SIZE_DP)
                    ? prefs.getInt(VideoRecordController.PREF_RECORD_OVERLAY_SIZE_DP, 96)
                    : prefs.getInt(PhotoCaptureController.PREF_PHOTO_OVERLAY_SIZE_DP, 96), 32, 240);
            int sizePx = (int) (sizeDp * density);
            int alphaPercent = clampInt(prefs.contains(VideoRecordController.PREF_RECORD_OVERLAY_ALPHA_PERCENT)
                    ? prefs.getInt(VideoRecordController.PREF_RECORD_OVERLAY_ALPHA_PERCENT, 70)
                    : prefs.getInt(PhotoCaptureController.PREF_PHOTO_OVERLAY_ALPHA_PERCENT, 70), 5, 100);

            if (photoOverlayParams == null) {
                int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                photoOverlayParams = new WindowManager.LayoutParams(
                        sizePx,
                        sizePx,
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT);
                photoOverlayParams.gravity = Gravity.TOP | Gravity.END;
                int savedX = prefs.contains(VideoRecordController.PREF_RECORD_OVERLAY_X)
                        ? prefs.getInt(VideoRecordController.PREF_RECORD_OVERLAY_X, Integer.MIN_VALUE)
                        : prefs.getInt(PhotoCaptureController.PREF_PHOTO_OVERLAY_X, Integer.MIN_VALUE);
                int savedY = prefs.contains(VideoRecordController.PREF_RECORD_OVERLAY_Y)
                        ? prefs.getInt(VideoRecordController.PREF_RECORD_OVERLAY_Y, Integer.MIN_VALUE)
                        : prefs.getInt(PhotoCaptureController.PREF_PHOTO_OVERLAY_Y, Integer.MIN_VALUE);
                photoOverlayParams.x = savedX != Integer.MIN_VALUE ? savedX : (int) (24 * density);
                photoOverlayParams.y = savedY != Integer.MIN_VALUE ? savedY : (int) (160 * density);
            }
            photoOverlayParams.width = sizePx;
            photoOverlayParams.height = sizePx;
            photoOverlayParams.alpha = alphaPercent / 100f;

            if (photoOverlayContainer.getWindowToken() == null) {
                try {
                    getOverlayWindowManager().addView(photoOverlayContainer, photoOverlayParams);
                } catch (Exception ignored) {
                }
            } else {
                try {
                    getOverlayWindowManager().updateViewLayout(photoOverlayContainer, photoOverlayParams);
                } catch (Exception ignored) {
                }
            }
            updatePhotoOverlayActive(videoRecordController != null && videoRecordController.isRecordEnabled());
        } catch (Exception ignored) {
        }
    }

    private void updatePhotoOverlayActive(boolean active) {
        if (photoOverlayContainer == null) return;
        if (photoOverlayLabel != null) {
            photoOverlayLabel.setText(active ? "录制\n运行中" : "录制\n已停止");
        }
        if (active) {
            startPhotoOverlayPulse();
        } else {
            stopPhotoOverlayPulse();
        }
    }

    private void createPhotoOverlay() {
        try {
            float density = getResources().getDisplayMetrics().density;
            FrameLayout root = new FrameLayout(this);
            int pad = (int) (10 * density);
            root.setPadding(pad, pad, pad, pad);

            GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] { 0x661C2B33, 0x66303E46 });
            base.setCornerRadius(18 * density);
            root.setBackground(base);

            View highlight = new View(this);
            GradientDrawable hi = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] { 0x553F8BFF, 0x5534C0A0 });
            hi.setCornerRadius(18 * density);
            highlight.setBackground(hi);
            highlight.setAlpha(0f);
            root.addView(highlight, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            TextView label = new TextView(this);
            label.setText("录制\n已停止");
            label.setTextColor(Color.WHITE);
            label.setTextSize(16);
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            root.addView(label, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            root.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                } catch (Exception ignored) {
                }
            });

            float moveSlop = 6 * density;
            root.setOnTouchListener((v, event) -> {
                if (photoOverlayParams == null || photoOverlayContainer == null) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    photoOverlayDownRawX = event.getRawX();
                    photoOverlayDownRawY = event.getRawY();
                    photoOverlayDownX = photoOverlayParams.x;
                    photoOverlayDownY = photoOverlayParams.y;
                    photoOverlayMoved = false;
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - photoOverlayDownRawX;
                    float dy = event.getRawY() - photoOverlayDownRawY;
                    if (!photoOverlayMoved && (Math.abs(dx) > moveSlop || Math.abs(dy) > moveSlop)) {
                        photoOverlayMoved = true;
                    }
                    if (photoOverlayMoved) {
                        photoOverlayParams.x = photoOverlayDownX + Math.round(-dx);
                        photoOverlayParams.y = photoOverlayDownY + Math.round(dy);
                        try {
                            getOverlayWindowManager().updateViewLayout(photoOverlayContainer, photoOverlayParams);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (photoOverlayMoved) {
                        prefs.edit()
                                .putInt(VideoRecordController.PREF_RECORD_OVERLAY_X, photoOverlayParams.x)
                                .putInt(VideoRecordController.PREF_RECORD_OVERLAY_Y, photoOverlayParams.y)
                                .apply();
                        return true;
                    }
                    v.performClick();
                    return true;
                }
                return false;
            });

            photoOverlayContainer = root;
            photoOverlayHighlight = highlight;
            photoOverlayLabel = label;
        } catch (Exception ignored) {
            photoOverlayContainer = null;
            photoOverlayHighlight = null;
            photoOverlayLabel = null;
        }
    }

    private void startPhotoOverlayPulse() {
        if (photoOverlayHighlight == null) return;
        if (photoOverlayAnimator != null && photoOverlayAnimator.isRunning()) return;
        photoOverlayAnimator = ValueAnimator.ofFloat(0f, 1f);
        photoOverlayAnimator.setDuration(900);
        photoOverlayAnimator.setRepeatCount(ValueAnimator.INFINITE);
        photoOverlayAnimator.setRepeatMode(ValueAnimator.REVERSE);
        photoOverlayAnimator.setInterpolator(new LinearInterpolator());
        photoOverlayAnimator.addUpdateListener(a -> {
            if (photoOverlayHighlight == null) return;
            float v = (float) a.getAnimatedValue();
            photoOverlayHighlight.setAlpha(0.75f * v);
        });
        photoOverlayAnimator.start();
    }

    private void stopPhotoOverlayPulse() {
        if (photoOverlayAnimator != null) {
            try {
                photoOverlayAnimator.cancel();
            } catch (Exception ignored) {
            }
            photoOverlayAnimator = null;
        }
        if (photoOverlayHighlight != null) {
            photoOverlayHighlight.setAlpha(0f);
        }
    }

    private void removePhotoOverlay() {
        stopPhotoOverlayPulse();
        if (photoOverlayContainer != null) {
            try {
                getOverlayWindowManager().removeView(photoOverlayContainer);
            } catch (Exception ignored) {
            }
            photoOverlayContainer = null;
            photoOverlayHighlight = null;
            photoOverlayLabel = null;
            photoOverlayParams = null;
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private float[] getEffectiveCropRectNormalized(CameraLayoutConfig.Direction direction) {
        if (direction == CameraLayoutConfig.Direction.LEFT) {
            return getEffectiveTurningCropRectNormalized(TurningSignalMonitor.DIRECTION_LEFT);
        }
        if (direction == CameraLayoutConfig.Direction.RIGHT) {
            return getEffectiveTurningCropRectNormalized(TurningSignalMonitor.DIRECTION_RIGHT);
        }
        return CameraLayoutConfig.getCropRectNormalized(prefs, direction);
    }

    /**
     * 获取当前激活的WindowManager（HUD或主屏）
     */
    private WindowManager getActiveWindowManager() {
        return windowManager;
    }

    /** 悬浮窗始终在主屏，位置/尺寸更新统一用主屏 WM，避免开启仪表盘时坐标乱跑 */
    private WindowManager getOverlayWindowManager() {
        return windowManager;
    }

    /**
     * 注册外部信号广播接收器（用于模拟器测试）
     */
    // 模拟器广播常量（兼容 TurnSignalSimulator）
    private static final String ACTION_SIMULATOR_SIGNAL = "com.kooo.turningblindspot.TURNING_SIGNAL_CHANGED";

    private void registerExternalSignalReceiver() {
        externalSignalReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                android.util.Log.e("TURNING_DEBUG", "Broadcast received: " + intent.getAction());

                // 处理转向信号广播（支持本应用广播和模拟器广播）
                String action = intent.getAction();
                if (TurningSignalMonitor.ACTION_TURNING_SIGNAL_CHANGED.equals(action) ||
                        ACTION_SIMULATOR_SIGNAL.equals(action)) {
                    String direction = intent.getStringExtra(TurningSignalMonitor.EXTRA_TURNING_DIRECTION);
                    boolean isOn = intent.getBooleanExtra(TurningSignalMonitor.EXTRA_IS_TURNING_ON, false);

                    // 兼容模拟器的 "off" 方向值
                    if ("off".equals(direction)) {
                        direction = TurningSignalMonitor.DIRECTION_NONE;
                        isOn = false;
                    }

                    AppLog.d(TAG, "Received external signal: direction=" + direction + ", isOn=" + isOn);
                    android.util.Log.e("TURNING_DEBUG", "Received signal: direction=" + direction + ", isOn=" + isOn
                            + ", current=" + currentDirection + ", from=" + action);

                    if (isOn) {
                        onTurningStart(direction);
                    } else {
                        // 只有当关闭信号的方向与当前显示方向匹配时才关闭
                        // 或者方向为空/none时关闭（全局关闭信号）
                        if (direction == null ||
                                TurningSignalMonitor.DIRECTION_NONE.equals(direction) ||
                                direction.equals(currentDirection)) {
                            onTurningEnd();
                        } else {
                            android.util.Log.e("TURNING_DEBUG",
                                    "Ignoring close signal for " + direction + ", current is " + currentDirection);
                        }
                    }
                } else if (ACTION_SETTINGS_CHANGED.equals(intent.getAction())) {
                    // 画质/校准/悬浮窗设置变更
                    android.util.Log.e("TURNING_DEBUG", "Settings changed, reloading");
                    mainHandler.post(() -> {
                        applyImageQualitySettings();
                        cachedCalibDirection = null;
                        if (isTurningMode && currentDirection != null
                                && !TurningSignalMonitor.DIRECTION_NONE.equals(currentDirection)) {
                            applyTransform(currentDirection);
                        }
                        // 若悬浮窗正在显示，立即应用新尺寸/位置
                        applyFloatingWindowSettingsIfVisible();
                        updatePhotoOverlayFromPrefs();
                    });
                }
            }
        };

        IntentFilter filter = new IntentFilter(TurningSignalMonitor.ACTION_TURNING_SIGNAL_CHANGED);
        filter.addAction(ACTION_SIMULATOR_SIGNAL); // 兼容模拟器广播
        filter.addAction(ACTION_SETTINGS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(externalSignalReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(externalSignalReceiver, filter);
        }
        AppLog.d(TAG, "External signal receiver registered");
    }

    /**
     * 应用画质设置（软件方式）
     * 由于相机硬件不支持曝光补偿，使用软件方式调整
     */
    private void applyImageQualitySettings() {
        if (previewTextureView == null || previewContainer == null) {
            return;
        }

        int brightness = prefs.getInt(PREF_BRIGHTNESS, 0);
        int contrast = prefs.getInt(PREF_CONTRAST, 0);

        android.util.Log.e("TURNING_DEBUG", "Applying image quality (software): brightness=" + brightness +
                ", contrast=" + contrast);

        // 使用 ColorMatrix 进行亮度和对比度调整
        android.graphics.ColorMatrix colorMatrix = new android.graphics.ColorMatrix();

        // 亮度调整：将 -100~100 映射到 -255~255 的偏移
        float brightnessValue = brightness * 2.55f;

        // 对比度调整：将 -100~100 映射到 0.5~2.0 的缩放
        float contrastValue = (contrast + 100) / 100f; // 0 -> 1.0, -100 -> 0, +100 -> 2.0
        contrastValue = Math.max(0.5f, Math.min(2.0f, contrastValue));

        // 构建亮度矩阵
        float[] brightnessMatrix = {
                1, 0, 0, 0, brightnessValue,
                0, 1, 0, 0, brightnessValue,
                0, 0, 1, 0, brightnessValue,
                0, 0, 0, 1, 0
        };

        // 构建对比度矩阵
        float translate = (1 - contrastValue) * 127.5f;
        float[] contrastMatrix = {
                contrastValue, 0, 0, 0, translate,
                0, contrastValue, 0, 0, translate,
                0, 0, contrastValue, 0, translate,
                0, 0, 0, 1, 0
        };

        android.graphics.ColorMatrix brightnessM = new android.graphics.ColorMatrix(brightnessMatrix);
        android.graphics.ColorMatrix contrastM = new android.graphics.ColorMatrix(contrastMatrix);

        colorMatrix.postConcat(brightnessM);
        colorMatrix.postConcat(contrastM);

        // 应用到 TextureView 的 Paint
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(colorMatrix));
        previewTextureView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint);

        android.util.Log.e("TURNING_DEBUG",
                "Image quality applied: brightness=" + brightnessValue + ", contrast=" + contrastValue);
    }

    /**
     * 若悬浮窗正在显示，从设置中应用新尺寸/位置（设置变更时立即生效）
     */
    private void applyFloatingWindowSettingsIfVisible() {
        if (previewContainer == null || layoutParams == null)
            return;
        boolean hidden = layoutParams.width <= 1 || layoutParams.height <= 1 || layoutParams.alpha < 0.1f;
        if (hidden)
            return;

        int newW = prefs.getInt(PREF_WINDOW_WIDTH, DEFAULT_PREVIEW_WIDTH);
        int newH = prefs.getInt(PREF_WINDOW_HEIGHT, DEFAULT_PREVIEW_HEIGHT);
        newW = Math.max(MIN_PREVIEW_SIZE, Math.min(newW, MAX_PREVIEW_WIDTH));
        newH = Math.max(MIN_PREVIEW_SIZE, Math.min(newH, MAX_PREVIEW_HEIGHT));

        int newX = prefs.getInt(PREF_WINDOW_X, Integer.MIN_VALUE);
        int newY = prefs.getInt(PREF_WINDOW_Y, Integer.MIN_VALUE);
        if (newX != Integer.MIN_VALUE && newY != Integer.MIN_VALUE) {
            layoutParams.x = newX;
            layoutParams.y = newY;
            currentWindowX = newX;
            currentWindowY = newY;
        } else {
            float density = getResources().getDisplayMetrics().density;
            layoutParams.gravity = Gravity.TOP | Gravity.END;
            layoutParams.x = (int) (20 * density);
            layoutParams.y = (int) (20 * density);
            currentWindowX = -1;
            currentWindowY = -1;
            isAbsolutePositionMode = false;
        }

        if (newW != layoutParams.width || newH != layoutParams.height) {
            layoutParams.width = newW;
            layoutParams.height = newH;
            currentWidth = newW;
            currentHeight = newH;
            try {
                getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
                if (currentDirection != null && !TurningSignalMonitor.DIRECTION_NONE.equals(currentDirection)) {
                    applyTransform(currentDirection);
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to apply floating window settings", e);
            }
        } else {
            try {
                getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to update window layout", e);
            }
        }
    }

    /**
     * 注册编辑模式广播接收器
     */
    private void registerEditModeReceiver() {
        editModeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_TOGGLE_EDIT_MODE.equals(intent.getAction())) {
                    AppLog.d(TAG, "Received edit mode toggle broadcast");
                    mainHandler.post(() -> toggleEditMode());
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_TOGGLE_EDIT_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(editModeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(editModeReceiver, filter);
        }
        AppLog.d(TAG, "Edit mode receiver registered");
    }

    /**
     * 切换编辑模式（通过广播调用）
     */
    public void toggleEditMode() {
        if (previewContainer == null) {
            AppLog.w(TAG, "Cannot toggle edit mode: preview window not shown");
            return;
        }

        isEditMode = !isEditMode;

        if (isEditMode) {
            enterEditMode();
        } else {
            exitEditMode();
        }
    }

    // ==================== TurningSignalCallback 实现 ====================

    @Override
    public void onTurningStart(String direction) {
        AppLog.d(TAG, "Turning start: " + direction);
        android.util.Log.e("TURNING_DEBUG", "========== onTurningStart: " + direction + " ==========");
        // 已在主线程（TurningSignalMonitor 通过 mainHandler.post 回调），直接执行避免二次 post 延迟
        enterTurningMode(direction);
    }

    @Override
    public void onTurningEnd() {
        AppLog.d(TAG, "Turning end");
        // 已在主线程，直接执行
        exitTurningMode();
    }

    // ==================== 转向模式控制 ====================

    private void enterTurningMode(String direction) {
        // 相同方向且已在转向模式中
        if (isTurningMode && direction.equals(currentDirection)) {
            if (pendingExitRunnable != null) {
                // 【修复】延迟退出期间收到同方向信号 = 用户继续转向，取消退出
                // 之前忽略此信号会导致窗口打开几秒后自动关闭
                mainHandler.removeCallbacks(pendingExitRunnable);
                pendingExitRunnable = null;
                android.util.Log.e("TURNING_DEBUG",
                        "enterTurningMode: same direction during pending exit, CANCELLED exit (user still turning)");
            }
            showTime = System.currentTimeMillis();
            AppLog.d(TAG, "Same direction, refresh time: " + direction);
            return;
        }

        // 不同方向或首次进入：取消待执行的退出操作
        if (pendingExitRunnable != null) {
            mainHandler.removeCallbacks(pendingExitRunnable);
            pendingExitRunnable = null;
        }

        // 方向切换（从左转到右转，或从右转到左转）
        if (isTurningMode && !direction.equals(currentDirection)) {
            AppLog.d(TAG, "Direction switch: " + currentDirection + " -> " + direction);

            // 检查悬浮窗是否仍然有效
            if (previewContainer != null) {
                // 窗口有效，只更新裁切方向和方向指示器
                currentDirection = direction;
                showTime = System.currentTimeMillis();
                applyTransform(direction); // 重启 ImageView 裁切刷新
                updateDirectionIndicator(direction);
                AppLog.d(TAG, "Direction switched without recreating window");
                return;
            } else {
                // 窗口无效，需要重建
                AppLog.w(TAG, "Window invalid during direction switch, recreating");
                // 先清理旧窗口
                removePreviewWindow();
                closeCamera();
            }
        }

        // 首次进入转向模式或窗口需要重建
        isTurningMode = true;
        currentDirection = direction;

        // 获取唤醒锁（无超时，在 exitTurningMode 中释放）
        // 移除60秒超时，避免长时间转向时系统休眠导致画面冻结
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        // 每次进入时重新读取设置（用户可能在运行时更改）
        boolean showFloatingWindow = prefs.getBoolean(PREF_SHOW_FLOATING_WINDOW, true);

        AppLog.d(TAG, "Enter turning mode: " + direction);
        android.util.Log.e("TURNING_DEBUG", "Enter turning mode: " + direction + ", floatWin=" + showFloatingWindow);

        if (showFloatingWindow) {
            showPreviewWindow(direction);
        } else {
            showPreviewWindowHeadless(direction);
        }

        // 启动 overlay 保活看门狗（防止地图等应用覆盖补盲窗口）
        startOverlayWatchdog();
    }
    private void exitTurningMode() {
        if (!isTurningMode) {
            return;
        }

        // 取消之前的待执行退出
        if (pendingExitRunnable != null) {
            mainHandler.removeCallbacks(pendingExitRunnable);
            pendingExitRunnable = null;
        }

        // 保证最小显示时间（短按转向灯闪3下约3秒，需保持显示）
        long displayedTime = System.currentTimeMillis() - showTime;
        if (displayedTime < MIN_DISPLAY_TIME_MS && showTime > 0) {
            long delayTime = MIN_DISPLAY_TIME_MS - displayedTime;
            android.util.Log.e("TURNING_DEBUG", "exitTurningMode: displayed " + displayedTime +
                    "ms < " + MIN_DISPLAY_TIME_MS + "ms, delay exit by " + delayTime + "ms");
            pendingExitRunnable = this::doExitTurningMode;
            mainHandler.postDelayed(pendingExitRunnable, delayTime);
            return;
        }

        android.util.Log.e("TURNING_DEBUG", "exitTurningMode: displayed " + displayedTime + "ms, exit now");
        doExitTurningMode();
    }

    private void doExitTurningMode() {
        isTurningMode = false;
        currentDirection = TurningSignalMonitor.DIRECTION_NONE;
        showTime = 0;
        pendingExitRunnable = null;

        // GL 管道模式：恢复全景（readPixels 帧共享保持启用）
        if (useGLPipeline && glPipelineRenderer != null) {
            glPipelineRenderer.setCropQuadrant(CameraGLRenderer.CROP_NONE);
            android.util.Log.e("TURNING_DEBUG", "GL Pipeline: restored CROP_NONE");
        }

        // 清空共享帧
        sharedFrame = null;

        // 停止 overlay 看门狗
        stopOverlayWatchdog();

        // 关悬浮窗（立即隐藏）
        hidePreviewWindow();

        // 释放唤醒锁
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    /**
     * 启动 Overlay 保活看门狗
     * 在转向模式期间每 500ms 检查悬浮窗是否被其他应用（如地图）覆盖或移除
     * 如果被移除则重新添加
     */
    private void startOverlayWatchdog() {
        stopOverlayWatchdog();

        overlayWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isTurningMode || mainHandler == null)
                    return;

                try {
                    // 检查悬浮窗是否仍然存在
                    if (previewContainer != null && previewContainer.getWindowToken() == null) {
                        android.util.Log.w("TURNING_DEBUG", "Overlay watchdog: window lost! Re-adding...");
                        try {
                            windowManager.addView(previewContainer, layoutParams);
                            android.util.Log.e("TURNING_DEBUG", "Overlay watchdog: window re-added successfully");
                        } catch (Exception e) {
                            android.util.Log.e("TURNING_DEBUG",
                                    "Overlay watchdog: failed to re-add window: " + e.getMessage());
                        }
                    }

                } catch (Exception e) {
                    android.util.Log.e("TURNING_DEBUG", "Overlay watchdog error: " + e.getMessage());
                }

                // 继续定时检查
                if (isTurningMode && mainHandler != null) {
                    mainHandler.postDelayed(this, OVERLAY_WATCHDOG_INTERVAL_MS);
                }
            }
        };
        mainHandler.postDelayed(overlayWatchdogRunnable, OVERLAY_WATCHDOG_INTERVAL_MS);
        android.util.Log.e("TURNING_DEBUG",
                "Overlay watchdog started (interval=" + OVERLAY_WATCHDOG_INTERVAL_MS + "ms)");
    }

    /**
     * 停止 Overlay 保活看门狗
     */
    private void stopOverlayWatchdog() {
        if (overlayWatchdogRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(overlayWatchdogRunnable);
            overlayWatchdogRunnable = null;
        }
    }

    // ==================== 预览窗口管理 ====================

    /**
     * 无悬浮窗模式：创建隐藏的 1x1 相机 Surface
     */
    private void showPreviewWindowHeadless(String direction) {
        if (previewContainer != null && previewTextureView != null && previewTextureView.isAvailable()) {
            return; // 已存在
        }
        removePreviewWindow();

        previewContainer = new FrameLayout(this);
        previewTextureView = new TextureView(this);
        previewTextureView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                surface.setDefaultBufferSize(getCameraOutputWidth(), getCameraOutputHeight());
                applyTransform(direction);
                openCamera();
                showTime = System.currentTimeMillis();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                closeCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        previewContainer.addView(previewTextureView);
        currentWidth = 64;
        currentHeight = 64;

        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        layoutParams = new WindowManager.LayoutParams(64, 64, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.START | Gravity.TOP;
        layoutParams.x = -200; // 移出屏幕
        layoutParams.y = -200;
        layoutParams.alpha = 0.01f; // 几乎不可见
        layoutParams.windowAnimations = 0; // 禁用系统窗口动画

        try {
            windowManager.addView(previewContainer, layoutParams);
            AppLog.d(TAG, "Headless preview window created for frame sharing");
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to add headless window", e);
        }
    }

    private void showPreviewWindow(String direction) {
        // 标记是否从持久模式（1x1 隐藏）转为可见 - 用于控制防闪帧逻辑
        boolean fromPersistentMode = false;

        // 如果悬浮窗已经存在
        if (previewContainer != null && previewTextureView != null) {
            // 检查是否是常驻的隐藏窗口（alpha 约为 0 或位置在屏幕外）
            boolean isHidden = false;
            if (layoutParams != null) {
                // 如果 alpha 很小，或者位置在屏幕外，认为是隐藏窗口
                isHidden = layoutParams.alpha < 0.1f || layoutParams.width <= 1 || layoutParams.height <= 1;
            }

            if (!isHidden && previewTextureView.isAvailable()) {
                // 窗口已存在且可见，只需更新变换
                android.util.Log.e("TURNING_DEBUG", "Window visible, updating transform for: " + direction);
                applyTransform(direction);
                showTime = System.currentTimeMillis();
                return;
            } else if (isHidden) {
                // 是隐藏的常驻窗口，直接复用（移到可见位置+应用变换）
                android.util.Log.e("TURNING_DEBUG",
                        "Converting persistent window to blindspot window, direction=" + direction);
                fromPersistentMode = true;

                // 【防闪帧】隐藏 TextureView，防止全景四向画面在 ImageView 就绪前闪现
                previewTextureView.setVisibility(View.INVISIBLE);

                // 确保相机已打开
                if (cameraDevice == null) {
                    android.util.Log.e("TURNING_DEBUG", "Camera not open, opening...");
                    openCamera();
                } else {
                    // 即使 cameraDevice 非 null，检查帧共享是否在运行
                    // GL 管道模式下 fullFrameShareRunnable 为 null 是正常的（readPixels 接管）
                    if (fullFrameShareRunnable == null
                            && !(useGLPipeline && glPipelineRenderer != null && glPipelineRenderer.isRunning())) {
                        android.util.Log.e("TURNING_DEBUG",
                                "Camera ref alive but frame sharing stopped, restarting...");
                        closeCamera();
                        openCamera();
                    }
                }

                // 注意：不在这里调用 applyTransform！
                // 先让窗口 resize 到正确尺寸，再由下方代码 + postDelayed 应用变换
                // 这样避免在 1x1 窗口上启动刷新循环导致黑帧
                showTime = System.currentTimeMillis();

                // 继续执行下面的代码来更新窗口位置和可见性
            } else {
                // TextureView 存在但不可用，先移除再重新创建
                android.util.Log.e("TURNING_DEBUG", "TextureView not available, recreating window");
                removePreviewWindow();
            }
        }

        // 悬浮窗始终显示在主屏幕上（HUD/仪表盘通过 Activity 共享帧）
        Context targetContext = this;
        WindowManager targetWindowManager = windowManager;

        // 【修复】强制使用主屏幕 WindowManager 获取尺寸，避免意外使用 HUD 屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        float density = metrics.density;

        AppLog.d(TAG, "Show preview window: display=Main, density=" + density +
                ", screen=" + metrics.widthPixels + "x" + metrics.heightPixels);
        android.util.Log.e("TURNING_DEBUG", "Show preview window on display: Main, screen=" +
                metrics.widthPixels + "x" + metrics.heightPixels);

        // 获取目标屏幕尺寸
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;

        // 加载保存的尺寸，或使用默认值（支持设置中的小/中/大）
        int previewWidth = prefs.getInt(PREF_WINDOW_WIDTH, DEFAULT_PREVIEW_WIDTH);
        int previewHeight = prefs.getInt(PREF_WINDOW_HEIGHT, DEFAULT_PREVIEW_HEIGHT);

        // 确保不小于最小尺寸，但不强制 1280x800（允许用户选"小"）
        if (previewWidth < MIN_PREVIEW_SIZE || previewHeight < MIN_PREVIEW_SIZE) {
            previewWidth = Math.max(MIN_PREVIEW_SIZE, previewWidth);
            previewHeight = Math.max(MIN_PREVIEW_SIZE, previewHeight);
            android.util.Log.e("TURNING_DEBUG",
                    "Clamped to minimum window size: " + previewWidth + "x" + previewHeight);
        }

        // 根据目标屏幕尺寸调整窗口大小（确保不超出屏幕）
        if (previewHeight > screenHeight * 0.9f) {
            // 如果窗口高度超过屏幕高度的 90%，按比例缩小
            float scale = (screenHeight * 0.9f) / previewHeight;
            previewHeight = (int) (previewHeight * scale);
            previewWidth = (int) (previewWidth * scale);
            AppLog.d(TAG, "Adjusted window size to fit screen: " + previewWidth + "x" + previewHeight);
            android.util.Log.e("TURNING_DEBUG", "Adjusted window size: " + previewWidth + "x" + previewHeight
                    + " (screen: " + screenWidth + "x" + screenHeight + ")");
        }

        android.util.Log.e("TURNING_DEBUG", "Window size: " + previewWidth + "x" + previewHeight);

        currentWidth = previewWidth;
        currentHeight = previewHeight;

        // 创建容器和 TextureView（如果不存在）
        if (previewContainer == null) {
            previewContainer = new FrameLayout(targetContext);
            previewTextureView = new TextureView(targetContext);

            previewTextureView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    AppLog.d(TAG, "Surface available");
                    android.util.Log.e("TURNING_DEBUG", "Surface available: " + width + "x" + height);
                    surface.setDefaultBufferSize(getCameraOutputWidth(), getCameraOutputHeight());
                    applyTransform(direction);
                    openCamera();
                    showTime = System.currentTimeMillis();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    if (useGLPipeline && glPipelineRenderer != null && width > 0 && height > 0) {
                        glPipelineRenderer.setPreviewSurfaceSize(width, height);
                    }
                    if (width <= 1 || height <= 1) {
                        return;
                    }
                    android.util.Log.e("TURNING_DEBUG", "TextureView size changed: " + width + "x" + height);
                    applyTransform(currentDirection);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            });

            previewContainer.addView(previewTextureView);
        } else {
            // 容器已存在（常驻预览模式），重置状态
            AppLog.d(TAG, "Reusing existing preview container");
            // 确保 TextureView 可见
            previewTextureView.setVisibility(View.VISIBLE);
            // 注意：不在这里调用 applyTransform，等下面窗口 layout 更新后再调用
            // 这样确保 ImageView 在正确尺寸的容器中创建
            if (cameraDevice == null && previewTextureView.isAvailable()) {
                openCamera();
            }
            showTime = System.currentTimeMillis();
        }

        // 添加方向指示器（保存引用，便于刷新时保持在最前）
        View indicator = createDirectionIndicator(direction, previewWidth, previewHeight, density, targetContext);
        if (indicator != null) {
            directionIndicatorView = indicator;
            previewContainer.addView(indicator);
        } else {
            directionIndicatorView = null;
        }

        // 添加右下角调整大小的手柄（初始隐藏，编辑模式时显示）
        resizeHandle =

                createResizeHandle(density, targetContext);
        resizeHandle.setVisibility(View.GONE);
        previewContainer.addView(resizeHandle);

        // 添加编辑模式指示器（初始隐藏）
        editModeIndicator = createEditModeIndicator(density, targetContext);
        editModeIndicator.setVisibility(View.GONE);
        previewContainer.addView(editModeIndicator);

        previewContainer.setElevation(16 * density);

        // 添加触摸监听器（支持拖拽，仅在编辑模式下有效）
        previewContainer.setOnTouchListener(this::onWindowTouch);

        // 初始化编辑模式 Handler
        editModeHandler = new Handler(Looper.getMainLooper());

        // 窗口参数 - 使用 TYPE_APPLICATION_OVERLAY（Android O+）
        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        android.util.Log.e("TURNING_DEBUG", "Using window type: " + windowType);

        // 悬浮窗可接收触摸事件（长按拖动、点击5次调整大小）
        layoutParams = new WindowManager.LayoutParams(
                previewWidth,
                previewHeight,
                windowType,
                NORMAL_WINDOW_FLAGS,
                PixelFormat.TRANSLUCENT);
        layoutParams.windowAnimations = 0; // 禁用系统窗口动画

        android.util.Log.e("TURNING_DEBUG", "showPreviewWindow: touch enabled, windowType=" + windowType);

        // 【防闪帧】从持久模式转入时，窗口先完全透明，等 ImageView 就绪后再显示
        if (fromPersistentMode) {
            layoutParams.alpha = 0f;
        }

        // 主屏：使用保存的位置或默认位置
        {
            // 使用 Integer.MIN_VALUE 作为未设置标志（支持负坐标，窗口可部分超出屏幕）
            int savedX = prefs.getInt(PREF_WINDOW_X, Integer.MIN_VALUE);
            int savedY = prefs.getInt(PREF_WINDOW_Y, Integer.MIN_VALUE);

            android.util.Log.e("TURNING_DEBUG", "Loading window position: savedX=" + savedX + ", savedY=" + savedY);

            // 检查是否有有效的保存位置
            if (savedX != Integer.MIN_VALUE && savedY != Integer.MIN_VALUE) {
                // 使用保存的位置（绝对定位模式）
                layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
                layoutParams.x = savedX;
                layoutParams.y = savedY;
                currentWindowX = savedX;
                currentWindowY = savedY;
                isAbsolutePositionMode = true;
                android.util.Log.e("TURNING_DEBUG", "Using saved position: " + savedX + "," + savedY);
            } else {
                // 首次使用或位置无效，右上角显示（相对定位模式）
                layoutParams.gravity = Gravity.TOP | Gravity.END;
                layoutParams.x = (int) (20 * density);
                layoutParams.y = (int) (20 * density);
                // 实际位置会在第一次 touch 时通过 getLocationOnScreen 获取
                currentWindowX = -1; // 使用 -1 表示未初始化
                currentWindowY = -1;
                isAbsolutePositionMode = false;
                android.util.Log.e("TURNING_DEBUG", "Using default position (relative mode)");
            }
        }

        try {
            // 保存当前使用的WindowManager
            activeWindowManager = targetWindowManager;

            if (previewContainer.getParent() != null) {
                // 如果已经添加（常驻预览模式），只需更新布局
                targetWindowManager.updateViewLayout(previewContainer, layoutParams);
                android.util.Log.e("TURNING_DEBUG", "Preview window updated: " + previewWidth + "x" + previewHeight +
                        " at (" + layoutParams.x + "," + layoutParams.y + ")");
            } else {
                targetWindowManager.addView(previewContainer, layoutParams);
                android.util.Log.e("TURNING_DEBUG", "Preview window added: " + previewWidth + "x" + previewHeight +
                        " at (" + layoutParams.x + "," + layoutParams.y + ")");
            }

            // 设置圆角背景
            previewContainer.setClipToOutline(true);
            previewContainer.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    int cornerRadiusPx = (int) (CORNER_RADIUS_DP * density);
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
                }
            });

            // 快速显示：先设最终状态再短动画（减少感知延迟）
            // 从持久模式转入时跳过容器动画（窗口级 alpha=0，等 ImageView 就绪后再显示）
            if (!fromPersistentMode) {
                previewContainer.setAlpha(1f);
                previewContainer.setScaleX(1f);
                previewContainer.setScaleY(1f);
                previewContainer.setTranslationY(0f);
                if (ENTER_ANIMATION_MS > 0) {
                    previewContainer.setAlpha(0.6f);
                    previewContainer.setScaleX(0.95f);
                    previewContainer.setScaleY(0.95f);
                    previewContainer.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(ENTER_ANIMATION_MS)
                            .start();
                }
            } else {
                // 持久模式转入：容器保持就绪状态，窗口级 alpha=0 负责隐藏
                previewContainer.setAlpha(1f);
                previewContainer.setScaleX(1f);
                previewContainer.setScaleY(1f);
                previewContainer.setTranslationY(0f);
            }

            // 窗口 layout 已更新完成，延迟应用变换（确保 view 布局完成后再创建 ImageView）
            // 这解决了从持久模式(1x1)转换到可见模式时，ImageView 在错误尺寸下创建的问题
            final String dirForTransform = direction;
            final boolean needReveal = fromPersistentMode;
            mainHandler.post(() -> {
                if (previewTextureView != null && previewTextureView.isAvailable() && isTurningMode) {
                    android.util.Log.e("TURNING_DEBUG", "Post-layout applyTransform for: " + dirForTransform);
                    applyTransform(dirForTransform);
                    // 【防闪帧】ImageView 已就绪，现在安全地显示窗口
                    if (needReveal) {
                        revealWindowAfterTransform();
                    }
                } else {
                    android.util.Log.e("TURNING_DEBUG", "Post-layout: TextureView not ready, scheduling retry");
                    // TextureView 可能还没准备好，延迟重试
                    mainHandler.postDelayed(() -> {
                        if (previewTextureView != null && previewTextureView.isAvailable() && isTurningMode) {
                            applyTransform(dirForTransform);
                            if (needReveal) {
                                revealWindowAfterTransform();
                            }
                        } else if (needReveal) {
                            // 兜底：即使 TextureView 不可用也要显示窗口，避免永远不可见
                            revealWindowAfterTransform();
                        }
                    }, 500);
                }
            });

            AppLog.d(TAG, "Preview window created");
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to add preview window", e);
        }
    }

    /**
     * 【防闪帧】从持久模式转入后，ImageView 就绪，安全地显示窗口
     * 将 LayoutParams.alpha 从 0 设为 1，带短动画
     */
    private void revealWindowAfterTransform() {
        if (layoutParams == null || previewContainer == null)
            return;
        try {
            // 如果还不是绝对定位模式，先锁定当前位置为绝对坐标
            // 防止后续 show/hide 循环中 Gravity.END 模式导致位置不一致
            if (!isAbsolutePositionMode && previewContainer.getWidth() > 0) {
                int[] location = new int[2];
                previewContainer.getLocationOnScreen(location);
                if (location[0] >= 0 && location[1] >= 0) {
                    layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
                    layoutParams.x = location[0];
                    layoutParams.y = location[1];
                    currentWindowX = location[0];
                    currentWindowY = location[1];
                    isAbsolutePositionMode = true;
                    android.util.Log.e("TURNING_DEBUG", "revealWindow: locked position to absolute " +
                            location[0] + "," + location[1]);
                }
            }

            layoutParams.alpha = 1f;
            getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);

            // 【关键】在窗口真正可见时才设置 showTime
            // 之前 showTime 在窗口创建时就设置了，导致 MIN_DISPLAY_TIME_MS 提前到期
            showTime = System.currentTimeMillis();

            android.util.Log.e("TURNING_DEBUG", "Window revealed after ImageView ready (anti-flash) at " +
                    layoutParams.x + "," + layoutParams.y + ", showTime reset to NOW");

            // 短淡入动画
            if (ENTER_ANIMATION_MS > 0) {
                previewContainer.setAlpha(0.6f);
                previewContainer.animate()
                        .alpha(1f)
                        .setDuration(ENTER_ANIMATION_MS)
                        .start();
            }

            // 延迟保存位置（避免阻塞第一帧显示，减少 HUD 唤醒卡顿）
            mainHandler.postDelayed(this::saveWindowSettings, 500);
        } catch (Exception e) {
            android.util.Log.e("TURNING_DEBUG", "revealWindow failed: " + e.getMessage());
        }
    }

    private void hidePreviewWindow() {
        // 退出编辑模式
        if (isEditMode) {
            exitEditMode();
        }

        if (previewContainer != null && layoutParams != null) {
            android.util.Log.e("TURNING_DEBUG", "Hiding blindspot window immediately");
            // 直接隐藏（不用动画，避免回调丢失导致窗口永远无法消失）
            convertToPersistentMode();
        }
    }

    /**
     * 将悬浮窗转换回隐藏的常驻模式（动画结束后调用）
     */
    private void convertToPersistentMode() {
        if (previewContainer == null || layoutParams == null)
            return;

        try {
            // 第一步：立即把窗口设为完全透明（Window 级别隐藏，零延迟）
            layoutParams.alpha = 0f;
            windowManager.updateViewLayout(previewContainer, layoutParams);

            // 第二步：停止刷新（此时窗口已不可见，不会闪黑）
            stopBlindspotRefresh();

            // 第三步：缩小为 1x1 持久模式
            layoutParams.width = 1;
            layoutParams.height = 1;
            layoutParams.x = -10;
            layoutParams.y = -10;
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            windowManager.updateViewLayout(previewContainer, layoutParams);

            // 第四步：重置 View 属性，清理子视图
            previewContainer.setAlpha(1f);
            previewContainer.setScaleX(1f);
            previewContainer.setScaleY(1f);
            previewContainer.setTranslationY(0f);

            for (int i = previewContainer.getChildCount() - 1; i >= 0; i--) {
                View child = previewContainer.getChildAt(i);
                if (child != previewTextureView) {
                    previewContainer.removeView(child);
                }
            }
            blindspotImageView = null;
            directionIndicatorView = null;

            if (previewTextureView != null && previewTextureView.isAvailable()) {
                previewTextureView.setTransform(null);
            }

            android.util.Log.e("TURNING_DEBUG", "Window converted back to persistent mode");
        } catch (Exception e) {
            android.util.Log.e("TURNING_DEBUG", "Failed to hide window: " + e.getMessage());
        }
    }

    private void removePreviewWindow() {
        AppLog.d(TAG, "removePreviewWindow called, previewContainer=" + (previewContainer != null));

        // 确保在主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::removePreviewWindow);
            return;
        }

        // 移除悬浮窗
        if (previewContainer != null) {
            try {
                // 先将视图设置为不可见，避免残留
                previewContainer.setVisibility(View.GONE);

                // 从 WindowManager 移除
                WindowManager wm = getActiveWindowManager();
                if (wm != null) {
                    wm.removeViewImmediate(previewContainer);
                    AppLog.d(TAG, "Preview window removed successfully");
                }
            } catch (IllegalArgumentException e) {
                // 视图可能已经不在窗口中
                AppLog.w(TAG, "View not attached: " + e.getMessage());
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to remove preview window", e);
            } finally {
                if (resizeSnapshotBitmap != null) {
                    if (!resizeSnapshotBitmap.isRecycled())
                        resizeSnapshotBitmap.recycle();
                    resizeSnapshotBitmap = null;
                }
                // 确保清理所有引用
                previewContainer = null;
                previewTextureView = null;
                resizeHandle = null;
                editModeIndicator = null;
                directionIndicatorView = null;
                layoutParams = null;
            }
        }

        // 清理编辑模式相关状态
        isEditMode = false;
        isResizing = false;
        isDragging = false;
        isLongPressing = false;

        if (exitEditModeRunnable != null && editModeHandler != null) {
            editModeHandler.removeCallbacks(exitEditModeRunnable);
            exitEditModeRunnable = null;
        }

        if (longPressRunnable != null && longPressHandler != null) {
            longPressHandler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private View createDirectionIndicator(String direction, int width, int height, float density, Context context) {
        // 检查是否启用提醒条
        boolean showIndicator = prefs.getBoolean(PREF_SHOW_INDICATOR, true);
        if (!showIndicator) {
            return null;
        }

        FrameLayout indicator = new FrameLayout(context);
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (36 * density));
        indicatorParams.gravity = Gravity.TOP;
        indicator.setLayoutParams(indicatorParams);

        // 创建单独的背景 View 用于模糊效果
        View blurBackground = new View(context);
        FrameLayout.LayoutParams bgParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        blurBackground.setLayoutParams(bgParams);

        // 设置半透明背景
        android.graphics.drawable.GradientDrawable blurBg = new android.graphics.drawable.GradientDrawable();
        blurBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        blurBg.setColor(0x60000000); // 半透明黑色底色
        blurBackground.setBackground(blurBg);

        // API 31+ 只对背景 View 应用模糊效果，文字不受影响
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurBackground.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    15f, 15f, android.graphics.Shader.TileMode.CLAMP));
            blurBg.setColor(0x50000000); // 模糊时使用稍浅的背景
        }

        // 先添加背景
        indicator.addView(blurBackground);

        // 创建文字 TextView（放在背景之上，不受模糊影响）
        TextView label = new TextView(context);
        label.setTextSize(16);
        label.setTextColor(0xFFFFFFFF);
        label.setPadding((int) (12 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(2, 1, 1, 0x80000000); // 添加文字阴影增强可读性

        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        label.setLayoutParams(labelParams);

        if (TurningSignalMonitor.DIRECTION_LEFT.equals(direction)) {
            label.setText("◀ 左后轮毂视图");
        } else if (TurningSignalMonitor.DIRECTION_RIGHT.equals(direction)) {
            label.setText("右后轮毂视图 ▶");
        } else {
            return null;
        }

        indicator.addView(label);
        return indicator;
    }

    private void updateDirectionIndicator(String direction) {
        if (previewContainer == null || previewContainer.getWidth() < 10) {
            return; // 无悬浮窗模式（headless）不更新指示器
        }

        // 移除旧的方向指示器（保留 index 0 的 TextureView 和最后一个 resizeHandle）
        // previewContainer 的子视图顺序：[0] TextureView, [1] indicator (如果有), [最后]
        // resizeHandle
        int childCount = previewContainer.getChildCount();
        if (childCount >= 2) {
            // 从后往前移除，避免索引问题，但保留第一个和最后一个
            for (int i = childCount - 2; i >= 1; i--) {
                previewContainer.removeViewAt(i);
            }
        }

        Context targetContext = this;
        DisplayMetrics metrics = targetContext.getResources().getDisplayMetrics();
        float density = metrics.density;

        View indicator = createDirectionIndicator(direction, currentWidth, currentHeight, density, targetContext);
        if (indicator != null) {
            directionIndicatorView = indicator;
            // 插入到 TextureView 之后，resizeHandle 之前
            previewContainer.addView(indicator, 1);
        } else {
            directionIndicatorView = null;
        }
    }

    // 始终使用直接 TextureView 模式（不经 GL 渲染器，getBitmap 返回真正的四向全景帧）
    private volatile boolean useDirectTextureView = true;

    private static int normalizeWindowRotationDeg(int deg) {
        if (deg == 90 || deg == 180 || deg == 270) return deg;
        return 0;
    }

    private int getRotationDegForDirection(String direction) {
        int legacy = prefs.getInt(MainActivity.PREF_FLOATING_WINDOW_ROTATION, 0);
        if (TurningSignalMonitor.DIRECTION_LEFT.equals(direction)) {
            return normalizeWindowRotationDeg(prefs.getInt(MainActivity.PREF_FLOATING_WINDOW_ROTATION_LEFT, legacy));
        }
        if (TurningSignalMonitor.DIRECTION_RIGHT.equals(direction)) {
            return normalizeWindowRotationDeg(prefs.getInt(MainActivity.PREF_FLOATING_WINDOW_ROTATION_RIGHT, legacy));
        }
        return 0;
    }

    private void applyPreviewRotation(String direction) {
        int deg = getRotationDegForDirection(direction);
        if (useGLPipeline && glPipelineRenderer != null) {
            glPipelineRenderer.setPreviewRotationDegrees(deg);
        }
        Runnable r = () -> {
            if (previewTextureView != null) previewTextureView.setRotation(0);
            if (blindspotImageView != null) blindspotImageView.setRotation(deg);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else if (mainHandler != null) {
            mainHandler.post(r);
        }
    }

    private void applyTransform(String direction) {
        // 【防竞争】跳过过时的方向（快速切换转向灯时，延迟回调可能携带旧方向）
        if (isTurningMode && currentDirection != null && !currentDirection.equals(direction)
                && !TurningSignalMonitor.DIRECTION_NONE.equals(currentDirection)) {
            android.util.Log.w("TURNING_DEBUG", "applyTransform SKIPPED: stale direction=" + direction +
                    ", current=" + currentDirection);
            return;
        }

        applyPreviewRotation(direction);

        // ==================== GL 管道模式 ====================
        if (useGLPipeline && glPipelineRenderer != null && glPipelineRenderer.isRunning()) {
            android.util.Log.e("TURNING_DEBUG", "applyTransform(GL pipeline): direction=" + direction);

            float[] rect = getEffectiveTurningCropRectNormalized(direction);
            glPipelineRenderer.setCropRect(rect[0], rect[1], rect[2], rect[3]);

            // GL 模式下不需要 ImageView 和 blindspotRefreshRunnable
            if (blindspotImageView != null) {
                blindspotImageView.setVisibility(android.view.View.GONE);
            }

            return;
        }

        // ==================== 旧方案：直接模式 + ImageView 裁切 ====================
        // 补盲期间：Camera → TextureView（直接，不经 GL），getBitmap 获取真正的全景帧
        // ImageView 覆盖 TextureView 显示裁切后的象限
        // 帧共享继续从 TextureView getBitmap() 获取全景帧（无 transform）
        // ==============================================================

        android.util.Log.e("TURNING_DEBUG", "applyTransform(legacy getBitmap): direction=" + direction);

        // 确保 ImageView 存在并显示
        ensureBlindspotImageView();

        // 启动裁切刷新循环（从 TextureView 直接 getBitmap 获取全景帧）
        startBlindspotRefresh(direction);

        // 立即预填充一帧 sharedFrame（减少 HUD/仪表盘 Activity 启动后的等待）
        if (previewTextureView != null && previewTextureView.isAvailable()) {
            try {
                int outW = getCameraOutputWidth();
                int outH = getCameraOutputHeight();
                android.graphics.Bitmap quickFrame = previewTextureView.getBitmap(
                        Math.max(320, outW / 2), Math.max(180, outH / 2));
                if (quickFrame != null && !quickFrame.isRecycled()) {
                    float[] rect = blindspotCropRectNormalized;
                    int fw = quickFrame.getWidth();
                    int fh = quickFrame.getHeight();
                    int cl = Math.round(rect[0] * fw);
                    int ct = Math.round(rect[1] * fh);
                    int cr = Math.round(rect[2] * fw);
                    int cb = Math.round(rect[3] * fh);
                    cl = Math.max(0, Math.min(cl, fw - 1));
                    ct = Math.max(0, Math.min(ct, fh - 1));
                    cr = Math.max(cl + 1, Math.min(cr, fw));
                    cb = Math.max(ct + 1, Math.min(cb, fh));
                    int cw = cr - cl;
                    int ch = cb - ct;
                    if (cw > 0 && ch > 0) {
                        android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(
                                quickFrame, cl, ct, cw, ch);
                        cropped.recycle();
                    }
                    quickFrame.recycle();
                }
            } catch (Exception e) {
                /* ignore */ }
        }
    }

    /**
     * 确保补盲 ImageView 存在（覆盖在 TextureView 上方）
     */
    private void ensureBlindspotImageView() {
        if (previewContainer == null) {
            android.util.Log.e("TURNING_DEBUG", "ensureBlindspotImageView: previewContainer is NULL!");
            return;
        }

        // 检查 ImageView 是否在当前容器中（容器可能被重建过）
        if (blindspotImageView != null && blindspotImageView.getParent() == previewContainer) {
            blindspotImageView.setVisibility(android.view.View.VISIBLE);
            blindspotImageView.bringToFront();
            return;
        }

        // ImageView 不在当前容器中（或不存在），重新创建
        if (blindspotImageView != null && blindspotImageView.getParent() != null) {
            // 从旧容器中移除
            ((android.view.ViewGroup) blindspotImageView.getParent()).removeView(blindspotImageView);
        }
        blindspotImageView = null;

        blindspotImageView = new android.widget.ImageView(this);
        blindspotImageView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        blindspotImageView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        blindspotImageView.setBackgroundColor(android.graphics.Color.BLACK);
        // 将触摸转发给容器，保证点视频区域也能长按移动、点 5 下进编辑
        blindspotImageView
                .setOnTouchListener((v, event) -> previewContainer != null && onWindowTouch(previewContainer, event));

        // 添加到容器顶层（覆盖 TextureView）
        previewContainer.addView(blindspotImageView);
        applyPreviewRotation(currentDirection);
        blindspotImageView.bringToFront();
        previewContainer.requestLayout();
        android.util.Log.e("TURNING_DEBUG",
                "Blindspot ImageView created and added to container, children=" + previewContainer.getChildCount());
    }

    // 补盲裁切参数（归一化坐标 0~1，直接作用于当前帧尺寸，避免 outputSize 不一致导致错裁）
    private volatile float[] blindspotCropRectNormalized = new float[] { 0f, 0f, 0.25f, 1f };

    /**
     * 启动补盲画面裁切刷新循环
     */
    private void startBlindspotRefresh(String direction) {
        if (!TurningSignalMonitor.DIRECTION_LEFT.equals(direction) && !TurningSignalMonitor.DIRECTION_RIGHT.equals(direction)) {
            return;
        }
        float[] rect = getEffectiveTurningCropRectNormalized(direction);

        blindspotCropRectNormalized = new float[] { rect[0], rect[1], rect[2], rect[3] };

        android.util.Log.e("TURNING_DEBUG", "Blindspot crop updated(norm): direction=" + direction +
                ", crop=(" + rect[0] + "," + rect[1] + "," + rect[2] + "," + rect[3] + ")");

        // 如果循环已在运行且 isTurningMode 为 true，不重新创建（避免闪帧）
        // 但如果 isTurningMode 刚从 false 变为 true（新一轮补盲），需要重新创建
        if (blindspotRefreshRunnable != null && isTurningMode) {
            android.util.Log.e("TURNING_DEBUG", "Blindspot refresh already running, updating crop only");
            return;
        }
        // 确保清理旧的 runnable
        stopBlindspotRefresh();

        blindspotRefreshRunnable = new Runnable() {
            private int logCounter = 0;

            @Override
            public void run() {
                if (blindspotImageView == null || !isTurningMode)
                    return;

                // 【修复】调整大小时暂停帧刷新，避免覆盖静态快照
                if (isResizing) {
                    if (isTurningMode && mainHandler != null) {
                        mainHandler.postDelayed(this, BLINDSPOT_REFRESH_INTERVAL_MS);
                    }
                    return;
                }

                try {
                    if (previewTextureView == null || !previewTextureView.isAvailable())
                        return;

                    // 窗口从持久模式(1x1)转换到可见模式时，TextureView 可能还未 layout
                    // 跳过，等 onSurfaceTextureSizeChanged 触发重新创建刷新循环
                    if (previewTextureView.getWidth() < 100 || previewTextureView.getHeight() < 100) {
                        if (isTurningMode && mainHandler != null) {
                            mainHandler.postDelayed(this, 100); // 100ms 后重试
                        }
                        return;
                    }

                    int fastW = 2048;
                    int outW = getCameraOutputWidth();
                    int outH = getCameraOutputHeight();
                    int fastH = Math.max(200, Math.round(fastW * (outH / (float) outW)));
                    android.graphics.Bitmap frame = previewTextureView.getBitmap(fastW, fastH);

                    if (frame != null && !frame.isRecycled()) {
                        int fw = frame.getWidth();
                        int fh = frame.getHeight();

                        float[] cropNorm = blindspotCropRectNormalized;
                        float nl = cropNorm[0], nt = cropNorm[1], nr = cropNorm[2], nb = cropNorm[3];
                        int scaledCl = Math.round(nl * fw);
                        int scaledCt = Math.round(nt * fh);
                        int scaledCr = Math.round(nr * fw);
                        int scaledCb = Math.round(nb * fh);
                        scaledCl = Math.max(0, Math.min(scaledCl, fw - 1));
                        scaledCt = Math.max(0, Math.min(scaledCt, fh - 1));
                        scaledCr = Math.max(scaledCl + 1, Math.min(scaledCr, fw));
                        scaledCb = Math.max(scaledCt + 1, Math.min(scaledCb, fh));
                        int scaledCw = scaledCr - scaledCl;
                        int scaledCh = scaledCb - scaledCt;

                        // 诊断日志（每2秒一次）
                        if (logCounter++ % 60 == 0) {
                            android.util.Log.e("TURNING_DEBUG", "BlindspotRefresh: " +
                                    fw + "x" + fh + " crop=(" + scaledCl + "," + scaledCt + "," + scaledCw + "x"
                                    + scaledCh + ")");
                        }

                        synchronized (TurningBlindSpotService.this) {
                            sharedFullFrame = frame;
                        }

                        // 裁切对应象限（使用缩放后的坐标 + 预分配 Bitmap）
                        if (fw >= scaledCl + scaledCw && fh >= scaledCt + scaledCh && scaledCw > 0 && scaledCh > 0) {
                            // 确保预分配 Bitmap 存在且尺寸正确
                            if (reusableCroppedBitmap == null || reusableCroppedBitmap.getWidth() != scaledCw
                                    || reusableCroppedBitmap.getHeight() != scaledCh) {
                                if (reusableCroppedBitmap != null && !reusableCroppedBitmap.isRecycled())
                                    reusableCroppedBitmap.recycle();
                                reusableCroppedBitmap = android.graphics.Bitmap.createBitmap(scaledCw, scaledCh,
                                        android.graphics.Bitmap.Config.ARGB_8888);
                                reusableCroppedCanvas = new android.graphics.Canvas(reusableCroppedBitmap);
                            }
                            // Canvas drawBitmap 复用目标 Bitmap（零分配）
                            srcRect.set(scaledCl, scaledCt, scaledCl + scaledCw, scaledCt + scaledCh);
                            dstRect.set(0, 0, scaledCw, scaledCh);
                            reusableCroppedCanvas.drawBitmap(frame, srcRect, dstRect, null);

                            android.graphics.Bitmap displayBitmap = reusableCroppedBitmap;

                            // 实时更新 ImageView（resize 时也继续更新，跟随窗口缩放）
                            {
                                // 回收上一帧 ImageView Bitmap（避免 GC 累积）
                                if (lastDisplayBitmap != null && lastDisplayBitmap != displayBitmap
                                        && lastDisplayBitmap != reusableCroppedBitmap
                                        && !lastDisplayBitmap.isRecycled()) {
                                    lastDisplayBitmap.recycle();
                                }
                                lastDisplayBitmap = displayBitmap;

                                // 显示在悬浮窗 ImageView
                                blindspotImageView.setImageBitmap(displayBitmap);
                                blindspotImageView.setVisibility(android.view.View.VISIBLE);
                                blindspotImageView.bringToFront();
                                // 方向提示条、编辑条、调整手柄保持在补盲画面上方（手柄最后置顶以便接收触摸）
                                if (directionIndicatorView != null
                                        && directionIndicatorView.getParent() == previewContainer) {
                                    directionIndicatorView.bringToFront();
                                }
                                if (editModeIndicator != null && editModeIndicator.getParent() == previewContainer) {
                                    editModeIndicator.bringToFront();
                                }
                                if (resizeHandle != null && resizeHandle.getParent() == previewContainer) {
                                    resizeHandle.bringToFront();
                                }
                            }

                        }
                    }
                } catch (Exception e) {
                    // 忽略偶发的 Bitmap 回收竞争
                }

                // 继续刷新
                if (isTurningMode && mainHandler != null) {
                    mainHandler.postDelayed(this, BLINDSPOT_REFRESH_INTERVAL_MS);
                }
            }
        };

        mainHandler.post(blindspotRefreshRunnable);
    }

    /**
     * 停止补盲画面刷新
     */
    private void stopBlindspotRefresh() {
        if (blindspotRefreshRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(blindspotRefreshRunnable);
            blindspotRefreshRunnable = null;
        }

        // 隐藏 ImageView
        if (blindspotImageView != null) {
            blindspotImageView.setVisibility(android.view.View.GONE);
            blindspotImageView.setImageBitmap(null);
        }
    }

    /**
     * 应用校准参数变换（偏移、缩放、旋转）到裁切后的 Bitmap
     * 参数来自 BlindspotCalibrationActivity 保存的 SharedPreferences
     */
    /**
     * 应用校准裁切（从象限中按百分比裁切子区域）
     * 参数来自 BlindspotCalibrationActivity 保存的 SharedPreferences
     * 新格式：left_crop_x/y/w/h（百分比 0-100）
     */
    // 校准参数缓存（避免每帧读 SharedPreferences）
    private float cachedCalibCropX, cachedCalibCropY, cachedCalibCropW = 100, cachedCalibCropH = 100;
    private String cachedCalibDirection = null;

    private void refreshCalibrationCache(String direction) {
        String prefix = TurningSignalMonitor.DIRECTION_LEFT.equals(direction) ? "left_" : "right_";
        cachedCalibCropX = prefs.getFloat(prefix + "crop_x", 0);
        cachedCalibCropY = prefs.getFloat(prefix + "crop_y", 0);
        cachedCalibCropW = prefs.getFloat(prefix + "crop_w", 100);
        cachedCalibCropH = prefs.getFloat(prefix + "crop_h", 100);
        cachedCalibDirection = direction;
    }

    private float[] getEffectiveTurningCropRectNormalized(String direction) {
        if (!direction.equals(cachedCalibDirection)) {
            refreshCalibrationCache(direction);
        }

        CameraLayoutConfig.Direction d = TurningSignalMonitor.DIRECTION_LEFT.equals(direction)
                ? CameraLayoutConfig.Direction.LEFT
                : CameraLayoutConfig.Direction.RIGHT;

        float[] base = CameraLayoutConfig.getCropRectNormalized(prefs, d);
        float baseW = base[2] - base[0];
        float baseH = base[3] - base[1];

        float l = base[0] + (cachedCalibCropX / 100f) * baseW;
        float t = base[1] + (cachedCalibCropY / 100f) * baseH;
        float r = l + (cachedCalibCropW / 100f) * baseW;
        float b = t + (cachedCalibCropH / 100f) * baseH;

        return clampRect01(l, t, r, b);
    }

    private static float[] clampRect01(float l, float t, float r, float b) {
        float cl = clamp01(Math.min(l, r));
        float cr = clamp01(Math.max(l, r));
        float ct = clamp01(Math.min(t, b));
        float cb = clamp01(Math.max(t, b));
        if (cr - cl < 0.01f) cr = clamp01(cl + 0.01f);
        if (cb - ct < 0.01f) cb = clamp01(ct + 0.01f);
        return new float[] { cl, ct, cr, cb };
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private android.graphics.Bitmap applyCalibrationTransform(android.graphics.Bitmap cropped, String direction) {
        if (cropped == null || cropped.isRecycled())
            return cropped;

        try {
            // 使用缓存（方向变化或首次时刷新）
            if (!direction.equals(cachedCalibDirection)) {
                refreshCalibrationCache(direction);
            }
            float cx = cachedCalibCropX;
            float cy = cachedCalibCropY;
            float cw = cachedCalibCropW;
            float ch = cachedCalibCropH;

            // 全部默认值（完整象限），不做裁切
            if (cx == 0 && cy == 0 && cw >= 100 && ch >= 100) {
                return cropped;
            }

            int w = cropped.getWidth();
            int h = cropped.getHeight();

            int srcX = Math.round(cx / 100f * w);
            int srcY = Math.round(cy / 100f * h);
            int srcW = Math.round(cw / 100f * w);
            int srcH = Math.round(ch / 100f * h);

            // 边界保护
            srcW = Math.min(srcW, w - srcX);
            srcH = Math.min(srcH, h - srcY);
            if (srcW <= 0 || srcH <= 0)
                return cropped;

            return android.graphics.Bitmap.createBitmap(cropped, srcX, srcY, srcW, srcH);

        } catch (Exception e) {
            android.util.Log.w("TURNING_DEBUG", "Calibration crop error: " + e.getMessage());
            return cropped;
        }
    }

    /**
    // ==================== 摄像头资源接管（从 BlindspotApp） ====================

    /**
     * 从 BlindspotApp 接管已预热的摄像头资源
     * BlindspotApp 在进程创建时就开始预热摄像头，
     * 服务启动后直接使用这些已经就绪的资源，省去初始化和预热等待时间
     * 
     * @return true 如果成功接管
     */
    private boolean adoptCameraFromApp() {
        BlindspotApp app = BlindspotApp.getInstance();
        if (app == null) {
            android.util.Log.w("TURNING_DEBUG", "adoptCameraFromApp: BlindspotApp not available");
            return false;
        }

        BlindspotApp.CameraResources resources = app.adoptCameraResources();
        if (resources == null) {
            android.util.Log.w("TURNING_DEBUG", "adoptCameraFromApp: no camera resources available");
            return false;
        }

        // 接管所有资源
        this.previewTextureView = resources.textureView;
        this.previewContainer = resources.container;
        this.layoutParams = resources.layoutParams;
        this.cameraDevice = resources.cameraDevice;
        this.captureSession = resources.captureSession;
        this.panoramicCameraId = resources.panoramicCameraId;
        this.cameraThread = resources.cameraThread;
        this.cameraHandler = resources.cameraHandler;
        this.warmupFramesRemaining = resources.warmupFramesRemaining;

        android.util.Log.e("TURNING_DEBUG", "adoptCameraFromApp: SUCCESS - " +
                "camera=" + panoramicCameraId +
                ", warmupComplete=" + resources.isWarmupComplete +
                ", warmupRemaining=" + warmupFramesRemaining +
                ", device=" + (cameraDevice != null) +
                ", session=" + (captureSession != null));

        return true;
    }

    /**
     * 设置接管后的 TextureView 监听器
     * 替换 BlindspotApp 设置的简单监听器为服务的完整监听器
     */
    private void setupAdoptedTextureViewListener() {
        if (previewTextureView == null)
            return;

        previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                // 接管后不太会触发（TextureView 已经可用），但作为防御
                AppLog.d(TAG, "Adopted TextureView surface available");
                android.util.Log.e("TURNING_DEBUG", "Adopted TextureView available: " + width + "x" + height);
                surface.setDefaultBufferSize(getCameraOutputWidth(), getCameraOutputHeight());
                useDirectTextureView = true;
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                android.util.Log.e("TURNING_DEBUG", "TextureView size changed: " + width + "x" + height);
                if (useGLPipeline && glPipelineRenderer != null && width > 0 && height > 0) {
                    glPipelineRenderer.setPreviewSurfaceSize(width, height);
                }
                if (isTurningMode && width > 100 && height > 100 && currentDirection != null
                        && !TurningSignalMonitor.DIRECTION_NONE.equals(currentDirection)) {
                    android.util.Log.e("TURNING_DEBUG",
                            "Reapplying transform after size change to " + width + "x" + height);
                    stopBlindspotRefresh();
                    applyTransform(currentDirection);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                android.util.Log.e("TURNING_DEBUG", "TextureView destroyed");
                stopPreviewFrameSharing();
                closeCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
            }
        });
    }

    // ==================== 相机管理 ====================

    private int getCameraOutputWidth() {
        if (prefs == null) return CAMERA_WIDTH;
        return CameraLayoutConfig.getOutputWidth(prefs);
    }

    private int getCameraOutputHeight() {
        if (prefs == null) return CAMERA_HEIGHT;
        return CameraLayoutConfig.getOutputHeight(prefs);
    }

    private void findPanoramicCamera() {
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            String preferredId = prefs != null ? prefs.getString(PREF_CAMERA_ID, null) : null;

            if (preferredId != null) {
                for (String id : cameraIds) {
                    if (preferredId.equals(id)) {
                        panoramicCameraId = id;
                        AppLog.d(TAG, "Using preferred camera: " + id);
                        return;
                    }
                }
                AppLog.w(TAG, "Preferred cameraId not found: " + preferredId);
            }

            for (String id : cameraIds) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                // 默认使用第一个相机（全景相机通常是 camera0）
                panoramicCameraId = id;
                AppLog.d(TAG, "Found camera: " + id);
                break;
            }
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Failed to find camera", e);
        }
    }

    private synchronized void openCamera() {
        if (panoramicCameraId == null) {
            AppLog.e(TAG, "No camera found");
            return;
        }

        // 如果相机已经打开，不要重复打开
        if (cameraDevice != null) {
            android.util.Log.w("TURNING_DEBUG", "Camera already opened, skipping");
            return;
        }

        try {
            android.util.Log.e("TURNING_DEBUG", "Opening camera: " + panoramicCameraId);
            cameraManager.openCamera(panoramicCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    AppLog.d(TAG, "Camera opened");
                    android.util.Log.e("TURNING_DEBUG", "Camera opened successfully: " + panoramicCameraId);
                    cameraDevice = camera;
                    // 相机刚打开，前几帧可能是残留画面，设置预热跳过
                    warmupFramesRemaining = WARMUP_FRAMES_TO_SKIP;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    AppLog.d(TAG, "Camera disconnected");
                    android.util.Log.w("TURNING_DEBUG", "Camera disconnected");
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    AppLog.e(TAG, "Camera error: " + error);
                    android.util.Log.e("TURNING_DEBUG", "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            AppLog.e(TAG, "Failed to open camera", e);
            android.util.Log.e("TURNING_DEBUG", "Failed to open camera: " + e.getMessage(), e);
        }
    }

    private void createCaptureSession() {
        if (cameraDevice == null) {
            android.util.Log.e("TURNING_DEBUG", "Cannot create session: cameraDevice is null");
            return;
        }

        Surface cameraSurface = null;

        // 【GL 管道模式】Camera → GL SurfaceTexture → Shader 裁切 → TextureView
        if (useGLPipeline && glPipelineRenderer != null && glPipelineRenderer.getCameraSurface() != null) {
            cameraSurface = glPipelineRenderer.getCameraSurface();
            android.util.Log.e("TURNING_DEBUG",
                    "Creating camera session with GL Pipeline Surface (GPU crop, zero getBitmap)");
        } else if (useDirectTextureView && previewTextureView != null && previewTextureView.isAvailable()) {
            // 旧方案：Camera → TextureView 直接（getBitmap 返回真正的全景帧）
            SurfaceTexture texture = previewTextureView.getSurfaceTexture();
            if (texture != null) {
                texture.setDefaultBufferSize(getCameraOutputWidth(), getCameraOutputHeight());
                cameraSurface = new Surface(texture);
            }
            android.util.Log.e("TURNING_DEBUG",
                    "Creating camera session with TextureView Surface (legacy getBitmap), buffer="
                            + getCameraOutputWidth() + "x" + getCameraOutputHeight());
        } else if (previewTextureView != null && previewTextureView.isAvailable()) {
            SurfaceTexture texture = previewTextureView.getSurfaceTexture();
            if (texture != null) {
                texture.setDefaultBufferSize(getCameraOutputWidth(), getCameraOutputHeight());
                cameraSurface = new Surface(texture);
            }
            android.util.Log.e("TURNING_DEBUG", "Creating camera session with TextureView Surface (fallback), buffer="
                    + getCameraOutputWidth() + "x" + getCameraOutputHeight());
        } else {
            android.util.Log.e("TURNING_DEBUG", "Cannot create session: no valid surface");
            return;
        }

        try {

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(cameraSurface);

            // 优化预览质量和帧率
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(30, 30));

            cameraDevice.createCaptureSession(java.util.Arrays.asList(cameraSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                android.util.Log.e("TURNING_DEBUG", "Camera session started successfully @ 30fps");

                                // 启动预览帧共享（用于预览界面和虚拟摄像头）
                                startPreviewFrameSharing();

                            } catch (CameraAccessException e) {
                                AppLog.e(TAG, "Failed to start preview", e);
                                android.util.Log.e("TURNING_DEBUG", "Failed to start preview: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            AppLog.e(TAG, "Capture session configuration failed");
                            android.util.Log.e("TURNING_DEBUG", "Capture session configuration FAILED");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Failed to create capture session", e);
            android.util.Log.e("TURNING_DEBUG", "Failed to create capture session: " + e.getMessage(), e);
        }
    }

    /**
     * 创建常驻相机预览（使用 OpenGL 渲染管道，高性能）
     */
    private void createPersistentCameraPreview() {
        if (previewTextureView != null) {
            AppLog.d(TAG, "Persistent camera preview already exists");
            return;
        }

        android.util.Log.e("TURNING_DEBUG", "Creating persistent camera preview with OpenGL renderer");

        // 创建 TextureView（作为 OpenGL 渲染目标）
        previewTextureView = new TextureView(this);
        previewContainer = new FrameLayout(this);
        previewContainer.addView(previewTextureView);

        previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                AppLog.d(TAG, "Preview TextureView surface available");
                android.util.Log.e("TURNING_DEBUG", "TextureView available: " + width + "x" + height);
                surface.setDefaultBufferSize(getCameraOutputWidth(), getCameraOutputHeight());

                if (useGLPipeline) {
                    // 【GL 管道模式】Camera → GL SurfaceTexture → Shader 裁切 → TextureView
                    android.util.Log.e("TURNING_DEBUG", "Initializing GL Pipeline (GPU crop, zero getBitmap)");
                    initGLPipeline(surface);
                } else {
                    // 旧方案：Camera → TextureView 直接（getBitmap）
                    android.util.Log.e("TURNING_DEBUG", "Opening camera directly to TextureView (legacy getBitmap)");
                    useDirectTextureView = true;
                    openCamera();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                android.util.Log.e("TURNING_DEBUG", "TextureView size changed: " + width + "x" + height);
                // 通知 GL 渲染器预览表面的实际尺寸（渲染 viewport 匹配窗口大小）
                // readPixels 使用独立 PBuffer，不受此影响
                if (useGLPipeline && glPipelineRenderer != null && width > 0 && height > 0) {
                    glPipelineRenderer.setPreviewSurfaceSize(width, height);
                }
                // 从持久模式(1x1) resize 到正常尺寸时，需要重新应用补盲变换
                if (isTurningMode && width > 100 && height > 100 && currentDirection != null
                        && !TurningSignalMonitor.DIRECTION_NONE.equals(currentDirection)) {
                    android.util.Log.e("TURNING_DEBUG",
                            "Reapplying transform after size change to " + width + "x" + height);
                    stopBlindspotRefresh();
                    applyTransform(currentDirection);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                android.util.Log.e("TURNING_DEBUG", "TextureView destroyed");
                stopPreviewFrameSharing();
                closeCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        // 创建隐藏窗口（使用相机尺寸，移出屏幕）
        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 使用 1x1 尺寸 + alpha=0 确保完全不可见（某些车机 alpha=0.01 仍显示黑色矩形）
        // SurfaceTexture 的 buffer 尺寸独立于 View 尺寸，getBitmap 不受影响
        layoutParams = new WindowManager.LayoutParams(
                1, 1,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        layoutParams.gravity = Gravity.START | Gravity.TOP;
        layoutParams.x = -10;
        layoutParams.y = -10;
        layoutParams.alpha = 0f;
        layoutParams.windowAnimations = 0; // 禁用系统窗口动画

        try {
            windowManager.addView(previewContainer, layoutParams);
            AppLog.d(TAG, "Persistent camera preview created (hidden)");
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to create persistent preview", e);
        }
    }

    // ==================== GL 管道模式（beta - 零 getBitmap 高性能管道）====================

    /**
     * 初始化 GL 管道渲染器
     * Camera → GL SurfaceTexture → Shader 裁切 → TextureView
     * 
     * @param textureViewSurface TextureView 的 SurfaceTexture（用于预览输出）
     */
    private void initGLPipeline(SurfaceTexture textureViewSurface) {
        if (glPipelineRenderer != null) {
            glPipelineRenderer.stop();
            glPipelineRenderer = null;
        }

        // 创建 GL 管道渲染器
        glPipelineRenderer = new CameraGLRenderer(getCameraOutputWidth(), getCameraOutputHeight());

        glPipelineRenderer.setFrameShareCallback(fullPanoramicFrame -> {
            if (fullPanoramicFrame == null || fullPanoramicFrame.isRecycled()) return;
            synchronized (TurningBlindSpotService.this) {
                sharedFullFrame = fullPanoramicFrame;
            }
        });

        glPipelineRenderer.setCallback(new CameraGLRenderer.Callback() {
            @Override
            public void onSurfaceTextureReady(SurfaceTexture surfaceTexture) {
                android.util.Log.e("TURNING_DEBUG", "GL Pipeline SurfaceTexture ready, opening camera");
                // GL 管道使用自己的 SurfaceTexture 接收相机帧
                useDirectTextureView = false;
                openCamera();
            }

            @Override
            public void onError(String error) {
                android.util.Log.e("TURNING_DEBUG", "GL Pipeline error: " + error);
                // 回退到旧方案
                mainHandler.post(() -> {
                    android.util.Log.e("TURNING_DEBUG", "GL Pipeline failed, falling back to legacy getBitmap mode");
                    useGLPipeline = false;
                    stopGLPipeline();
                    useDirectTextureView = true;
                    openCamera();
                });
            }
        });

        // 启动 GL 渲染器，将预览输出到 TextureView
        Surface previewOutput = new Surface(textureViewSurface);
        glPipelineRenderer.start(null); // 不传 TextureView，手动设置 Surface
        glPipelineRenderer.setPreviewSurface(previewOutput);

        // 默认全景（不裁切），转向时由 applyTransform 切换到对应象限
        glPipelineRenderer.setCropQuadrant(CameraGLRenderer.CROP_NONE);
        // 始终启用 readPixels 帧共享（浮窗可能是 1x1 持久模式，getBitmap 不可靠）
        glPipelineRenderer.setFrameShareEnabled(true);

        android.util.Log.e("TURNING_DEBUG", "GL Pipeline initialized: " +
                CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
    }

    /**
     * 停止 GL 管道渲染器
     */
    private void stopGLPipeline() {
        if (glPipelineRenderer != null) {
            android.util.Log.e("TURNING_DEBUG", "Stopping GL Pipeline");
            try {
                glPipelineRenderer.stop();
            } catch (Exception e) {
                android.util.Log.w("TURNING_DEBUG", "Error stopping GL Pipeline: " + e.getMessage());
            }
            glPipelineRenderer = null;
        }
        // 清理 GL 帧共享 Bitmap
        if (glFrameShareBitmap != null && !glFrameShareBitmap.isRecycled()) {
            glFrameShareBitmap.recycle();
            glFrameShareBitmap = null;
            glFrameShareCanvas = null;
        }
    }

    /**
     * 启动全景画面共享（优化版：降低帧率减少主线程负担）
     * 
     * 架构：
     * - 补盲：直接从 TextureView 显示（零延迟，硬件渲染）
     * - 预览：getBitmap @ 30fps（非 GL 模式下兜底；GL 模式由 readPixels 更新 sharedFullFrame）
     */
    private void startPreviewFrameSharing() {
        // GL 管道模式下完全跳过 getBitmap 帧共享
        // GL readPixels 异步线程已接管 SharedMemory 写入，getBitmap 是多余的 CPU 开销
        // 仅检查 useGLPipeline（启动时确定，不依赖 glPipelineRenderer 初始化时序）
        if (useGLPipeline) {
            android.util.Log.d("TURNING_DEBUG",
                    "Skipping getBitmap frame sharing: GL pipeline mode, async readPixels handles SharedMemory");
            return;
        }

        if (fullFrameShareRunnable != null) {
            android.util.Log.w("TURNING_DEBUG", "Frame sharing already running");
            return;
        }

        // 创建后台线程处理帧共享（高优先级，避免被系统繁忙时降低）
        if (frameShareThread == null) {
            frameShareThread = new android.os.HandlerThread("PreviewFrameShare",
                    android.os.Process.THREAD_PRIORITY_DISPLAY);
            frameShareThread.start();
            frameShareHandler = new android.os.Handler(frameShareThread.getLooper());
        }

        // 帧率配置
        final int NORMAL_INTERVAL_MS = 33; // 30fps

        // 预分配复用 Bitmap（避免每帧 ~16MB 分配导致 GC 压力）
        // TextureView.getBitmap(Bitmap) 自 API 26 可用，我们 minSdk=28
        int outW = getCameraOutputWidth();
        int outH = getCameraOutputHeight();
        if (reusableFullFrameBitmap == null || reusableFullFrameBitmap.isRecycled()
                || reusableFullFrameBitmap.getWidth() != outW
                || reusableFullFrameBitmap.getHeight() != outH) {
            if (reusableFullFrameBitmap != null && !reusableFullFrameBitmap.isRecycled()) {
                reusableFullFrameBitmap.recycle();
            }
            reusableFullFrameBitmap = android.graphics.Bitmap.createBitmap(
                    outW, outH, android.graphics.Bitmap.Config.ARGB_8888);
            android.util.Log.e("TURNING_DEBUG", "Pre-allocated reusable frame bitmap: " +
                    outW + "x" + outH);
        }

        android.util.Log.e("TURNING_DEBUG",
                "Starting preview frame sharing (reusable bitmap, interval=" + NORMAL_INTERVAL_MS + "ms)");

        fullFrameShareRunnable = new Runnable() {
            private int frameCount = 0;
            private long lastLogTime = System.currentTimeMillis();
            private long cameraDisconnectedSince = 0;

            @Override
            public void run() {
                // ===== 相机健康看门狗 =====
                if (previewTextureView != null && previewTextureView.isAvailable() && cameraDevice == null) {
                    if (cameraDisconnectedSince == 0) {
                        cameraDisconnectedSince = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - cameraDisconnectedSince > 5000) {
                        android.util.Log.e("TURNING_DEBUG", "Watchdog: camera disconnected >5s, auto-reconnecting");
                        cameraDisconnectedSince = 0;
                        mainHandler.post(() -> {
                            closeCamera();
                            openCamera();
                        });
                    }
                } else {
                    cameraDisconnectedSince = 0;
                }

                int currentInterval = NORMAL_INTERVAL_MS;

                if (previewTextureView != null && previewTextureView.isAvailable()) {
                    // GL 管道模式：始终跳过 getBitmap（浮窗可能是 1x1 持久模式，getBitmap 返回空白帧）
                    // GL 渲染器的 readPixels 帧共享始终提供 CROP_NONE 全景帧
                    if (useGLPipeline && glPipelineRenderer != null && glPipelineRenderer.isRunning()) {
                        scheduleNext(currentInterval);
                        return;
                    }

                    // 非 GL 模式补盲时跳过（blindspotRefreshRunnable 已处理帧共享）
                    if (isTurningMode && blindspotRefreshRunnable != null) {
                        scheduleNext(currentInterval);
                        return;
                    }

                    try {
                        // 直接在后台线程调用 getBitmap（TextureView.getBitmap 是线程安全的）
                        // 使用预分配 Bitmap 复用，零内存分配
                        android.graphics.Bitmap fullFrame = null;
                        if (reusableFullFrameBitmap != null && !reusableFullFrameBitmap.isRecycled()) {
                            // getBitmap(Bitmap) 直接写入现有 Bitmap（API 26+，零分配）
                            fullFrame = previewTextureView.getBitmap(reusableFullFrameBitmap);
                        }

                        if (fullFrame == null || fullFrame.isRecycled()) {
                            // 回退：分配新 Bitmap（不应该发生，但保底）
                            fullFrame = previewTextureView.getBitmap(outW, outH);
                        }

                        if (fullFrame != null && !fullFrame.isRecycled()) {
                            // 相机预热：跳过前 N 帧（避免残留/错误画面）
                            if (warmupFramesRemaining > 0) {
                                warmupFramesRemaining--;
                                android.util.Log.w("TURNING_DEBUG", "Skipping warmup frame (" +
                                        warmupFramesRemaining + " remaining)");
                                scheduleNext(currentInterval);
                                return;
                            }

                            // 更新共享帧引用（sharedFullFrame 使用预分配 Bitmap，不需要 recycle）
                            synchronized (TurningBlindSpotService.this) {
                                android.graphics.Bitmap oldFrame = sharedFullFrame;
                                sharedFullFrame = fullFrame;
                                // 仅回收非复用的旧帧
                                if (oldFrame != null && !oldFrame.isRecycled()
                                        && oldFrame != fullFrame && oldFrame != reusableFullFrameBitmap) {
                                    oldFrame.recycle();
                                }
                            }

                            // 统计
                            frameCount++;
                            long now = System.currentTimeMillis();
                            if (now - lastLogTime >= 5000) {
                                float fps = frameCount / ((now - lastLogTime) / 1000.0f);
                                android.util.Log.e("TURNING_DEBUG",
                                        "Frame sharing: " + String.format("%.1f", fps) + " fps, " +
                                                "size: " + fullFrame.getWidth() + "x" + fullFrame.getHeight());
                                frameCount = 0;
                                lastLogTime = now;
                            }
                        }
                    } catch (OutOfMemoryError oom) {
                        android.util.Log.e("TURNING_DEBUG", "OOM in frame sharing");
                        System.gc();
                    } catch (Exception e) {
                        android.util.Log.w("TURNING_DEBUG", "Frame error: " + e.getMessage());
                    }
                }

                scheduleNext(currentInterval);
            }

            private long lastFrameTimeMs = 0;

            private void scheduleNext(int interval) {
                if (fullFrameShareRunnable != null && frameShareHandler != null) {
                    // 【修复】时间补偿调度：减去本帧处理耗时，防止累积延迟导致掉帧
                    long now = System.currentTimeMillis();
                    long elapsed = (lastFrameTimeMs > 0) ? (now - lastFrameTimeMs) : 0;
                    lastFrameTimeMs = now;
                    long delay = Math.max(1, interval - Math.max(0, elapsed - interval));
                    frameShareHandler.postDelayed(fullFrameShareRunnable, delay);
                }
            }
        };

        frameShareHandler.post(fullFrameShareRunnable);
    }

    /**
     * 停止预览帧共享
     */
    private void stopPreviewFrameSharing() {
        android.util.Log.e("TURNING_DEBUG", "Stopping preview frame sharing");

        if (fullFrameShareRunnable != null) {
            if (frameShareHandler != null) {
                frameShareHandler.removeCallbacks(fullFrameShareRunnable);
            }
            mainHandler.removeCallbacks(fullFrameShareRunnable);
            fullFrameShareRunnable = null;
        }

        // 停止后台线程
        if (frameShareThread != null) {
            frameShareThread.quitSafely();
            frameShareThread = null;
            frameShareHandler = null;
        }
    }

    /**
     * 停止全景画面共享
     */
    private void stopFullFrameSharing() {
        android.util.Log.e("TURNING_DEBUG", "Stopping full frame sharing");

        if (fullFrameShareRunnable != null) {
            if (frameShareHandler != null) {
                frameShareHandler.removeCallbacks(fullFrameShareRunnable);
            }
            mainHandler.removeCallbacks(fullFrameShareRunnable);
            fullFrameShareRunnable = null;
        }

        // 停止后台线程
        if (frameShareThread != null) {
            frameShareThread.quitSafely();
            frameShareThread = null;
            frameShareHandler = null;
        }

        // previousFullFrame 已移除（死代码）
        if (sharedFullFrame != null && !sharedFullFrame.isRecycled()) {
            sharedFullFrame.recycle();
            sharedFullFrame = null;
        }
    }

    /**
     * 根据方向裁剪帧（从原始四向画面中提取对应视角）
     */
    private android.graphics.Bitmap cropFrameForDirection(android.graphics.Bitmap rawFrame, String direction) {
        if (rawFrame == null || direction == null)
            return null;

        int rawWidth = rawFrame.getWidth();
        int rawHeight = rawFrame.getHeight();
        CameraLayoutConfig.Direction d;
        if (TurningSignalMonitor.DIRECTION_LEFT.equals(direction)) {
            d = CameraLayoutConfig.Direction.LEFT;
        } else if (TurningSignalMonitor.DIRECTION_RIGHT.equals(direction)) {
            d = CameraLayoutConfig.Direction.RIGHT;
        } else {
            return null;
        }

        float[] rect = CameraLayoutConfig.getCropRectNormalized(prefs, d);
        int srcLeft = Math.round(rect[0] * rawWidth);
        int srcTop = Math.round(rect[1] * rawHeight);
        int srcWidth = Math.round((rect[2] - rect[0]) * rawWidth);
        int srcHeight = Math.round((rect[3] - rect[1]) * rawHeight);
        srcWidth = Math.min(srcWidth, rawWidth - srcLeft);
        srcHeight = Math.min(srcHeight, rawHeight - srcTop);
        if (srcWidth <= 0 || srcHeight <= 0) return null;

        try {
            return android.graphics.Bitmap.createBitmap(rawFrame, srcLeft, srcTop, srcWidth, srcHeight);
        } catch (Exception e) {
            android.util.Log.e("TURNING_DEBUG", "cropFrameForDirection error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 高清版裁剪（输出固定尺寸）
     */
    private android.graphics.Bitmap cropFrameForDirectionHD(android.graphics.Bitmap rawFrame, String direction) {
        android.graphics.Bitmap cropped = cropFrameForDirection(rawFrame, direction);
        if (cropped == null) return null;
        if (cropped.getWidth() == SHARED_FRAME_WIDTH && cropped.getHeight() == SHARED_FRAME_HEIGHT) return cropped;

        try {
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                    cropped, SHARED_FRAME_WIDTH, SHARED_FRAME_HEIGHT, true);
            if (scaled != cropped) {
                cropped.recycle();
            }
            return scaled;
        } catch (Exception e) {
            android.util.Log.e("TURNING_DEBUG", "cropFrameForDirectionHD error: " + e.getMessage());
            return cropped;
        }
    }

    /**
     * 服务启动/重启时自动授权权限 + 启用无线调试 + 建立 ADB
     * 解决重启后摄像头 "disabled by policy"、ADB 断开等问题
     */
    private void autoGrantPermissionsAndInitAdb() {
        new Thread(() -> {
            android.util.Log.e("TURNING_DEBUG", "Auto-granting permissions and initializing ADB...");
            String pkg = getPackageName();
            boolean suSuccess = false;

            // 方法1: 尝试 su（部分车机有 root）
            try {
                Process su = Runtime.getRuntime().exec("su");
                java.io.OutputStream os = su.getOutputStream();
                os.write(("pm grant " + pkg + " android.permission.CAMERA\n").getBytes());
                os.write(("pm grant " + pkg + " android.permission.READ_LOGS\n").getBytes());
                os.write(("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow\n").getBytes());
                os.write("settings put global adb_enabled 1\n".getBytes());
                os.write("settings put global adb_wifi_enabled 1\n".getBytes());
                // 使用 persist.adb.tcp.port 确保重启后仍然有效
                os.write("setprop persist.adb.tcp.port 5555\n".getBytes());
                os.write("setprop service.adb.tcp.port 5555\n".getBytes());
                os.write("stop adbd\nstart adbd\nexit\n".getBytes());
                os.flush();
                boolean exited = su.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                int exitCode = exited ? su.exitValue() : -1;
                suSuccess = exited && exitCode == 0;
                android.util.Log.e("TURNING_DEBUG", "su result: exited=" + exited + ", exitCode=" + exitCode);
                if (!exited)
                    su.destroyForcibly();
            } catch (Exception e) {
                android.util.Log.w("TURNING_DEBUG", "su failed: " + e.getMessage());
            }

            // 方法2: 不需要 root 的方式尝试开启 ADB TCP
            if (!suSuccess) {
                android.util.Log.e("TURNING_DEBUG", "su failed, trying non-root methods...");
                try {
                    // 某些车机 shell 用户有 setprop 权限
                    Process sh = Runtime.getRuntime().exec("sh");
                    java.io.OutputStream os = sh.getOutputStream();
                    os.write("setprop persist.adb.tcp.port 5555\n".getBytes());
                    os.write("setprop service.adb.tcp.port 5555\n".getBytes());
                    os.write("exit\n".getBytes());
                    os.flush();
                    sh.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    sh.destroyForcibly();
                } catch (Exception e) {
                    android.util.Log.w("TURNING_DEBUG", "sh setprop failed: " + e.getMessage());
                }

                // 尝试通过 Settings API（需要 WRITE_SECURE_SETTINGS 权限）
                try {
                    android.provider.Settings.Global.putInt(getContentResolver(), "adb_enabled", 1);
                    android.util.Log.e("TURNING_DEBUG", "Enabled ADB via Settings API");
                } catch (Exception e) {
                    android.util.Log.w("TURNING_DEBUG", "Settings API failed: " + e.getMessage());
                }
            }

            // 等待 adbd 重启
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }

            // 方法3: 通过 ADB TCP 自授权（带重试）
            if (adbExecutor != null) {
                attemptAdbConnectionWithRetry(pkg, 0);
            }
        }).start();
    }

    /**
     * 带重试的 ADB 连接尝试（最多 3 次，每次间隔递增）
     */
    private void attemptAdbConnectionWithRetry(String pkg, int attempt) {
        final int MAX_ATTEMPTS = 3;
        final int[] DELAYS_MS = { 2000, 3000, 5000 };

        if (adbExecutor == null || attempt >= MAX_ATTEMPTS) {
            if (attempt >= MAX_ATTEMPTS) {
                android.util.Log.e("TURNING_DEBUG", "ADB connection failed after " + MAX_ATTEMPTS + " attempts");
            }
            return;
        }

        adbExecutor.establishConnectionAsync((connected, message) -> {
            sAdbConnected = connected;
            android.util.Log.e("TURNING_DEBUG", "ADB init attempt " + (attempt + 1) + "/" + MAX_ATTEMPTS + ": " +
                    (connected ? "SUCCESS" : "FAILED") + " - " + message);
            if (connected) {
                // ADB 连接成功，通过 ADB 授权权限
                android.util.Log.e("TURNING_DEBUG", "Granting permissions via ADB...");
                adbExecutor.executeAsync("pm grant " + pkg + " android.permission.CAMERA", null);
                adbExecutor.executeAsync("pm grant " + pkg + " android.permission.READ_LOGS", null);
                adbExecutor.executeAsync("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow", null);
                // 确保 ADB TCP 持久化
                adbExecutor.executeAsync("setprop persist.adb.tcp.port 5555", null);
                mainHandler.post(() -> {
                    // 权限授予后重连相机（如果之前因权限被拒绝）
                    if (cameraDevice == null && previewTextureView != null && previewTextureView.isAvailable()) {
                        android.util.Log.e("TURNING_DEBUG", "Reopening camera after ADB permission grant");
                        mainHandler.postDelayed(() -> openCamera(), 500);
                    }
                });
            } else if (attempt + 1 < MAX_ATTEMPTS) {
                // 重试
                int delay = DELAYS_MS[Math.min(attempt, DELAYS_MS.length - 1)];
                android.util.Log.e("TURNING_DEBUG", "ADB retry in " + delay + "ms...");
                mainHandler.postDelayed(() -> {
                    new Thread(() -> attemptAdbConnectionWithRetry(pkg, attempt + 1)).start();
                }, delay);
            }
        });
    }

    private void closeCamera() {
        // 停止所有帧共享线程（先停共享，防止回调访问已释放资源）
        stopPreviewFrameSharing();

        // 【关键顺序】先关闭相机会话和设备，再停 GL 管道
        // 否则 GL 管道销毁 SurfaceTexture 后，Camera 仍在向已销毁 Surface 写入，导致崩溃
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }

        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }
            cameraDevice = null;
        }

        // 相机已关闭，安全停止 GL 管道渲染器
        stopGLPipeline();

        // 清理共享帧（双缓冲）
        if (previousFrame != null && !previousFrame.isRecycled()) {
            previousFrame.recycle();
            previousFrame = null;
        }
        if (sharedFrame != null && !sharedFrame.isRecycled()) {
            sharedFrame.recycle();
            sharedFrame = null;
        }

        // 清理预分配的复用 Bitmap
        // 注意：sharedFullFrame 可能引用 reusableFullFrameBitmap，需要先清引用
        synchronized (this) {
            sharedFullFrame = null;
        }
        if (reusableFullFrameBitmap != null && !reusableFullFrameBitmap.isRecycled()) {
            reusableFullFrameBitmap.recycle();
            reusableFullFrameBitmap = null;
        }
    }

    // ==================== 通知 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "转向补盲服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("转向补盲服务运行通知");

            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    // ==================== 触摸处理（拖拽） ====================

    /**
     * 创建编辑模式指示器（显示在窗口底部）
     */
    private View createEditModeIndicator(float density, Context context) {
        TextView indicator = new TextView(context);
        indicator.setText("开始调整悬浮窗大小 · 右下角按钮可拖动调整 · 点击此处完成");
        indicator.setTextSize(12);
        indicator.setTextColor(0xFFFFFFFF);
        indicator.setBackgroundColor(0xCC2196F3); // 蓝色半透明背景
        indicator.setGravity(Gravity.CENTER);
        indicator.setPadding((int) (8 * density), (int) (4 * density), (int) (52 * density), (int) (4 * density)); // 右侧留空给右下角调整手柄
        indicator.setClickable(true);
        indicator.setOnClickListener(v -> exitEditMode());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        indicator.setLayoutParams(params);

        return indicator;
    }

    /**
     * 进入编辑模式
     */
    private void enterEditMode() {
        isEditMode = true;

        // 移除 FLAG_NOT_TOUCHABLE，允许触摸
        if (layoutParams != null && previewContainer != null) {
            layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to update window flags", e);
            }
        }

        // 编辑模式保持 TextureView 可见，保证 getBitmap 有视频流、画面不透明；仅在实际拖动调大小时再隐藏防闪
        // 显示调整手柄和编辑指示器
        if (resizeHandle != null) {
            resizeHandle.setVisibility(View.VISIBLE);
        }
        if (editModeIndicator != null) {
            editModeIndicator.setVisibility(View.VISIBLE);
            editModeIndicator.setOnClickListener(v -> exitEditMode());
        }

        mainHandler.post(() -> android.widget.Toast.makeText(this,
                "开始调整悬浮窗大小，可拖动右下角按钮调整", android.widget.Toast.LENGTH_SHORT).show());

        // 取消之前的自动退出
        if (exitEditModeRunnable != null && editModeHandler != null) {
            editModeHandler.removeCallbacks(exitEditModeRunnable);
        }

        // 设置 10 秒后自动退出编辑模式
        exitEditModeRunnable = this::exitEditMode;
        editModeHandler.postDelayed(exitEditModeRunnable, 10000);

        AppLog.d(TAG, "Entered edit mode");
    }

    /**
     * 退出编辑模式
     */
    private void exitEditMode() {
        isEditMode = false;

        // 恢复正常触摸标志（不要加 FLAG_NOT_TOUCHABLE，否则悬浮窗会锁死无法拖动）
        if (layoutParams != null && previewContainer != null) {
            layoutParams.flags = NORMAL_WINDOW_FLAGS;
            try {
                getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to update window flags", e);
            }
        }

        // 隐藏调整手柄和编辑指示器
        if (resizeHandle != null) {
            resizeHandle.setVisibility(View.GONE);
        }
        if (editModeIndicator != null) {
            editModeIndicator.setVisibility(View.GONE);
        }

        // 取消自动退出定时器
        if (exitEditModeRunnable != null && editModeHandler != null) {
            editModeHandler.removeCallbacks(exitEditModeRunnable);
            exitEditModeRunnable = null;
        }

        // 保存窗口设置
        saveWindowSettings();

        AppLog.d(TAG, "Exited edit mode");
    }

    private View createResizeHandle(float density, Context context) {
        // 创建右下角的调整大小手柄（需可点击以优先接收触摸）
        TextView handle = new TextView(context);
        handle.setText("⤡"); // 调整大小图标
        handle.setContentDescription("拖动调整悬浮窗大小");
        handle.setTextSize(20);
        handle.setTextColor(0xFFFFFFFF);
        handle.setBackgroundColor(0x60000000);
        handle.setGravity(Gravity.CENTER);
        handle.setClickable(true);
        handle.setFocusable(true);

        int handleSize = (int) (44 * density); // 稍大一点便于点中
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(handleSize, handleSize);
        params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        params.rightMargin = (int) (4 * density);
        params.bottomMargin = (int) (4 * density);
        handle.setLayoutParams(params);

        // 设置调整大小的触摸监听
        handle.setOnTouchListener(this::onResizeTouch);

        return handle;
    }

    private boolean onResizeTouch(View v, MotionEvent event) {
        // 只在编辑模式下处理调整大小
        if (!isEditMode) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                startWidth = currentWidth;
                startHeight = currentHeight;
                isResizing = true;

                // 【修复】截图方案：捕获当前补盲画面作为静态快照，避免调整时露出四摄全景
                captureResizeSnapshot();

                resetEditModeTimer();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isResizing && layoutParams != null && previewContainer != null) {
                    float deltaX = event.getRawX() - touchStartX;
                    float deltaY = event.getRawY() - touchStartY;

                    int newWidth = startWidth + (int) deltaX;
                    int newHeight = startHeight + (int) deltaY;

                    // 限制尺寸范围
                    newWidth = Math.max(MIN_PREVIEW_SIZE, Math.min(newWidth, MAX_PREVIEW_WIDTH));
                    newHeight = Math.max(MIN_PREVIEW_SIZE, Math.min(newHeight, MAX_PREVIEW_HEIGHT));

                    layoutParams.width = newWidth;
                    layoutParams.height = newHeight;
                    // 【注意】调整期间不更新 currentWidth/currentHeight，防止触发 HUD/仪表尺寸保存
                    // currentWidth/currentHeight 仅在 ACTION_UP 确认后才更新

                    try {
                        getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Failed to resize window", e);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isResizing) {
                    // 确认最终尺寸
                    currentWidth = layoutParams.width;
                    currentHeight = layoutParams.height;

                    // 【修复】恢复实时画面，回收截图
                    restoreFromResizeSnapshot();

                    saveWindowSettings();
                    isResizing = false;
                    // resize 结束后重启补盲刷新确保帧正常
                    if (isTurningMode) {
                        String dir = (currentDirection != null
                                && !TurningSignalMonitor.DIRECTION_NONE.equals(currentDirection))
                                        ? currentDirection
                                        : TurningSignalMonitor.DIRECTION_LEFT;
                        applyTransform(dir);
                    }
                }
                return true;
        }
        return false;
    }

    /**
     * 捕获当前补盲画面作为静态快照（用于调整大小时显示）
     * 避免调整期间露出 TextureView 的四摄全景画面
     */
    private void captureResizeSnapshot() {
        try {
            // 优先从 blindspotImageView 获取当前显示的裁切画面
            android.graphics.Bitmap snapshot = null;
            if (blindspotImageView != null && blindspotImageView.getDrawable() != null) {
                blindspotImageView.setDrawingCacheEnabled(true);
                android.graphics.Bitmap cache = blindspotImageView.getDrawingCache();
                if (cache != null && !cache.isRecycled()) {
                    snapshot = cache.copy(cache.getConfig(), false);
                }
                blindspotImageView.setDrawingCacheEnabled(false);
            }

            // 回退：GL 模式下从 sharedFrame 获取（补盲裁切帧），避免 TextureView 全景
            if (snapshot == null && sharedFrame != null && !sharedFrame.isRecycled()) {
                snapshot = sharedFrame.copy(sharedFrame.getConfig(), false);
            }

            // 最终回退：从 TextureView 获取（非 GL 模式或 sharedFrame 不可用）
            if (snapshot == null && previewTextureView != null && previewTextureView.isAvailable()) {
                snapshot = previewTextureView.getBitmap();
            }

            if (snapshot != null && !snapshot.isRecycled()) {
                // 回收旧的快照
                if (resizeSnapshotBitmap != null && !resizeSnapshotBitmap.isRecycled()) {
                    resizeSnapshotBitmap.recycle();
                }
                resizeSnapshotBitmap = snapshot;

                // 将快照设置到 blindspotImageView（替换实时帧）
                if (blindspotImageView != null) {
                    blindspotImageView.setImageBitmap(resizeSnapshotBitmap);
                    blindspotImageView.setVisibility(View.VISIBLE);
                    blindspotImageView.bringToFront();
                }

                // 隐藏 TextureView 防止四摄全景露出
                if (previewTextureView != null) {
                    previewTextureView.setVisibility(View.INVISIBLE);
                }

                // 暂停补盲刷新循环（避免快照被实时帧覆盖）
                stopBlindspotRefresh();

                AppLog.d(TAG, "Resize snapshot captured: " + snapshot.getWidth() + "x" + snapshot.getHeight());
            } else {
                AppLog.w(TAG, "Failed to capture resize snapshot, using live preview");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Error capturing resize snapshot", e);
        }
    }

    /**
     * 恢复实时画面（调整大小结束后）
     */
    private void restoreFromResizeSnapshot() {
        // 恢复 TextureView 可见
        if (previewTextureView != null) {
            previewTextureView.setVisibility(View.VISIBLE);
        }

        // 回收快照 Bitmap
        if (resizeSnapshotBitmap != null && !resizeSnapshotBitmap.isRecycled()) {
            resizeSnapshotBitmap.recycle();
            resizeSnapshotBitmap = null;
        }

        AppLog.d(TAG, "Resize snapshot released, restoring live preview");
    }

    private boolean onWindowTouch(View v, MotionEvent event) {
        // 如果正在调整大小，只处理调整大小逻辑
        if (isResizing) {
            return false;
        }

        // 检查必要的组件是否存在
        if (layoutParams == null || previewContainer == null) {
            android.util.Log.e("TURNING_DEBUG", "onWindowTouch: layoutParams or previewContainer is null");
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                isDragging = false;
                isLongPressing = false;
                touchDownTime = System.currentTimeMillis();

                // 如果还没有切换到绝对定位模式，现在切换
                if (!isAbsolutePositionMode) {
                    // 获取当前窗口在屏幕上的实际位置
                    int[] location = new int[2];
                    previewContainer.getLocationOnScreen(location);

                    android.util.Log.e("TURNING_DEBUG", "onWindowTouch: switching to absolute mode, location=" +
                            location[0] + "," + location[1]);

                    // 只要窗口可见（宽高大于0），就认为位置有效
                    if (previewContainer.getWidth() > 0 && previewContainer.getHeight() > 0) {
                        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
                        layoutParams.x = location[0];
                        layoutParams.y = location[1];
                        currentWindowX = location[0];
                        currentWindowY = location[1];
                        isAbsolutePositionMode = true;
                        try {
                            getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
                            // 保存位置，确保下次打开时使用正确的位置
                            saveWindowSettings();
                        } catch (Exception e) {
                            android.util.Log.e("TURNING_DEBUG", "Failed to switch to absolute mode: " + e.getMessage());
                        }
                    }
                } else {
                    // 已经是绝对定位模式，确保 currentWindowX/Y 与 layoutParams 同步
                    // 防止 layoutParams 被系统重置导致位置错乱

                    // 如果 currentWindow 位置无效（-1），从 layoutParams 获取
                    if (currentWindowX < 0 || currentWindowY < 0) {
                        currentWindowX = Math.max(0, layoutParams.x);
                        currentWindowY = Math.max(0, layoutParams.y);
                        android.util.Log.e("TURNING_DEBUG", "onWindowTouch: recovered position from layoutParams: " +
                                currentWindowX + "," + currentWindowY);
                    }

                    // 检查并同步位置（layoutParams 可能被系统重置）
                    if (layoutParams.x != currentWindowX || layoutParams.y != currentWindowY) {
                        android.util.Log.e("TURNING_DEBUG", "onWindowTouch: syncing position, layoutParams=" +
                                layoutParams.x + "," + layoutParams.y + " -> currentWindow=" +
                                currentWindowX + "," + currentWindowY);
                        layoutParams.x = currentWindowX;
                        layoutParams.y = currentWindowY;
                        try {
                            getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
                        } catch (Exception ignored) {
                        }
                    }
                }

                // 使用保存的位置作为拖动起点（确保非负）
                windowStartX = currentWindowX;
                windowStartY = currentWindowY;

                android.util.Log.e("TURNING_DEBUG", "onWindowTouch DOWN: windowStart=" +
                        windowStartX + "," + windowStartY + ", isAbsolute=" + isAbsolutePositionMode);

                android.util.Log.e("TURNING_DEBUG", "onWindowTouch ACTION_DOWN: start=" + touchStartX + ","
                        + touchStartY);

                // 如果在编辑模式下，重置定时器
                if (isEditMode) {
                    resetEditModeTimer();
                }

                return true;

            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getRawX() - touchStartX;
                float deltaY = event.getRawY() - touchStartY;

                int touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
                if (!isDragging && (Math.abs(deltaX) > touchSlop || Math.abs(deltaY) > touchSlop)) {
                    isDragging = true;
                    android.util.Log.e("TURNING_DEBUG", "onWindowTouch: position drag started");
                }

                // 拖拽移动窗口
                if (isDragging) {
                    // 拖动时刷新显示时间（防止拖动期间自动退出）
                    showTime = System.currentTimeMillis();

                    int newX = windowStartX + (int) deltaX;
                    int newY = windowStartY + (int) deltaY;

                    // 宽松边界限制：允许窗口部分超出屏幕，只需保留 200px 可见区域
                    // 使用 WindowManager 获取真实屏幕尺寸（避免 Service context 返回错误值）
                    android.graphics.Point realSize = new android.graphics.Point();
                    windowManager.getDefaultDisplay().getRealSize(realSize);
                    int screenW = realSize.x;
                    int screenH = realSize.y;
                    int minVisible = 200; // 至少保留 200px 在屏幕内
                    int minX = -(currentWidth - minVisible);
                    int minY = -(currentHeight - minVisible);
                    int maxX = screenW - minVisible;
                    int maxY = screenH - minVisible;
                    newX = Math.max(minX, Math.min(newX, maxX));
                    newY = Math.max(minY, Math.min(newY, maxY));

                    // 调试日志（每 10 帧输出一次避免刷屏）
                    if (System.currentTimeMillis() % 500 < 50) {
                        android.util.Log.e("TURNING_DEBUG", "DRAG: delta=" + (int) deltaX + "," + (int) deltaY +
                                " new=" + newX + "," + newY + " screen=" + screenW + "x" + screenH +
                                " range=[" + minX + "~" + maxX + ", " + minY + "~" + maxY + "]" +
                                " winSize=" + currentWidth + "x" + currentHeight);
                    }

                    // 更新位置
                    layoutParams.x = newX;
                    layoutParams.y = newY;
                    currentWindowX = newX;
                    currentWindowY = newY;

                    try {
                        getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
                    } catch (Exception e) {
                        android.util.Log.e("TURNING_DEBUG", "Failed to move window: " + e.getMessage());
                        AppLog.e(TAG, "Failed to move window", e);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 取消长按检测
                if (longPressHandler != null && longPressRunnable != null) {
                    longPressHandler.removeCallbacks(longPressRunnable);
                }

                if (isDragging) {
                    // 完成拖拽，保存位置
                    saveWindowSettings();
                    isDragging = false;
                    isLongPressing = false;
                } else if (!isLongPressing) {
                    // 短按（非长按、非拖拽）- 处理点击计数
                    long now = System.currentTimeMillis();
                    long touchDuration = now - touchDownTime;

                    // 短按：未达到长按阈值才计入点击（2 秒内抬手算短按）
                    if (touchDuration < LONG_PRESS_TIMEOUT) {
                        // 检查是否在点击超时时间内
                        if (now - lastTapTime < TAP_TIMEOUT) {
                            tapCount++;
                        } else {
                            tapCount = 1;
                        }
                        lastTapTime = now;

                        AppLog.d(TAG, "Tap count: " + tapCount);

                        // 达到5次点击，进入/退出调整大小模式
                        if (tapCount >= TAP_COUNT_FOR_RESIZE) {
                            tapCount = 0;
                            if (isEditMode) {
                                exitEditMode();
                            } else {
                                enterEditMode();
                            }
                        }
                    }
                }

                isLongPressing = false;
                return true;
        }
        return false;
    }

    /**
     * 重置编辑模式自动退出定时器
     */
    private void resetEditModeTimer() {
        if (editModeHandler != null && isEditMode) {
            if (exitEditModeRunnable != null) {
                editModeHandler.removeCallbacks(exitEditModeRunnable);
            }
            exitEditModeRunnable = this::exitEditMode;
            editModeHandler.postDelayed(exitEditModeRunnable, 10000); // 10秒后自动退出
        }
    }

    private void saveWindowSettings() {
        // 防止在持久模式（1x1 隐藏窗口）时保存无效的位置/尺寸
        if (layoutParams != null && layoutParams.width <= 1 && layoutParams.height <= 1) {
            android.util.Log.e("TURNING_DEBUG", "saveWindowSettings: SKIPPED (persistent mode, size=" +
                    layoutParams.width + "x" + layoutParams.height + ")");
            return;
        }

        // 【修复】正在调整大小时不保存（等 ACTION_UP 确认后再保存）
        if (isResizing) {
            android.util.Log.e("TURNING_DEBUG", "saveWindowSettings: SKIPPED (resizing in progress)");
            return;
        }

        android.util.Log.e("TURNING_DEBUG", "saveWindowSettings: isAbsoluteMode=" + isAbsolutePositionMode +
                ", currentPos=" + currentWindowX + "," + currentWindowY +
                ", layoutParams=" + (layoutParams != null ? layoutParams.x + "," + layoutParams.y : "null"));

        int saveX, saveY;

        if (isAbsolutePositionMode && currentWindowX >= 0 && currentWindowY >= 0) {
            // 绝对定位模式，使用保存的位置
            saveX = currentWindowX;
            saveY = currentWindowY;
        } else if (previewContainer != null && layoutParams != null) {
            // 相对定位模式或位置无效，尝试获取当前实际位置
            int[] location = new int[2];
            previewContainer.getLocationOnScreen(location);

            // 检查位置是否有效（窗口必须可见）
            if (previewContainer.getWidth() > 0 && previewContainer.getHeight() > 0) {
                saveX = location[0];
                saveY = location[1];

                // 同时更新内部状态，切换到绝对定位模式
                currentWindowX = saveX;
                currentWindowY = saveY;
                layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
                layoutParams.x = saveX;
                layoutParams.y = saveY;
                isAbsolutePositionMode = true;

                try {
                    getOverlayWindowManager().updateViewLayout(previewContainer, layoutParams);
                } catch (Exception ignored) {
                }

                android.util.Log.e("TURNING_DEBUG", "saveWindowSettings: switched to absolute mode, pos=" +
                        saveX + "," + saveY);
            } else {
                android.util.Log.e("TURNING_DEBUG", "saveWindowSettings: skipped (window not visible)");
                return;
            }
        } else {
            android.util.Log.e("TURNING_DEBUG", "saveWindowSettings: skipped (no container or layoutParams)");
            return;
        }

        // 允许负坐标（窗口可部分超出屏幕）
        // 不再强制非负，保持用户设置的精确位置

        // 【保护】如果当前尺寸是 HUD/仪表盘尺寸，说明悬浮窗被 HUD 模式覆盖了尺寸
        // 此时不应保存（会导致下次启动时窗口变成 HUD 的小尺寸）
        // 但如果用户在非 HUD 模式下主动调整到相同尺寸，应允许保存
        // 【保护】确保保存的尺寸在合理范围内
        if (currentWidth < MIN_PREVIEW_SIZE || currentHeight < MIN_PREVIEW_SIZE) {
            android.util.Log.e("TURNING_DEBUG", "saveWindowSettings: SKIPPED (size too small: " +
                    currentWidth + "x" + currentHeight + ")");
            return;
        }

        // 保存位置和尺寸
        prefs.edit()
                .putInt(PREF_WINDOW_X, saveX)
                .putInt(PREF_WINDOW_Y, saveY)
                .putInt(PREF_WINDOW_WIDTH, currentWidth)
                .putInt(PREF_WINDOW_HEIGHT, currentHeight)
                .apply();

        android.util.Log.e("TURNING_DEBUG", "Window settings saved: x=" + saveX + ", y=" + saveY +
                ", w=" + currentWidth + ", h=" + currentHeight);
        AppLog.d(TAG, "Window settings saved: x=" + saveX + ", y=" + saveY +
                ", w=" + currentWidth + ", h=" + currentHeight);
    }

    /**
     * 重新创建只有预览的相机会话
     */
    private synchronized void recreateCaptureSessionPreviewOnly() {
        if (cameraDevice == null || previewTextureView == null || !previewTextureView.isAvailable()) {
            android.util.Log.e("TURNING_DEBUG", "Cannot recreate preview session: camera not ready");
            return;
        }

        try {
            android.util.Log.e("TURNING_DEBUG", "Recreating camera session (preview-only)...");

            // 先停止当前会话
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                } catch (Exception e) {
                    android.util.Log.w("TURNING_DEBUG", "Error stopping repeating: " + e.getMessage());
                }
                captureSession.close();
                captureSession = null;
            }

            SurfaceTexture texture = previewTextureView.getSurfaceTexture();
            if (texture == null) {
                android.util.Log.e("TURNING_DEBUG", "SurfaceTexture is null!");
                return;
            }

            texture.setDefaultBufferSize(getCameraOutputWidth(), getCameraOutputHeight());
            Surface previewSurface = new Surface(texture);

            android.util.Log.e("TURNING_DEBUG", "Creating preview-only session");

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder builder = cameraDevice
                                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(previewSurface);
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(30, 30));
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        android.util.Log.e("TURNING_DEBUG",
                                "Preview-only session configured, restarting frame sharing");

                        // 重启帧共享（因为会话重建会停止）
                        mainHandler.post(() -> startPreviewFrameSharing());

                    } catch (CameraAccessException e) {
                        android.util.Log.e("TURNING_DEBUG", "Failed to start preview capture: " + e.getMessage(), e);
                        AppLog.e(TAG, "Failed to start preview capture", e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    android.util.Log.e("TURNING_DEBUG", "Preview session configure FAILED");
                    AppLog.e(TAG, "Preview session configure failed");
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            android.util.Log.e("TURNING_DEBUG", "Failed to create preview session: " + e.getMessage(), e);
            AppLog.e(TAG, "Failed to create preview session", e);
        }
    }

    /**
     * 重新创建包含外部 Surface 的相机会话
     * 用于将视频流同时输出到外部 Surface（按需）
     */
    private void recreateCaptureSessionWithExternalSurfaces() {
        if (cameraDevice == null || previewTextureView == null || !previewTextureView.isAvailable()) {
            AppLog.w(TAG, "Camera not ready for external surfaces");
            return;
        }

        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }

            SurfaceTexture texture = previewTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(getCameraOutputWidth(), getCameraOutputHeight());
            Surface previewSurface = new Surface(texture);

            java.util.List<Surface> surfaces = new java.util.ArrayList<>();
            surfaces.add(previewSurface);

            // 添加所有注册的外部 Surface
            synchronized (registeredSurfaces) {
                for (android.view.Surface extSurface : registeredSurfaces) {
                    if (extSurface != null && extSurface.isValid()) {
                        surfaces.add(extSurface);
                        AppLog.d(TAG, "Added external surface to session");
                    }
                }
            }

            AppLog.d(TAG, "Creating capture session with " + surfaces.size() + " surfaces");

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

                        // 添加所有 Surface 作为目标
                        for (Surface s : surfaces) {
                            builder.addTarget(s);
                        }

                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        AppLog.d(TAG, "Capture session with external surfaces started");
                    } catch (CameraAccessException e) {
                        AppLog.e(TAG, "Failed to start session", e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    AppLog.e(TAG, "External surfaces session configure failed");
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Failed to create session with external surfaces", e);
        }
    }
}
