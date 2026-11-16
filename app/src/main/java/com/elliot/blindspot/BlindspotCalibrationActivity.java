package com.elliot.blindspot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 补盲视角校准 Activity
 * 在完整鱼眼画面上拖动矩形框选择补盲显示区域
 * 
 * 存储参数（百分比，相对于当前 LEFT/RIGHT 基础区域）：
 *   left_crop_x, left_crop_y, left_crop_w, left_crop_h (0-100 百分比)
 *   right_crop_x, right_crop_y, right_crop_w, right_crop_h
 * 默认值：x=0, y=0, w=100, h=100（完整基础区域）
 */
public class BlindspotCalibrationActivity extends Activity {
    private static final String TAG = "BlindspotCalibration";
    private static final String PREFS_NAME = "turning_blind_spot_prefs";

    private volatile int previewBaseWidth = 0;
    private volatile int previewBaseHeight = 0;

    // UI
    private ImageView imageFull;
    private View cropOverlay;
    private ImageView previewResult;
    private TextView tvHint;
    private Button btnLeft, btnRight;

    // 状态
    private boolean isLeftView = true;
    
    // 裁切框参数（百分比 0-100，相对于象限）
    private float cropX = 0;      // 左上角 X %
    private float cropY = 0;      // 左上角 Y %
    private float cropW = 100;    // 宽度 %
    private float cropH = 100;    // 高度 %

    // 触摸拖动
    private static final int DRAG_NONE = 0;
    private static final int DRAG_MOVE = 1;    // 整体移动
    private static final int DRAG_RESIZE = 2;  // 右下角缩放
    private int dragMode = DRAG_NONE;
    private float touchStartX, touchStartY;
    private float dragStartCropX, dragStartCropY, dragStartCropW, dragStartCropH;

    // 绘制
    private Paint dimPaint, borderPaint, handlePaint, textPaint;

    // 视图坐标映射
    private RectF imageRect = new RectF(); // 图片在 ImageView 中的实际区域

    // 实时刷新
    private Handler mainHandler;
    private Runnable refreshRunnable;
    private static final int REFRESH_INTERVAL_MS = 50;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blindspot_calibration);

        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initPaints();
        initViews();
        loadSettings(true);
        startPreviewRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreviewRefresh();
        // 清理所有 Handler 回调防止泄漏
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onBackPressed() {
        saveSettings();
        super.onBackPressed();
    }

    private void initPaints() {
        dimPaint = new Paint();
        dimPaint.setColor(Color.argb(160, 0, 0, 0));
        dimPaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint();
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);

        handlePaint = new Paint();
        handlePaint.setColor(Color.argb(200, 79, 195, 247)); // 蓝色
        handlePaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);
    }

    private void initViews() {
        imageFull = findViewById(R.id.image_full);
        cropOverlay = findViewById(R.id.crop_overlay);
        previewResult = findViewById(R.id.preview_result);
        tvHint = findViewById(R.id.tv_hint);
        btnLeft = findViewById(R.id.btn_left);
        btnRight = findViewById(R.id.btn_right);

        findViewById(R.id.btn_back).setOnClickListener(v -> { saveSettings(); finish(); });
        btnLeft.setOnClickListener(v -> switchView(true));
        btnRight.setOnClickListener(v -> switchView(false));
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            cropX = 0; cropY = 0; cropW = 100; cropH = 100;
            cropOverlay.invalidate();
            Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            saveSettings();
            Toast.makeText(this, (isLeftView ? "左" : "右") + "视角已保存", Toast.LENGTH_SHORT).show();
        });

        // 裁切框绘制和触摸
        cropOverlay.setOnTouchListener(this::onCropTouch);
        cropOverlay.setWillNotDraw(false);
        // 自定义绘制
        cropOverlay.setBackground(null);
        cropOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            calculateImageRect();
        });
    }

    /**
     * 计算图片在 ImageView 中的实际显示区域（fitCenter）
     */
    private void calculateImageRect() {
        int viewW = imageFull.getWidth();
        int viewH = imageFull.getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        int imgW = previewBaseWidth;
        int imgH = previewBaseHeight;
        if (imgW <= 0 || imgH <= 0) return;

        float scaleX = (float) viewW / imgW;
        float scaleY = (float) viewH / imgH;
        float scale = Math.min(scaleX, scaleY);

        float scaledW = imgW * scale;
        float scaledH = imgH * scale;
        float left = (viewW - scaledW) / 2f;
        float top = (viewH - scaledH) / 2f;

        imageRect.set(left, top, left + scaledW, top + scaledH);
    }

    /**
     * 裁切框触摸处理
     */
    private boolean onCropTouch(View v, MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = x;
                touchStartY = y;
                dragStartCropX = cropX;
                dragStartCropY = cropY;
                dragStartCropW = cropW;
                dragStartCropH = cropH;

                // 判断是否在右下角手柄上（缩放）
                RectF cropRect = getCropRectInView();
                float handleSize = 60;
                if (x > cropRect.right - handleSize && y > cropRect.bottom - handleSize
                        && x < cropRect.right + handleSize && y < cropRect.bottom + handleSize) {
                    dragMode = DRAG_RESIZE;
                } else if (cropRect.contains(x, y)) {
                    dragMode = DRAG_MOVE;
                } else {
                    dragMode = DRAG_NONE;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - touchStartX;
                float dy = y - touchStartY;
                float imgW = imageRect.width();
                float imgH = imageRect.height();
                if (imgW <= 0 || imgH <= 0) return true;

                if (dragMode == DRAG_MOVE) {
                    // 整体移动
                    float pctDx = dx / imgW * 100f;
                    float pctDy = dy / imgH * 100f;
                    cropX = clamp(dragStartCropX + pctDx, 0, 100 - cropW);
                    cropY = clamp(dragStartCropY + pctDy, 0, 100 - cropH);
                } else if (dragMode == DRAG_RESIZE) {
                    // 缩放（拖动右下角）
                    float pctDw = dx / imgW * 100f;
                    float pctDh = dy / imgH * 100f;
                    cropW = clamp(dragStartCropW + pctDw, 20, 100 - cropX);
                    cropH = clamp(dragStartCropH + pctDh, 20, 100 - cropY);
                }
                v.invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                dragMode = DRAG_NONE;
                return true;
        }
        return false;
    }

    /**
     * 获取裁切框在视图坐标中的位置
     */
    private RectF getCropRectInView() {
        float left = imageRect.left + cropX / 100f * imageRect.width();
        float top = imageRect.top + cropY / 100f * imageRect.height();
        float right = left + cropW / 100f * imageRect.width();
        float bottom = top + cropH / 100f * imageRect.height();
        return new RectF(left, top, right, bottom);
    }

    private void switchView(boolean left) {
        saveSettings();
        isLeftView = left;
        loadSettings(left);
        updateToggleStyle();
    }

    private void updateToggleStyle() {
        btnLeft.setTextColor(getColor(isLeftView ? R.color.button_accent : R.color.text_hint));
        btnRight.setTextColor(getColor(!isLeftView ? R.color.button_accent : R.color.text_hint));
    }

    // ==================== 实时预览 ====================

    private void startPreviewRefresh() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                updatePreview();
                if (refreshRunnable != null) {
                    mainHandler.postDelayed(this, REFRESH_INTERVAL_MS);
                }
            }
        };
        mainHandler.post(refreshRunnable);
    }

    private void stopPreviewRefresh() {
        if (refreshRunnable != null) {
            mainHandler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
    }

    private void updatePreview() {
        Bitmap fullFrame = TurningBlindSpotService.getSharedFullFrame();
        if (fullFrame == null || fullFrame.isRecycled()) return;

        int fw = fullFrame.getWidth();
        int fh = fullFrame.getHeight();

        try {
            CameraLayoutConfig.Direction direction = isLeftView ? CameraLayoutConfig.Direction.LEFT
                    : CameraLayoutConfig.Direction.RIGHT;
            float[] rect = CameraLayoutConfig.getCropRectNormalized(prefs, direction);
            int left = Math.round(rect[0] * fw);
            int top = Math.round(rect[1] * fh);
            int right = Math.round(rect[2] * fw);
            int bottom = Math.round(rect[3] * fh);
            left = Math.max(0, Math.min(left, fw - 1));
            top = Math.max(0, Math.min(top, fh - 1));
            right = Math.max(left + 1, Math.min(right, fw));
            bottom = Math.max(top + 1, Math.min(bottom, fh));
            int w = right - left;
            int h = bottom - top;

            Bitmap base = Bitmap.createBitmap(fullFrame, left, top, w, h);
            previewBaseWidth = base.getWidth();
            previewBaseHeight = base.getHeight();
            calculateImageRect();

            drawQuadrantWithOverlay(base);
            updateResultPreview(base);
        } catch (Exception e) {
            // 忽略
        }
    }


    /**
     * 在主视图上绘制完整象限 + 半透明遮罩 + 裁切框
     */
    private void drawQuadrantWithOverlay(Bitmap quadrant) {
        if (quadrant == null || imageFull == null) return;

        int qw = quadrant.getWidth();
        int qh = quadrant.getHeight();

        // 创建带遮罩的画面
        Bitmap overlay = Bitmap.createBitmap(qw, qh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlay);

        // 1. 画原始象限
        canvas.drawBitmap(quadrant, 0, 0, null);

        // 2. 画半透明遮罩（裁切框外部变暗）
        int cx = Math.round(cropX / 100f * qw);
        int cy = Math.round(cropY / 100f * qh);
        int cw = Math.round(cropW / 100f * qw);
        int ch = Math.round(cropH / 100f * qh);

        // 上
        canvas.drawRect(0, 0, qw, cy, dimPaint);
        // 下
        canvas.drawRect(0, cy + ch, qw, qh, dimPaint);
        // 左
        canvas.drawRect(0, cy, cx, cy + ch, dimPaint);
        // 右
        canvas.drawRect(cx + cw, cy, qw, cy + ch, dimPaint);

        // 3. 画裁切框边框
        canvas.drawRect(cx, cy, cx + cw, cy + ch, borderPaint);

        // 4. 右下角缩放手柄
        float handleSize = 20;
        canvas.drawRect(cx + cw - handleSize, cy + ch - handleSize, 
                cx + cw + handleSize, cy + ch + handleSize, handlePaint);

        // 5. 尺寸标注
        String info = Math.round(cropW) + "% x " + Math.round(cropH) + "%";
        canvas.drawText(info, cx + 10, cy + 30, textPaint);

        imageFull.setImageBitmap(overlay);
    }

    /**
     * 预览框：显示裁切框内的内容（补盲悬浮窗的实际效果）
     */
    private void updateResultPreview(Bitmap quadrant) {
        if (previewResult == null || quadrant == null || quadrant.isRecycled()) return;

        try {
            int qw = quadrant.getWidth();
            int qh = quadrant.getHeight();

            int cx = Math.round(cropX / 100f * qw);
            int cy = Math.round(cropY / 100f * qh);
            int cw = Math.round(cropW / 100f * qw);
            int ch = Math.round(cropH / 100f * qh);

            // 边界保护
            cw = Math.min(cw, qw - cx);
            ch = Math.min(ch, qh - cy);
            if (cw <= 0 || ch <= 0) return;

            Bitmap cropped = Bitmap.createBitmap(quadrant, cx, cy, cw, ch);
            previewResult.setImageBitmap(cropped);
        } catch (Exception e) {
            // 忽略
        }
    }

    // ==================== 参数读写 ====================

    private void loadSettings(boolean left) {
        isLeftView = left;
        String prefix = left ? "left_" : "right_";
        cropX = prefs.getFloat(prefix + "crop_x", 0);
        cropY = prefs.getFloat(prefix + "crop_y", 0);
        cropW = prefs.getFloat(prefix + "crop_w", 100);
        cropH = prefs.getFloat(prefix + "crop_h", 100);
        updateToggleStyle();
    }

    private void saveSettings() {
        String prefix = isLeftView ? "left_" : "right_";
        prefs.edit()
                .putFloat(prefix + "crop_x", cropX)
                .putFloat(prefix + "crop_y", cropY)
                .putFloat(prefix + "crop_w", cropW)
                .putFloat(prefix + "crop_h", cropH)
                .apply();

        try {
            Bitmap fullFrame = TurningBlindSpotService.getSharedFullFrame();
            if (fullFrame != null && !fullFrame.isRecycled()) {
                CameraLayoutConfig.updateOutputSize(prefs, fullFrame.getWidth(), fullFrame.getHeight());
            }
        } catch (Exception ignored) {
        }

        // 通知服务更新
        sendBroadcast(new Intent("com.elliot.blindspot.SETTINGS_CHANGED"));
        Log.d(TAG, "Saved " + (isLeftView ? "left" : "right") +
                ": crop=(" + cropX + "," + cropY + "," + cropW + "," + cropH + ")");
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
