package com.elliot.blindspot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class CropOverlayView extends View {
    private final RectF imageRect = new RectF();
    private final float[] cropRect = new float[] { 0f, 0f, 1f, 1f };

    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint handlePaint = new Paint();
    private final Paint textPaint = new Paint();

    public CropOverlayView(Context context) {
        super(context);
        init();
    }

    public CropOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CropOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        dimPaint.setColor(Color.argb(140, 0, 0, 0));
        dimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);

        handlePaint.setColor(Color.argb(220, 79, 195, 247));
        handlePaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);
    }

    public void setImageRect(RectF rect) {
        if (rect == null) return;
        imageRect.set(rect);
        invalidate();
    }

    public RectF getImageRectCopy() {
        return new RectF(imageRect);
    }

    public void setCropRectNormalized(float l, float t, float r, float b) {
        cropRect[0] = l;
        cropRect[1] = t;
        cropRect[2] = r;
        cropRect[3] = b;
        invalidate();
    }

    public float[] getCropRectNormalizedCopy() {
        return new float[] { cropRect[0], cropRect[1], cropRect[2], cropRect[3] };
    }

    public RectF getCropRectInView() {
        float imgW = imageRect.width();
        float imgH = imageRect.height();
        if (imgW <= 0 || imgH <= 0) return new RectF();

        float left = imageRect.left + cropRect[0] * imgW;
        float top = imageRect.top + cropRect[1] * imgH;
        float right = imageRect.left + cropRect[2] * imgW;
        float bottom = imageRect.top + cropRect[3] * imgH;
        return new RectF(left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageRect.width() <= 0 || imageRect.height() <= 0) return;

        RectF crop = getCropRectInView();
        if (crop.width() <= 0 || crop.height() <= 0) return;

        float imgLeft = imageRect.left;
        float imgTop = imageRect.top;
        float imgRight = imageRect.right;
        float imgBottom = imageRect.bottom;

        canvas.drawRect(imgLeft, imgTop, imgRight, crop.top, dimPaint);
        canvas.drawRect(imgLeft, crop.bottom, imgRight, imgBottom, dimPaint);
        canvas.drawRect(imgLeft, crop.top, crop.left, crop.bottom, dimPaint);
        canvas.drawRect(crop.right, crop.top, imgRight, crop.bottom, dimPaint);

        canvas.drawRect(crop, borderPaint);

        float handle = 20f;
        canvas.drawRect(crop.right - handle, crop.bottom - handle, crop.right + handle, crop.bottom + handle, handlePaint);

        float wPercent = Math.round((cropRect[2] - cropRect[0]) * 100f);
        float hPercent = Math.round((cropRect[3] - cropRect[1]) * 100f);
        canvas.drawText((int) wPercent + "% x " + (int) hPercent + "%", crop.left + 10, crop.top + 30, textPaint);
    }
}

