package com.elliot.blindspot;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 补盲应用 Application 类
 * 
 * 双重作用：
 * 1. 进程锚点：创建 1x1 不可见悬浮窗，防止进程被系统杀死
 * 2. 摄像头预热：在进程创建时立即初始化摄像头，服务启动后直接接管
 * 
 * 启动流程：
 * 开机 → 广播触发进程 → Application.onCreate() → 
 *   → 悬浮窗锚点（保住进程）
 *   → 摄像头预览窗口（预热摄像头）
 *   → 启动服务（接管已预热的摄像头）
 * 
 * 优势：
 * - 摄像头从进程创建就开始预热，服务启动后无需等待
 * - 悬浮窗保住进程，即使 startForegroundService() 失败也能重试
 * - 摄像头预热帧在服务接管前已完成跳过
 */
public class BlindspotApp extends Application {
    private static final String TAG = "BlindspotApp";
    private static final String PREFS_NAME = "turning_blind_spot_prefs";
    
    // 摄像头参数（E02 默认，与 TurningBlindSpotService 保持一致）
    private static final int CAMERA_WIDTH = 5120;
    private static final int CAMERA_HEIGHT = 800;
    private static final int WARMUP_FRAMES_TO_SKIP = 5;
    
    private static BlindspotApp instance;
    private View anchorView;
    private WindowManager windowManager;
    private Handler mainHandler;
    
    // 服务启动重试
    private int serviceStartRetries = 0;
    private static final int MAX_SERVICE_START_RETRIES = 5;
    private static final long SERVICE_RETRY_DELAY_MS = 3000;
    
    // ===== 摄像头预热资源（供服务接管） =====
    private TextureView cameraTextureView;
    private FrameLayout cameraContainer;
    private WindowManager.LayoutParams cameraLayoutParams;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private String panoramicCameraId;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private volatile int warmupFramesRemaining = WARMUP_FRAMES_TO_SKIP;
    private volatile boolean cameraReady = false;  // 摄像头完全就绪（预热完成）
    private volatile boolean cameraAdopted = false; // 已被服务接管
    
    public static BlindspotApp getInstance() {
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppLog.init(this);
        mainHandler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        android.util.Log.e("TURNING_DEBUG", "BlindspotApp.onCreate() - process started");
        
        // 检查悬浮窗权限
        boolean hasOverlayPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M 
                || Settings.canDrawOverlays(this);
        
        if (hasOverlayPermission) {
            // 1. 创建悬浮窗锚点（保住进程）
            createAnchorOverlay();
            
            // 2. 创建摄像头预览窗口并开始预热
            initCameraPreview();
        } else {
            android.util.Log.w("TURNING_DEBUG", "BlindspotApp: no overlay permission, skipping init");
        }
        
        // 检查是否需要自动启动服务
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean disclaimerAccepted = prefs.getBoolean("disclaimer_accepted", false);
        boolean autoStart = prefs.getBoolean("auto_start_on_boot", true);
        if (prefs.getBoolean(MainActivity.PREF_DEBUG_FILE_LOGGING, false)) {
            AppLog.startFileLogging(this);
        }
        
        if (disclaimerAccepted && autoStart) {
            // 延迟 1 秒后尝试启动服务（给摄像头预热的时间）
            mainHandler.postDelayed(this::ensureServiceRunning, 1000);
        }
        
    }
    
    // ==================== 悬浮窗锚点 ====================
    
    /**
     * 创建 1x1 不可见悬浮窗作为进程锚点
     */
    private void createAnchorOverlay() {
        try {
            anchorView = new View(this);
            
            int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    1, 1,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            
            params.gravity = Gravity.START | Gravity.TOP;
            params.x = -100;
            params.y = -100;
            params.alpha = 0f;
            
            windowManager.addView(anchorView, params);
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: anchor overlay created");
            
        } catch (Exception e) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: anchor failed: " + e.getMessage());
        }
    }
    
    // ==================== 摄像头预热 ====================
    
    /**
     * 初始化摄像头预览窗口
     * 创建一个与服务中 createPersistentCameraPreview() 相同的 1x1 隐藏窗口，
     * 提前打开摄像头开始预热
     */
    private void initCameraPreview() {
        android.util.Log.e("TURNING_DEBUG", "BlindspotApp: initializing camera preview for warmup");
        
        // 初始化摄像头线程
        cameraThread = new HandlerThread("AppCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        
        // 查找全景摄像头
        findPanoramicCamera();
        
        if (panoramicCameraId == null) {
            android.util.Log.w("TURNING_DEBUG", "BlindspotApp: no camera found, skipping preview");
            return;
        }
        
        // 创建 TextureView
        cameraTextureView = new TextureView(this);
        cameraContainer = new FrameLayout(this);
        cameraContainer.addView(cameraTextureView);
        
        cameraTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                android.util.Log.e("TURNING_DEBUG", "BlindspotApp: TextureView available " + width + "x" + height);
                surface.setDefaultBufferSize(CAMERA_WIDTH, CAMERA_HEIGHT);
                
                // 打开摄像头（预热）
                if (!cameraAdopted) {
                    openCamera();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // 不处理
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                android.util.Log.e("TURNING_DEBUG", "BlindspotApp: TextureView destroyed");
                if (!cameraAdopted) {
                    closeCamera();
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // 预热帧计数
                if (warmupFramesRemaining > 0) {
                    warmupFramesRemaining--;
                    if (warmupFramesRemaining == 0) {
                        cameraReady = true;
                        android.util.Log.e("TURNING_DEBUG", "BlindspotApp: camera warmup complete, ready for service");
                    }
                }
            }
        });
        
        // 创建隐藏窗口（1x1, 完全不可见）
        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        
        cameraLayoutParams = new WindowManager.LayoutParams(
                1, 1,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        
        cameraLayoutParams.gravity = Gravity.START | Gravity.TOP;
        cameraLayoutParams.x = -10;
        cameraLayoutParams.y = -10;
        cameraLayoutParams.alpha = 0f;
        
        try {
            windowManager.addView(cameraContainer, cameraLayoutParams);
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: camera preview window created (1x1 hidden)");
        } catch (Exception e) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: failed to create camera window: " + e.getMessage());
        }
    }
    
    private void findPanoramicCamera() {
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String preferredId = prefs.getString(TurningBlindSpotService.PREF_CAMERA_ID, null);

            if (preferredId != null) {
                for (String id : cameraIds) {
                    if (preferredId.equals(id)) {
                        panoramicCameraId = id;
                        android.util.Log.e("TURNING_DEBUG", "BlindspotApp: using preferred camera: " + id);
                        return;
                    }
                }
                android.util.Log.w("TURNING_DEBUG", "BlindspotApp: preferred cameraId not found: " + preferredId);
            }

            for (String id : cameraIds) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                panoramicCameraId = id;
                android.util.Log.e("TURNING_DEBUG", "BlindspotApp: found camera: " + id);
                break;
            }
        } catch (CameraAccessException e) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: failed to find camera: " + e.getMessage());
        }
    }
    
    private synchronized void openCamera() {
        if (panoramicCameraId == null || cameraAdopted) return;
        if (cameraDevice != null) {
            android.util.Log.w("TURNING_DEBUG", "BlindspotApp: camera already open");
            return;
        }
        
        try {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: opening camera " + panoramicCameraId);
            cameraManager.openCamera(panoramicCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    android.util.Log.e("TURNING_DEBUG", "BlindspotApp: camera opened successfully");
                    if (cameraAdopted) {
                        // 已被服务接管，关闭这个
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                    warmupFramesRemaining = WARMUP_FRAMES_TO_SKIP;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    android.util.Log.w("TURNING_DEBUG", "BlindspotApp: camera disconnected");
                    camera.close();
                    if (!cameraAdopted) {
                        cameraDevice = null;
                        cameraReady = false;
                    }
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    android.util.Log.e("TURNING_DEBUG", "BlindspotApp: camera error: " + error);
                    camera.close();
                    if (!cameraAdopted) {
                        cameraDevice = null;
                        cameraReady = false;
                    }
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: openCamera failed: " + e.getMessage());
        }
    }
    
    private void createCaptureSession() {
        if (cameraDevice == null || cameraAdopted) return;
        if (cameraTextureView == null || !cameraTextureView.isAvailable()) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: TextureView not available for session");
            return;
        }
        
        try {
            Surface cameraSurface = new Surface(cameraTextureView.getSurfaceTexture());
            
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(cameraSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(30, 30));
            
            cameraDevice.createCaptureSession(java.util.Arrays.asList(cameraSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraAdopted) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                android.util.Log.e("TURNING_DEBUG", 
                                        "BlindspotApp: camera session started @ 30fps (warming up)");
                            } catch (CameraAccessException e) {
                                android.util.Log.e("TURNING_DEBUG", 
                                        "BlindspotApp: setRepeatingRequest failed: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: session config failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: createSession failed: " + e.getMessage());
        }
    }
    
    private void closeCamera() {
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        cameraReady = false;
    }
    
    // ==================== 资源接管接口 ====================
    
    /**
     * 服务调用此方法接管所有摄像头资源
     * 接管后 BlindspotApp 不再管理这些资源
     * 
     * @return CameraResources 对象，包含所有预热好的摄像头资源；如果摄像头未初始化返回 null
     */
    public synchronized CameraResources adoptCameraResources() {
        if (cameraTextureView == null || cameraDevice == null) {
            android.util.Log.w("TURNING_DEBUG", "BlindspotApp: no camera resources to adopt " +
                    "(textureView=" + (cameraTextureView != null) + ", device=" + (cameraDevice != null) + ")");
            return null;
        }
        
        cameraAdopted = true;
        
        CameraResources resources = new CameraResources();
        resources.textureView = cameraTextureView;
        resources.container = cameraContainer;
        resources.layoutParams = cameraLayoutParams;
        resources.cameraDevice = cameraDevice;
        resources.captureSession = captureSession;
        resources.panoramicCameraId = panoramicCameraId;
        resources.cameraThread = cameraThread;
        resources.cameraHandler = cameraHandler;
        resources.isWarmupComplete = cameraReady;
        resources.warmupFramesRemaining = warmupFramesRemaining;
        
        android.util.Log.e("TURNING_DEBUG", "BlindspotApp: camera resources adopted by service " +
                "(ready=" + cameraReady + ", warmup remaining=" + warmupFramesRemaining + ")");
        
        // 清空引用（不再管理）
        cameraTextureView = null;
        cameraContainer = null;
        cameraLayoutParams = null;
        cameraDevice = null;
        captureSession = null;
        panoramicCameraId = null;
        cameraThread = null;
        cameraHandler = null;
        
        return resources;
    }
    
    /**
     * 检查摄像头是否已预热就绪
     */
    public boolean isCameraReady() {
        return cameraReady && !cameraAdopted;
    }
    
    /**
     * 检查摄像头是否已被服务接管
     */
    public boolean isCameraAdopted() {
        return cameraAdopted;
    }
    
    /**
     * 摄像头资源封装类
     * 服务通过 adoptCameraResources() 获取此对象后直接使用
     */
    public static class CameraResources {
        public TextureView textureView;
        public FrameLayout container;
        public WindowManager.LayoutParams layoutParams;
        public CameraDevice cameraDevice;
        public CameraCaptureSession captureSession;
        public String panoramicCameraId;
        public HandlerThread cameraThread;
        public Handler cameraHandler;
        public boolean isWarmupComplete;
        public int warmupFramesRemaining;
    }
    
    // ==================== 服务启动 ====================
    
    /**
     * 确保服务正在运行，如果没有则启动（带重试机制）
     */
    public void ensureServiceRunning() {
        if (TurningBlindSpotService.isRunning()) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: service already running");
            serviceStartRetries = 0;
            return;
        }
        
        if (serviceStartRetries >= MAX_SERVICE_START_RETRIES) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: max retries reached, giving up");
            return;
        }
        
        serviceStartRetries++;
        android.util.Log.e("TURNING_DEBUG", "BlindspotApp: starting service (attempt " + 
                serviceStartRetries + "/" + MAX_SERVICE_START_RETRIES + ")");
        
        try {
            Intent serviceIntent = new Intent(this, TurningBlindSpotService.class);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            // 验证服务是否成功启动
            mainHandler.postDelayed(() -> {
                if (!TurningBlindSpotService.isRunning()) {
                    android.util.Log.w("TURNING_DEBUG", "BlindspotApp: service not running, retrying...");
                    ensureServiceRunning();
                } else {
                    android.util.Log.e("TURNING_DEBUG", "BlindspotApp: service confirmed running");
                    serviceStartRetries = 0;
                }
            }, SERVICE_RETRY_DELAY_MS);
            
        } catch (Exception e) {
            android.util.Log.e("TURNING_DEBUG", "BlindspotApp: start failed: " + e.getMessage());
            mainHandler.postDelayed(this::ensureServiceRunning, SERVICE_RETRY_DELAY_MS);
        }
    }
}
