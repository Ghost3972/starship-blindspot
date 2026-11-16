package com.elliot.blindspot;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.FileDescriptor;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoViewerActivity extends android.app.Activity {
    private static final String PREFS_NAME = "turning_blind_spot_prefs";

    private SharedPreferences prefs;
    private ExecutorService ioExecutor;

    private ImageView ivFull;
    private ImageView ivFront;
    private ImageView ivBack;
    private ImageView ivLeft;
    private ImageView ivRight;
    private TextView tvTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_viewer);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        ioExecutor = Executors.newSingleThreadExecutor();

        Button btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        ivFull = findViewById(R.id.iv_full);
        ivFront = findViewById(R.id.iv_front);
        ivBack = findViewById(R.id.iv_back);
        ivLeft = findViewById(R.id.iv_left);
        ivRight = findViewById(R.id.iv_right);
        tvTitle = findViewById(R.id.tv_title);

        String uriStr = getIntent().getStringExtra("uri");
        String name = getIntent().getStringExtra("name");
        if (name == null) name = "录制预览";
        tvTitle.setText(name);

        if (uriStr == null || uriStr.isEmpty()) {
            finish();
            return;
        }
        Uri uri = Uri.parse(uriStr);
        loadAll(uri, name);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
    }

    private void loadAll(Uri uri, String name) {
        ioExecutor.execute(() -> {
            Bitmap full = decodeScaled(uri, screenMaxWidth());
            runOnUiThread(() -> {
                if (full != null) ivFull.setImageBitmap(full);
                String t = parseDisplayTime(name);
                if (t != null) tvTitle.setText(t);
            });
            decodeAndSetQuadrants(uri);
        });
    }

    private void decodeAndSetQuadrants(Uri uri) {
        BitmapRegionDecoder decoder = null;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return;
            FileDescriptor fd = pfd.getFileDescriptor();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd, null, bounds);
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) return;

            decoder = BitmapRegionDecoder.newInstance(fd, false);
            int cellW = Math.max(320, screenMaxWidth() / 2);
            Bitmap front = decodeRegion(decoder, rectFor(CameraLayoutConfig.Direction.FRONT, w, h), cellW);
            Bitmap back = decodeRegion(decoder, rectFor(CameraLayoutConfig.Direction.BACK, w, h), cellW);
            Bitmap left = decodeRegion(decoder, rectFor(CameraLayoutConfig.Direction.LEFT, w, h), cellW);
            Bitmap right = decodeRegion(decoder, rectFor(CameraLayoutConfig.Direction.RIGHT, w, h), cellW);
            runOnUiThread(() -> {
                if (front != null) ivFront.setImageBitmap(front);
                if (back != null) ivBack.setImageBitmap(back);
                if (left != null) ivLeft.setImageBitmap(left);
                if (right != null) ivRight.setImageBitmap(right);
            });
        } catch (Exception ignored) {
        } finally {
            try {
                if (decoder != null) decoder.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    private Rect rectFor(CameraLayoutConfig.Direction direction, int w, int h) {
        float[] r = CameraLayoutConfig.getCropRectNormalized(prefs, direction);
        int l = Math.round(r[0] * w);
        int t = Math.round(r[1] * h);
        int rr = Math.round(r[2] * w);
        int bb = Math.round(r[3] * h);
        l = Math.max(0, Math.min(l, w - 2));
        t = Math.max(0, Math.min(t, h - 2));
        rr = Math.max(l + 2, Math.min(rr, w));
        bb = Math.max(t + 2, Math.min(bb, h));
        return new Rect(l, t, rr, bb);
    }

    private Bitmap decodeRegion(BitmapRegionDecoder decoder, Rect rect, int reqWidth) {
        if (decoder == null || rect == null) return null;
        try {
            int w = rect.width();
            int inSample = 1;
            while (w / inSample > reqWidth) inSample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, inSample);
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return decoder.decodeRegion(rect, o);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap decodeScaled(Uri uri, int reqWidth) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, b);
            }
            int w = b.outWidth;
            int h = b.outHeight;
            if (w <= 0 || h <= 0) return null;
            int inSample = 1;
            while (w / inSample > reqWidth) inSample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, inSample);
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                if (is2 == null) return null;
                return BitmapFactory.decodeStream(is2, null, o);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private int screenMaxWidth() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    private String parseDisplayTime(String name) {
        if (name == null) return null;
        String base = name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
            Date d = sdf.parse(base);
            if (d == null) return null;
            SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            return out.format(d);
        } catch (Exception ignored) {
            return null;
        }
    }
}
