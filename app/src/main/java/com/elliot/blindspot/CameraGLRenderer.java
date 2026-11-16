package com.elliot.blindspot;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL 统一相机渲染管道（高性能版本）
 * 
 * 使用 EGL/GLES 从相机 SurfaceTexture 渲染到多个目标：
 * - TextureView（预览）- 同时显示裁切后的补盲画面
 * 
 * 所有渲染在 GPU 完成，避免 CPU 瓶颈：
 * - 零 getBitmap() 调用
 * - 零 CPU Bitmap 操作
 * - 象限裁切在 GPU Shader 完成
 * - 预览和录制共享同一个 GPU 纹理，零拷贝
 * 
 * 性能对比：
 * - 旧方案：getBitmap + Canvas 裁切 ≈ 20ms/帧 → 最多 30fps
 * - GPU 方案：GL Shader 裁切 ≈ 1ms/帧 → 轻松 60fps
 */
public class CameraGLRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "CameraGLRenderer";

    // 性能目标
    private static final int TARGET_FPS = 60;
    private static final long FRAME_INTERVAL_NS = 1000000000L / TARGET_FPS;

    // 相机尺寸
    private final int cameraWidth;
    private final int cameraHeight;
    private final int frameShareWidth;
    private final int frameShareHeight;
    private final int frameShareBytes;

    // EGL 组件
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;

    // 预览 EGL Surface
    private EGLSurface previewEglSurface = EGL14.EGL_NO_SURFACE;
    private Surface previewSurface;

    // 帧共享 PBuffer（独立离屏表面，不依赖 TextureView 尺寸）
    private EGLSurface frameShareEglSurface = EGL14.EGL_NO_SURFACE;

    private EGLSurface stillCaptureEglSurface = EGL14.EGL_NO_SURFACE;
    private int stillCaptureWidth = 0;
    private int stillCaptureHeight = 0;
    private Bitmap stillCaptureBitmap;
    private ByteBuffer stillCaptureBuffer;
    private int stillCaptureBytes = 0;

    // 预览表面实际尺寸（TextureView 窗口大小，渲染时使用）
    private volatile int previewSurfaceWidth = 1280;
    private volatile int previewSurfaceHeight = 800;

    // 相机 SurfaceTexture
    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraSurface;
    private int cameraTextureId = -1;
    private float[] cameraTransformMatrix = new float[16];

    // OpenGL 着色器
    private int programId = -1;
    private int positionHandle;
    private int texCoordHandle;
    private int mvpMatrixHandle;
    private int stMatrixHandle;
    private int textureHandle;
    private int cropRectHandle; // 裁切区域 uniform

    // 顶点数据
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // MVP 矩阵
    private float[] mvpMatrix = new float[16];
    private float[] identityMatrix = new float[16];

    // ==================== 象限裁切 ====================
    /** 象限常量 */
    public static final int CROP_NONE = 0;        // 全景（不裁切）
    public static final int CROP_TOP_LEFT = 1;     // 前视角
    public static final int CROP_TOP_RIGHT = 2;    // 后视角
    public static final int CROP_BOTTOM_LEFT = 3;  // 左视角（补盲-左转）
    public static final int CROP_BOTTOM_RIGHT = 4; // 右视角（补盲-右转）

    // 预览裁切区域 (left, top, right, bottom) 纹理坐标 0~1
    private volatile float[] currentCropRect = {0f, 0f, 1f, 1f}; // 默认全景
    private volatile int previewRotationDeg = 0;

    // 预定义象限裁切区域
    private static final float[][] QUADRANT_CROPS = {
        {0.0f, 0.0f, 1.0f, 1.0f}, // CROP_NONE: 全景
        {0.0f, 0.0f, 0.5f, 0.5f}, // CROP_TOP_LEFT: 前
        {0.5f, 0.0f, 1.0f, 0.5f}, // CROP_TOP_RIGHT: 后
        {0.0f, 0.5f, 0.5f, 1.0f}, // CROP_BOTTOM_LEFT: 左
        {0.5f, 0.5f, 1.0f, 1.0f}, // CROP_BOTTOM_RIGHT: 右
    };

    // 渲染线程
    private HandlerThread renderThread;
    private Handler renderHandler;

    private volatile boolean isRunning = false;

    // 性能监控
    private long totalRenderTime = 0;
    private int renderSampleCount = 0;
    private float currentRenderFps = 0;

    // 帧回调
    private volatile boolean frameAvailable = false;
    private final Object frameSyncObject = new Object();

    // 帧共享回调（供 HUD/EVCam 使用，低频率 readPixels）
    private volatile FrameShareCallback frameShareCallback;
    private int frameShareCounter = 0;
    private static final int FRAME_SHARE_INTERVAL = 1; // 每帧共享（补盲帧率最大化）
    // 帧共享开关（默认开启，getBitmap 已禁用，readPixels 是唯一帧源）
    private volatile boolean frameShareEnabled = true;
    private Bitmap frameShareBitmap;
    private ByteBuffer frameShareBuffer;

    private volatile StillCaptureRequest pendingStillCapture;

    // 回调接口
    public interface Callback {
        void onSurfaceTextureReady(SurfaceTexture surfaceTexture);
        void onError(String error);
    }

    /** 帧共享回调（低频率，用于 HUD/EVCam） */
    public interface FrameShareCallback {
        void onFrameAvailable(Bitmap frame);
    }

    public interface StillCaptureCallback {
        void onCaptured(Bitmap frame);

        void onError(String error);
    }

    private Callback callback;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== 着色器 ====================

    // 顶点着色器（支持裁切）
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "uniform vec4 uCropRect;\n" + // left, top, right, bottom (0~1)
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vec2 uv = vec2(aTexCoord.x, 1.0 - aTexCoord.y);\n" +
            "    vec2 cropped = mix(uCropRect.xy, uCropRect.zw, uv);\n" +
            "    cropped = vec2(cropped.x, 1.0 - cropped.y);\n" +
            "    vTexCoord = (uSTMatrix * vec4(cropped, 0.0, 1.0)).xy;\n" +
            "}\n";

    // 片段着色器（OES 纹理）
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    // 全屏四边形顶点
    private static final float[] VERTEX_DATA = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
    };

    // 纹理坐标
    private static final float[] TEX_COORD_DATA = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    // ==================== 构造与配置 ====================

    public CameraGLRenderer(int cameraWidth, int cameraHeight) {
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        int targetW = 1920;
        int w = Math.max(320, Math.min(targetW, cameraWidth));
        int h = Math.max(180, Math.round(w * (cameraHeight / (float) cameraWidth)));
        if ((h & 1) == 1) h++;
        frameShareWidth = w;
        frameShareHeight = h;
        frameShareBytes = frameShareWidth * frameShareHeight * 4;

        Matrix.setIdentityM(identityMatrix, 0);
        Matrix.setIdentityM(mvpMatrix, 0);

        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTEX_DATA).position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORD_DATA.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(TEX_COORD_DATA).position(0);
    }

    public void setCallback(Callback callback) { this.callback = callback; }
    public void setFrameShareCallback(FrameShareCallback cb) { this.frameShareCallback = cb; }

    /**
     * 设置裁切象限（实时切换，线程安全）
     */
    public void setCropQuadrant(int quadrant) {
        if (quadrant >= 0 && quadrant < QUADRANT_CROPS.length) {
            currentCropRect = QUADRANT_CROPS[quadrant];
            Log.d(TAG, "Crop quadrant set to " + quadrant + ": [" +
                    currentCropRect[0] + "," + currentCropRect[1] + "," +
                    currentCropRect[2] + "," + currentCropRect[3] + "]");
        }
    }

    /**
     * 设置自定义预览裁切区域
     * @param left 左边界 (0~1)
     * @param top 上边界 (0~1)
     * @param right 右边界 (0~1)
     * @param bottom 下边界 (0~1)
     */
    public void setCropRect(float left, float top, float right, float bottom) {
        currentCropRect = new float[]{left, top, right, bottom};
    }

    public void setPreviewRotationDegrees(int deg) {
        previewRotationDeg = normalizeRotationDeg(deg);
    }

    private static int normalizeRotationDeg(int deg) {
        if (deg == 90 || deg == 180 || deg == 270) return deg;
        return 0;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动渲染器
     */
    public void start(TextureView textureView) {
        if (isRunning) {
            Log.w(TAG, "Renderer already running");
            return;
        }

        Log.e(TAG, "Starting CameraGLRenderer: " + cameraWidth + "x" + cameraHeight +
                " @ " + TARGET_FPS + "fps (GPU pipeline)");

        renderThread = new HandlerThread("CameraGLRenderer", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        renderHandler.post(() -> {
            try {
                initEGL();
                initOpenGL();
                createCameraSurfaceTexture();

                if (textureView != null && textureView.isAvailable()) {
                    setPreviewSurface(new Surface(textureView.getSurfaceTexture()));
                }

                isRunning = true;
                startRenderLoop();

                if (callback != null) {
                    mainHandler.post(() -> callback.onSurfaceTextureReady(cameraSurfaceTexture));
                }

                Log.e(TAG, "CameraGLRenderer started successfully (GPU pipeline active)");

            } catch (Exception e) {
                Log.e(TAG, "Failed to start renderer: " + e.getMessage(), e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("Failed to start: " + e.getMessage()));
                }
            }
        });
    }

    public void stop() {
        Log.e(TAG, "Stopping CameraGLRenderer");
        isRunning = false;
        frameShareCallback = null; // 立即停止帧共享回调，防止回调访问已释放资源

        // (readPixels 在 GL 主线程同步执行，无需额外线程清理)

        if (renderHandler != null) {
            try {
                renderHandler.post(this::cleanup);
                renderThread.quitSafely();
                renderThread.join(2000);
            } catch (Exception e) {
                Log.w(TAG, "Error stopping render thread: " + e.getMessage());
            }
            renderThread = null;
            renderHandler = null;
        }

        try {
            if (frameShareBitmap != null && !frameShareBitmap.isRecycled()) {
                frameShareBitmap.recycle();
                frameShareBitmap = null;
            }
        } catch (Exception ignored) {}
    }

    // ==================== EGL/OpenGL 初始化 ====================

    private void initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Unable to initialize EGL14");
        }

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("Unable to choose EGL config");
        }
        eglConfig = configs[0];

        int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGL context");
        }

        int[] pbufferAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
        EGLSurface pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        if (!EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            throw new RuntimeException("Unable to make EGL current");
        }

        Log.d(TAG, "EGL initialized");
    }

    private void initOpenGL() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        cameraTextureId = textures[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        programId = GLES20.glCreateProgram();
        GLES20.glAttachShader(programId, vertexShader);
        GLES20.glAttachShader(programId, fragmentShader);
        GLES20.glLinkProgram(programId);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            throw new RuntimeException("Program link failed: " + GLES20.glGetProgramInfoLog(programId));
        }

        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix");
        stMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix");
        textureHandle = GLES20.glGetUniformLocation(programId, "sTexture");
        cropRectHandle = GLES20.glGetUniformLocation(programId, "uCropRect");

        // 创建帧共享 PBuffer（独立离屏表面，不受 TextureView 尺寸影响）
        int[] pbufAttribs = { EGL14.EGL_WIDTH, frameShareWidth, EGL14.EGL_HEIGHT, frameShareHeight, EGL14.EGL_NONE };
        frameShareEglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufAttribs, 0);
        if (frameShareEglSurface == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "Failed to create frame share PBuffer, falling back to preview surface");
        } else {
            Log.d(TAG, "Frame share PBuffer created: " + frameShareWidth + "x" + frameShareHeight);
        }

        Log.d(TAG, "OpenGL initialized, texture ID: " + cameraTextureId +
                ", cropRect uniform: " + cropRectHandle +
                ", readPixels: sync " + frameShareWidth + "x" + frameShareHeight);
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + error);
        }
        return shader;
    }

    private void createCameraSurfaceTexture() {
        cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
        cameraSurfaceTexture.setDefaultBufferSize(cameraWidth, cameraHeight);
        cameraSurfaceTexture.setOnFrameAvailableListener(this);
        cameraSurface = new Surface(cameraSurfaceTexture);
        Log.d(TAG, "Camera SurfaceTexture created: " + cameraWidth + "x" + cameraHeight);
    }

    public Surface getCameraSurface() { return cameraSurface; }
    public SurfaceTexture getCameraSurfaceTexture() { return cameraSurfaceTexture; }

    public void setPreviewSurface(Surface surface) {
        if (renderHandler != null) {
            renderHandler.post(() -> {
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                    previewEglSurface = EGL14.EGL_NO_SURFACE;
                }
                previewSurface = surface;
                if (surface != null) {
                    int[] surfaceAttribs = { EGL14.EGL_NONE };
                    previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0);
                    Log.d(TAG, previewEglSurface != EGL14.EGL_NO_SURFACE ?
                            "Preview EGL surface created" : "Failed to create preview EGL surface");
                }
            });
        }
    }

    // ==================== 渲染循环 ====================

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (frameSyncObject) {
            frameAvailable = true;
            frameSyncObject.notifyAll();
        }
    }

    private void startRenderLoop() {
        Runnable renderRunnable = new Runnable() {
            private int frameCount = 0;
            private long lastLogTime = System.currentTimeMillis();

            @Override
            public void run() {
                if (!isRunning) return;

                long frameStartTime = System.nanoTime();

                // 等待新帧
                boolean hasNewFrame = false;
                synchronized (frameSyncObject) {
                    if (frameAvailable) {
                        hasNewFrame = true;
                        frameAvailable = false;
                    } else {
                        try {
                            frameSyncObject.wait(8);
                            if (frameAvailable) {
                                hasNewFrame = true;
                                frameAvailable = false;
                            }
                        } catch (InterruptedException e) { /* continue */ }
                    }
                }

                if (!isRunning) return;

                // 更新相机纹理
                if (hasNewFrame) {
                    try {
                        cameraSurfaceTexture.updateTexImage();
                        cameraSurfaceTexture.getTransformMatrix(cameraTransformMatrix);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to update texture: " + e.getMessage());
                    }
                }

                // 获取当前裁切区域（线程安全读取，预览和录制独立）
                float[] previewCrop = currentCropRect;

                // 帧共享（GL readPixels，每帧新相机数据都共享）
                if (frameShareEnabled && frameShareCallback != null && hasNewFrame) {
                    frameShareCounter++;
                    if (frameShareCounter >= FRAME_SHARE_INTERVAL) {
                        frameShareCounter = 0;
                        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                            captureFullPanoramicFrame();
                        }
                    }
                }

                StillCaptureRequest req = pendingStillCapture;
                if (req != null && hasNewFrame) {
                    pendingStillCapture = null;
                    captureStill(req);
                }

                // 预览：渲染到 TextureView 实际尺寸（不再固定 1280×800）
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    int rot = previewRotationDeg;
                    if (rot == 0) {
                        renderToSurface(previewEglSurface, previewSurfaceWidth, previewSurfaceHeight, identityMatrix, previewCrop);
                    } else {
                        Matrix.setIdentityM(mvpMatrix, 0);
                        Matrix.rotateM(mvpMatrix, 0, -rot, 0, 0, 1);
                        renderToSurface(previewEglSurface, previewSurfaceWidth, previewSurfaceHeight, mvpMatrix, previewCrop);
                    }
                    EGL14.eglSwapBuffers(eglDisplay, previewEglSurface);
                }

                // 帧率统计
                frameCount++;
                long nowMs = System.currentTimeMillis();
                long renderTimeNs = System.nanoTime() - frameStartTime;
                totalRenderTime += renderTimeNs;
                renderSampleCount++;

                if (nowMs - lastLogTime >= 3000) {
                    float renderFps = frameCount / 3.0f;
                    float avgMs = (totalRenderTime / renderSampleCount) / 1000000.0f;
                    currentRenderFps = renderFps;

                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("GPU Render: %.1f fps (avg %.2fms)", renderFps, avgMs));
                    sb.append(String.format(", preview=[%.2f,%.2f,%.2f,%.2f]", previewCrop[0], previewCrop[1], previewCrop[2], previewCrop[3]));

                    Log.e(TAG, sb.toString());
                    frameCount = 0;
                    totalRenderTime = 0;
                    renderSampleCount = 0;
                    lastLogTime = nowMs;
                }

                // 调度下一帧
                if (isRunning && renderHandler != null) {
                    long elapsed = System.nanoTime() - frameStartTime;
                    long delayNs = FRAME_INTERVAL_NS - elapsed;
                    if (delayNs > 1000000) {
                        renderHandler.postDelayed(this, delayNs / 1000000);
                    } else {
                        renderHandler.post(this);
                    }
                }
            }
        };

        renderHandler.post(renderRunnable);
    }

    /**
     * 渲染到指定 Surface（带象限裁切）
     */
    private void renderToSurface(EGLSurface surface, int width, int height, float[] mvp, float[] crop) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            return;
        }

        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(programId);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(textureHandle, 0);

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, cameraTransformMatrix, 0);

        GLES20.glUniform4f(cropRectHandle, crop[0], crop[1], crop[2], crop[3]);

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
    }

    // Y 翻转 MVP 矩阵（在 GPU 上翻转，避免 CPU 端 Canvas 翻转）
    private float[] flipYMvpMatrix = new float[16];

    /**
     * 捕获全景帧（CROP_NONE）供进程内使用（校准/布局编辑等）
     * 在 preview swap 之前调用！渲染全景到 back buffer → readPixels → 下一步 preview 渲染会覆盖
     * 
     * GPU 端 Y 翻转（MVP 矩阵 scaleY=-1），省去 CPU Canvas 翻转
     */
    private void captureFullPanoramicFrame() {
        try {
            // 复用 Bitmap 和 Buffer
            if (frameShareBitmap == null || frameShareBitmap.isRecycled() ||
                    frameShareBitmap.getWidth() != frameShareWidth || frameShareBitmap.getHeight() != frameShareHeight) {
                if (frameShareBitmap != null && !frameShareBitmap.isRecycled()) frameShareBitmap.recycle();
                frameShareBitmap = Bitmap.createBitmap(frameShareWidth, frameShareHeight, Bitmap.Config.ARGB_8888);
                frameShareBuffer = ByteBuffer.allocateDirect(frameShareBytes).order(ByteOrder.nativeOrder());
                // 初始化 Y 翻转 MVP（glReadPixels 读取底→顶，用 scaleY=-1 预翻转）
                Matrix.setIdentityM(flipYMvpMatrix, 0);
                Matrix.scaleM(flipYMvpMatrix, 0, 1, -1, 1);
            }

            // 使用独立 PBuffer 渲染帧共享（不依赖 TextureView 尺寸）
            EGLSurface targetSurface = (frameShareEglSurface != EGL14.EGL_NO_SURFACE)
                    ? frameShareEglSurface : previewEglSurface;
            if (targetSurface != EGL14.EGL_NO_SURFACE) {
                // 渲染全景（CROP_NONE）+ Y翻转（readPixels 读底→顶，flipY 补偿使 Bitmap 正向）
                float[] fullCrop = QUADRANT_CROPS[CROP_NONE]; // {0,0,1,1}
                renderToSurface(targetSurface, frameShareWidth, frameShareHeight, flipYMvpMatrix, fullCrop);

                // readPixels 直接得到正确方向的帧（GPU 已翻转）
                GLES20.glReadPixels(0, 0, frameShareWidth, frameShareHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frameShareBuffer);
                frameShareBuffer.rewind();
                frameShareBitmap.copyPixelsFromBuffer(frameShareBuffer);
                frameShareBuffer.rewind();

                if (frameShareCallback != null) {
                    frameShareCallback.onFrameAvailable(frameShareBitmap);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Full panoramic frame capture failed: " + e.getMessage());
        }
    }

    public void requestStillCapture(float[] cropRect, int maxWidth, StillCaptureCallback cb) {
        if (cropRect == null || cropRect.length < 4) {
            if (cb != null) cb.onError("cropRect invalid");
            return;
        }
        if (renderHandler == null) {
            if (cb != null) cb.onError("render thread not ready");
            return;
        }
        renderHandler.post(() -> {
            if (!isRunning || eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) {
                if (cb != null) cb.onError("gl not ready");
                return;
            }
            pendingStillCapture = new StillCaptureRequest(clampCrop01(cropRect), maxWidth, cb);
        });
    }

    private void captureStill(StillCaptureRequest req) {
        try {
            float[] crop = req.cropRect;
            int maxWidth = Math.max(320, req.maxWidth);
            float cropW = Math.max(0.01f, crop[2] - crop[0]);
            float cropH = Math.max(0.01f, crop[3] - crop[1]);
            float aspect = (cameraWidth * cropW) / (cameraHeight * cropH);
            int outW = Math.min(maxWidth, cameraWidth);
            outW = Math.max(320, outW);
            int outH = Math.round(outW / aspect);
            outH = Math.max(180, outH);
            if ((outW & 1) == 1) outW++;
            if ((outH & 1) == 1) outH++;

            ensureStillCaptureSurface(outW, outH);
            if (stillCaptureEglSurface == EGL14.EGL_NO_SURFACE) {
                if (req.cb != null) req.cb.onError("pbuffer create failed");
                return;
            }

            if (flipYMvpMatrix[0] == 0f && flipYMvpMatrix[5] == 0f && flipYMvpMatrix[10] == 0f
                    && flipYMvpMatrix[15] == 0f) {
                Matrix.setIdentityM(flipYMvpMatrix, 0);
                Matrix.scaleM(flipYMvpMatrix, 0, 1, -1, 1);
            }

            renderToSurface(stillCaptureEglSurface, outW, outH, flipYMvpMatrix, crop);
            ensureStillCaptureBuffers(outW, outH);
            if (stillCaptureBuffer == null || stillCaptureBitmap == null) {
                if (req.cb != null) req.cb.onError("buffer alloc failed");
                return;
            }
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, stillCaptureBuffer);
            stillCaptureBuffer.rewind();
            stillCaptureBitmap.copyPixelsFromBuffer(stillCaptureBuffer);
            stillCaptureBuffer.rewind();
            if (req.cb != null) req.cb.onCaptured(stillCaptureBitmap);
        } catch (Exception e) {
            if (req.cb != null) req.cb.onError(e.getMessage());
        }
    }

    private void ensureStillCaptureBuffers(int w, int h) {
        int bytes = w * h * 4;
        if (stillCaptureBitmap == null || stillCaptureBitmap.isRecycled()
                || stillCaptureBitmap.getWidth() != w || stillCaptureBitmap.getHeight() != h) {
            if (stillCaptureBitmap != null && !stillCaptureBitmap.isRecycled()) stillCaptureBitmap.recycle();
            stillCaptureBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        if (stillCaptureBuffer == null || stillCaptureBytes != bytes) {
            stillCaptureBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            stillCaptureBytes = bytes;
        }
    }

    private void ensureStillCaptureSurface(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (stillCaptureEglSurface != EGL14.EGL_NO_SURFACE && w == stillCaptureWidth && h == stillCaptureHeight) return;
        if (stillCaptureEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, stillCaptureEglSurface);
            stillCaptureEglSurface = EGL14.EGL_NO_SURFACE;
        }
        int[] pbufferAttribs = {
                EGL14.EGL_WIDTH, w,
                EGL14.EGL_HEIGHT, h,
                EGL14.EGL_NONE
        };
        stillCaptureEglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        stillCaptureWidth = w;
        stillCaptureHeight = h;
    }

    private static float[] clampCrop01(float[] rect) {
        float l = clamp01(rect[0]);
        float t = clamp01(rect[1]);
        float r = clamp01(rect[2]);
        float b = clamp01(rect[3]);
        float cl = Math.min(l, r);
        float cr = Math.max(l, r);
        float ct = Math.min(t, b);
        float cb = Math.max(t, b);
        if (cr - cl < 0.01f) cr = clamp01(cl + 0.01f);
        if (cb - ct < 0.01f) cb = clamp01(ct + 0.01f);
        return new float[] { cl, ct, cr, cb };
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static final class StillCaptureRequest {
        final float[] cropRect;
        final int maxWidth;
        final StillCaptureCallback cb;

        StillCaptureRequest(float[] cropRect, int maxWidth, StillCaptureCallback cb) {
            this.cropRect = cropRect;
            this.maxWidth = maxWidth;
            this.cb = cb;
        }
    }

    private void cleanup() {
        if (stillCaptureEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, stillCaptureEglSurface);
            stillCaptureEglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (stillCaptureBitmap != null && !stillCaptureBitmap.isRecycled()) {
            stillCaptureBitmap.recycle();
            stillCaptureBitmap = null;
        }
        stillCaptureBuffer = null;
        stillCaptureBytes = 0;
        if (frameShareEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, frameShareEglSurface);
            frameShareEglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
            previewEglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (cameraSurface != null) { cameraSurface.release(); cameraSurface = null; }
        if (cameraSurfaceTexture != null) { cameraSurfaceTexture.release(); cameraSurfaceTexture = null; }
        if (cameraTextureId != -1) {
            int[] tex = { cameraTextureId };
            GLES20.glDeleteTextures(1, tex, 0);
            cameraTextureId = -1;
        }
        if (programId != -1) { GLES20.glDeleteProgram(programId); programId = -1; }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglContext != EGL14.EGL_NO_CONTEXT) { EGL14.eglDestroyContext(eglDisplay, eglContext); }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
        }
        Log.d(TAG, "CameraGLRenderer cleaned up");
    }

    // ==================== 状态查询 ====================

    public boolean isRunning() { return isRunning; }
    public float getCurrentRenderFps() { return currentRenderFps; }
    public int getTargetFps() { return TARGET_FPS; }

    /** 更新预览表面实际尺寸（TextureView 窗口大小变化时调用） */
    public void setPreviewSurfaceSize(int width, int height) {
        if (width > 0 && height > 0) {
            this.previewSurfaceWidth = width;
            this.previewSurfaceHeight = height;
        }
    }

    /** 启用/禁用 readPixels 帧共享 */
    public void setFrameShareEnabled(boolean enabled) {
        this.frameShareEnabled = enabled;
        Log.d(TAG, "Frame share (readPixels): " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public void setPreviewCrop(int quadrant) {
        setCropQuadrant(quadrant);
    }
}
