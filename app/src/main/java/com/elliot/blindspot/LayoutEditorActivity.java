package com.elliot.blindspot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class LayoutEditorActivity extends Activity {
    private static final String PREFS_NAME = "turning_blind_spot_prefs";
    private static final int REFRESH_INTERVAL_MS = 80;

    private ImageView imageFull;
    private CropOverlayView cropOverlay;
    private TextView tvResolution;

    private Button btnFront;
    private Button btnBack;
    private Button btnLeft;
    private Button btnRight;

    private SharedPreferences prefs;
    private Handler mainHandler;
    private Runnable refreshRunnable;

    private CameraLayoutConfig.Direction currentDirection = CameraLayoutConfig.Direction.FRONT;
    private final float[][] rects = new float[CameraLayoutConfig.Direction.values().length][4];

    private static final int DRAG_NONE = 0;
    private static final int DRAG_MOVE = 1;
    private static final int DRAG_RESIZE = 2;
    private int dragMode = DRAG_NONE;
    private float touchStartX;
    private float touchStartY;
    private float[] dragStartRect = new float[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_layout_editor);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        loadRectsFromPrefs();
        setCurrentDirection(CameraLayoutConfig.Direction.FRONT);
        startPreviewRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreviewRefresh();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onBackPressed() {
        saveAll();
        super.onBackPressed();
    }

    private void initViews() {
        imageFull = findViewById(R.id.image_full);
        cropOverlay = findViewById(R.id.crop_overlay);
        tvResolution = findViewById(R.id.tv_resolution);

        btnFront = findViewById(R.id.btn_front);
        btnBack = findViewById(R.id.btn_back_view);
        btnLeft = findViewById(R.id.btn_left);
        btnRight = findViewById(R.id.btn_right);

        findViewById(R.id.btn_back).setOnClickListener(v -> {
            saveAll();
            finish();
        });
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            saveAll();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            rects[currentDirection.ordinal()] = new float[] { 0f, 0f, 1f, 1f };
            applyCurrentRectToOverlay();
        });

        btnFront.setOnClickListener(v -> setCurrentDirection(CameraLayoutConfig.Direction.FRONT));
        btnBack.setOnClickListener(v -> setCurrentDirection(CameraLayoutConfig.Direction.BACK));
        btnLeft.setOnClickListener(v -> setCurrentDirection(CameraLayoutConfig.Direction.LEFT));
        btnRight.setOnClickListener(v -> setCurrentDirection(CameraLayoutConfig.Direction.RIGHT));

        cropOverlay.setOnTouchListener(this::onCropTouch);
    }

    private void loadRectsFromPrefs() {
        for (CameraLayoutConfig.Direction d : CameraLayoutConfig.Direction.values()) {
            float[] r = CameraLayoutConfig.getCropRectNormalized(prefs, d);
            rects[d.ordinal()] = new float[] { r[0], r[1], r[2], r[3] };
        }
    }

    private void setCurrentDirection(CameraLayoutConfig.Direction direction) {
        currentDirection = direction;
        updateToggleStyle();
        applyCurrentRectToOverlay();
    }

    private void updateToggleStyle() {
        int active = getColor(R.color.button_accent);
        int inactive = getColor(R.color.text_secondary);

        btnFront.setTextColor(currentDirection == CameraLayoutConfig.Direction.FRONT ? active : inactive);
        btnBack.setTextColor(currentDirection == CameraLayoutConfig.Direction.BACK ? active : inactive);
        btnLeft.setTextColor(currentDirection == CameraLayoutConfig.Direction.LEFT ? active : inactive);
        btnRight.setTextColor(currentDirection == CameraLayoutConfig.Direction.RIGHT ? active : inactive);
    }

    private void applyCurrentRectToOverlay() {
        float[] r = rects[currentDirection.ordinal()];
        cropOverlay.setCropRectNormalized(r[0], r[1], r[2], r[3]);
    }

    private void startPreviewRefresh() {
        refreshRunnable = () -> {
            updatePreview();
            if (refreshRunnable != null) {
                mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
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
        if (fullFrame == null || fullFrame.isRecycled()) {
            tvResolution.setText("输出分辨率：未知（请先启动补盲服务）");
            return;
        }

        tvResolution.setText("输出分辨率：" + fullFrame.getWidth() + "×" + fullFrame.getHeight());
        imageFull.setImageBitmap(fullFrame);

        RectF imageRect = calculateImageRect(imageFull.getWidth(), imageFull.getHeight(), fullFrame.getWidth(),
                fullFrame.getHeight());
        cropOverlay.setImageRect(imageRect);
        applyCurrentRectToOverlay();
    }

    private RectF calculateImageRect(int viewW, int viewH, int imgW, int imgH) {
        if (viewW <= 0 || viewH <= 0 || imgW <= 0 || imgH <= 0) return new RectF();
        float scaleX = (float) viewW / imgW;
        float scaleY = (float) viewH / imgH;
        float scale = Math.min(scaleX, scaleY);
        float scaledW = imgW * scale;
        float scaledH = imgH * scale;
        float left = (viewW - scaledW) / 2f;
        float top = (viewH - scaledH) / 2f;
        return new RectF(left, top, left + scaledW, top + scaledH);
    }

    private boolean onCropTouch(View v, MotionEvent event) {
        RectF imgRect = cropOverlay.getImageRectCopy();
        if (imgRect.width() <= 0 || imgRect.height() <= 0) return true;

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                touchStartX = x;
                touchStartY = y;
                float[] r = rects[currentDirection.ordinal()];
                dragStartRect = new float[] { r[0], r[1], r[2], r[3] };

                RectF crop = cropOverlay.getCropRectInView();
                float handleSize = 60f;
                if (x > crop.right - handleSize && y > crop.bottom - handleSize && x < crop.right + handleSize
                        && y < crop.bottom + handleSize) {
                    dragMode = DRAG_RESIZE;
                } else if (crop.contains(x, y)) {
                    dragMode = DRAG_MOVE;
                } else {
                    dragMode = DRAG_NONE;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragMode == DRAG_NONE) return true;
                float dx = x - touchStartX;
                float dy = y - touchStartY;

                float ndx = dx / imgRect.width();
                float ndy = dy / imgRect.height();

                float l = dragStartRect[0];
                float t = dragStartRect[1];
                float r = dragStartRect[2];
                float b = dragStartRect[3];

                if (dragMode == DRAG_MOVE) {
                    float w = r - l;
                    float h = b - t;
                    float nl = l + ndx;
                    float nt = t + ndy;
                    nl = clamp(nl, 0f, 1f - w);
                    nt = clamp(nt, 0f, 1f - h);
                    l = nl;
                    t = nt;
                    r = nl + w;
                    b = nt + h;
                } else if (dragMode == DRAG_RESIZE) {
                    float minSize = 0.02f;
                    r = clamp(l + minSize, l + minSize, 1f);
                    b = clamp(t + minSize, t + minSize, 1f);
                    r = clamp(dragStartRect[2] + ndx, l + minSize, 1f);
                    b = clamp(dragStartRect[3] + ndy, t + minSize, 1f);
                }

                rects[currentDirection.ordinal()] = new float[] { l, t, r, b };
                applyCurrentRectToOverlay();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = DRAG_NONE;
                return true;
            default:
                return false;
        }
    }

    private float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private void saveAll() {
        Bitmap fullFrame = TurningBlindSpotService.getSharedFullFrame();
        if (fullFrame != null && !fullFrame.isRecycled()) {
            CameraLayoutConfig.updateOutputSize(prefs, fullFrame.getWidth(), fullFrame.getHeight());
        }

        CameraLayoutConfig.setVehicleType(prefs, CameraLayoutConfig.VehicleType.CUSTOM);
        for (CameraLayoutConfig.Direction d : CameraLayoutConfig.Direction.values()) {
            float[] r = rects[d.ordinal()];
            CameraLayoutConfig.setCustomCropRectNormalized(prefs, d, r[0], r[1], r[2], r[3]);
        }
        sendBroadcast(new Intent(TurningBlindSpotService.ACTION_SETTINGS_CHANGED));
    }
}

