package com.elliot.blindspot;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
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

public class PhotoGroupActivity extends Activity {
    private static final String PREFS_NAME = "turning_blind_spot_prefs";

    private SharedPreferences prefs;
    private ExecutorService ioExecutor;

    private TextView tvTitle;
    private TextView tvHint;
    private Button btnPlay;
    private Button btnPause;
    private Button btnStop;
    private RecyclerView rvPhotos;
    private View overlaySlideshow;
    private ImageView ivSlideshow;

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable slideshowRunnable;
    private List<SegmentItem> slideshowItems;
    private int slideshowIndex;
    private boolean slideshowPaused;
    private boolean slideshowDecodeInFlight;
    private MovMjpegReader slideshowReader;
    private int slideshowSampleIndex;
    private volatile long playbackIntervalMs = 100;

    private long bucketStartMs;
    private long bucketMs;

    private SegmentAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_group);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        ioExecutor = Executors.newSingleThreadExecutor();

        bucketStartMs = getIntent().getLongExtra("bucket_start", 0);
        bucketMs = getIntent().getLongExtra("bucket_ms", 60_000L);
        if (bucketStartMs <= 0 || bucketMs <= 0) {
            finish();
            return;
        }

        Button btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        tvTitle = findViewById(R.id.tv_title);
        tvHint = findViewById(R.id.tv_hint);
        btnPlay = findViewById(R.id.btn_play);
        btnPause = findViewById(R.id.btn_pause);
        btnStop = findViewById(R.id.btn_stop);
        rvPhotos = findViewById(R.id.rv_photos);
        overlaySlideshow = findViewById(R.id.overlay_slideshow);
        ivSlideshow = findViewById(R.id.iv_slideshow);

        rvPhotos.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        adapter = new SegmentAdapter(this);
        rvPhotos.setAdapter(adapter);

        btnPlay.setOnClickListener(v -> startSlideshow());
        btnPause.setOnClickListener(v -> togglePause());
        btnStop.setOnClickListener(v -> stopSlideshow());

        String title = formatRange(bucketStartMs, bucketStartMs + bucketMs);
        tvTitle.setText(title);
        loadGroupAsync();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSlideshow();
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
    }

    private int computeSpanCount() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float dp = dm.widthPixels / dm.density;
        return Math.max(3, Math.min(8, (int) (dp / 260)));
    }

    private void loadGroupAsync() {
        tvHint.setText("加载中…");
        ioExecutor.execute(() -> {
            List<SegmentItem> items = loadSegmentsFiltered(bucketStartMs, bucketStartMs + bucketMs);
            runOnUiThread(() -> {
                adapter.setItems(items);
                tvHint.setText("共 " + items.size() + " 段");
            });
        });
    }

    private List<SegmentItem> loadSegmentsFiltered(long startMs, long endMs) {
        List<SegmentItem> all = loadSegments();
        List<SegmentItem> out = new ArrayList<>();
        for (SegmentItem it : all) {
            if (it.timestampMs >= startMs && it.timestampMs < endMs) out.add(it);
        }
        out.sort((a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        return out;
    }

    private List<SegmentItem> loadSegments() {
        List<SegmentItem> out = new ArrayList<>();
        File[] files = new VideoStorage(getApplicationContext(), prefs).listAllSegments();
        if (files == null || files.length == 0) return out;
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        int limit = Math.min(files.length, 6000);
        for (int i = 0; i < limit; i++) {
            File f = files[i];
            String name = f.getName();
            long ts = parseTimestamp(name, f.lastModified());
            out.add(new SegmentItem(name, ts, f, safeLen(f)));
        }
        return out;
    }

    private void startSlideshow() {
        stopSlideshow();
        List<SegmentItem> items = adapter.getItems();
        if (items == null || items.isEmpty()) {
            Toast.makeText(this, "集合为空", Toast.LENGTH_SHORT).show();
            return;
        }
        slideshowItems = new ArrayList<>(items);
        slideshowIndex = 0;
        slideshowSampleIndex = 0;
        slideshowPaused = false;
        slideshowDecodeInFlight = false;
        btnPause.setText("暂停");
        overlaySlideshow.setVisibility(View.VISIBLE);
        slideshowRunnable = new Runnable() {
            @Override
            public void run() {
                if (slideshowItems == null || slideshowItems.isEmpty()) return;
                long interval = playbackIntervalMs;
                if (slideshowPaused) {
                    mainHandler.postDelayed(this, interval);
                    return;
                }
                if (slideshowDecodeInFlight) {
                    mainHandler.postDelayed(this, interval);
                    return;
                }
                slideshowDecodeInFlight = true;
                ioExecutor.execute(() -> {
                    android.graphics.Bitmap bmp = null;
                    try {
                        byte[] jpeg = nextJpegSample();
                        if (jpeg != null) bmp = decodeJpeg(jpeg, screenMaxWidth());
                    } catch (Exception ignored) {
                    }
                    android.graphics.Bitmap out = bmp;
                    mainHandler.post(() -> {
                        if (out != null) ivSlideshow.setImageBitmap(out);
                        slideshowDecodeInFlight = false;
                        if (slideshowRunnable != null && overlaySlideshow.getVisibility() == View.VISIBLE) {
                            mainHandler.postDelayed(slideshowRunnable, interval);
                        }
                    });
                });
            }
        };
        mainHandler.post(slideshowRunnable);
    }

    private void playFromIndex(int index) {
        if (index < 0 || adapter == null) return;
        List<SegmentItem> items = adapter.getItems();
        if (items == null || items.isEmpty()) return;
        stopSlideshow();
        slideshowItems = new ArrayList<>(items);
        slideshowIndex = Math.max(0, Math.min(items.size() - 1, index));
        slideshowSampleIndex = 0;
        slideshowPaused = false;
        slideshowDecodeInFlight = false;
        try {
            if (slideshowReader != null) slideshowReader.close();
        } catch (Exception ignored) {
        }
        slideshowReader = null;
        overlaySlideshow.setVisibility(View.VISIBLE);
        btnPause.setText("暂停");
        slideshowRunnable = new Runnable() {
            @Override
            public void run() {
                if (slideshowItems == null || slideshowItems.isEmpty()) return;
                long interval = playbackIntervalMs;
                if (slideshowPaused) {
                    mainHandler.postDelayed(this, interval);
                    return;
                }
                if (slideshowDecodeInFlight) {
                    mainHandler.postDelayed(this, interval);
                    return;
                }
                slideshowDecodeInFlight = true;
                ioExecutor.execute(() -> {
                    android.graphics.Bitmap bmp = null;
                    try {
                        byte[] jpeg = nextJpegSample();
                        if (jpeg != null) bmp = decodeJpeg(jpeg, screenMaxWidth());
                    } catch (Exception ignored) {
                    }
                    android.graphics.Bitmap out = bmp;
                    mainHandler.post(() -> {
                        if (out != null) ivSlideshow.setImageBitmap(out);
                        slideshowDecodeInFlight = false;
                        if (slideshowRunnable != null && overlaySlideshow.getVisibility() == View.VISIBLE) {
                            mainHandler.postDelayed(slideshowRunnable, interval);
                        }
                    });
                });
            }
        };
        mainHandler.post(slideshowRunnable);
    }

    private void togglePause() {
        if (slideshowRunnable == null || overlaySlideshow.getVisibility() != View.VISIBLE) return;
        slideshowPaused = !slideshowPaused;
        btnPause.setText(slideshowPaused ? "继续" : "暂停");
    }

    private void stopSlideshow() {
        if (slideshowRunnable != null) {
            mainHandler.removeCallbacks(slideshowRunnable);
            slideshowRunnable = null;
        }
        slideshowItems = null;
        slideshowPaused = false;
        slideshowDecodeInFlight = false;
        btnPause.setText("暂停");
        overlaySlideshow.setVisibility(View.GONE);
        ivSlideshow.setImageDrawable(null);
        try {
            if (slideshowReader != null) slideshowReader.close();
        } catch (Exception ignored) {
        }
        slideshowReader = null;
        slideshowSampleIndex = 0;
    }

    private byte[] nextJpegSample() throws Exception {
        if (slideshowItems == null || slideshowItems.isEmpty()) return null;
        int loops = 0;
        while (loops < slideshowItems.size() + 1) {
            SegmentItem seg = slideshowItems.get(slideshowIndex);
            if (slideshowReader == null) {
                slideshowReader = MovMjpegReader.open(seg.file);
                slideshowSampleIndex = 0;
                playbackIntervalMs = computePlaybackIntervalMs(slideshowReader);
            }
            int count = slideshowReader.getSampleCount();
            if (slideshowSampleIndex >= count) {
                try {
                    slideshowReader.close();
                } catch (Exception ignored) {
                }
                slideshowReader = null;
                slideshowSampleIndex = 0;
                slideshowIndex = (slideshowIndex + 1) % slideshowItems.size();
                loops++;
                continue;
            }
            byte[] jpeg = slideshowReader.readJpegSample(slideshowSampleIndex);
            slideshowSampleIndex++;
            return jpeg;
        }
        return null;
    }

    private static long computePlaybackIntervalMs(MovMjpegReader r) {
        if (r == null) return 100;
        int timescale = r.getTimescale();
        int dur = r.getSampleDuration();
        if (timescale <= 0 || dur <= 0) return 100;
        double ms = (dur * 1000.0) / (double) timescale;
        return Math.max(30, Math.round(ms));
    }

    private static android.graphics.Bitmap decodeJpeg(byte[] jpeg, int reqWidth) {
        try {
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

    private int screenMaxWidth() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
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

    private static String formatRange(long startMs, long endMs) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            return sdf.format(new Date(startMs)) + " ~ " + sdf.format(new Date(endMs));
        } catch (Exception ignored) {
            return "集合";
        }
    }

    private static long safeLen(File f) {
        try {
            return f.length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    static final class SegmentItem {
        final String name;
        final long timestampMs;
        final File file;
        final long bytes;

        SegmentItem(String name, long timestampMs, File file, long bytes) {
            this.name = name;
            this.timestampMs = timestampMs;
            this.file = file;
            this.bytes = bytes;
        }
    }

    final class SegmentAdapter extends RecyclerView.Adapter<SegmentAdapter.SegmentVH> {
        private final java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        private List<SegmentItem> items = new ArrayList<>();

        SegmentAdapter(android.content.Context context) {
        }

        void setItems(List<SegmentItem> items) {
            this.items = items != null ? items : new ArrayList<>();
            notifyDataSetChanged();
        }

        List<SegmentItem> getItems() {
            return items;
        }

        @Override
        public SegmentVH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
            return new SegmentVH(v);
        }

        @Override
        public void onBindViewHolder(SegmentVH holder, int position) {
            SegmentItem it = items.get(position);
            holder.tvName.setText(it.name);
            holder.tvInfo.setText(formatBytes(it.bytes) + " · " + fmt.format(new Date(it.timestampMs)));
            holder.itemView.setOnClickListener(v -> playFromIndex(holder.getBindingAdapterPosition()));
            holder.btnFavorite.setVisibility(View.GONE);
            holder.btnDelete.setOnClickListener(v -> {
                try {
                    boolean ok = it.file != null && it.file.exists() && it.file.delete();
                    Toast.makeText(PhotoGroupActivity.this, ok ? "已删除" : "删除失败", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(PhotoGroupActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                }
                loadGroupAsync();
            });
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        final class SegmentVH extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvInfo;
            final View btnFavorite;
            final View btnDelete;

            SegmentVH(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_video_name);
                tvInfo = itemView.findViewById(R.id.tv_video_info);
                btnFavorite = itemView.findViewById(R.id.btn_favorite);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }
        }
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
}
