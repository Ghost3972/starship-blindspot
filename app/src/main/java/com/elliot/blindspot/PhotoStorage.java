package com.elliot.blindspot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class PhotoStorage {
    static final String PREF_PHOTO_STORE_MODE = "photo_store_mode";
    static final String PREF_PHOTO_STORE_PATH = "photo_store_path";

    static final String STORE_MODE_INTERNAL = "internal";
    static final String STORE_MODE_USB_PATH = "usb_path";

    private static final String DEFAULT_DIR_NAME = "E02CAMERA";

    private final Context context;
    private final SharedPreferences prefs;

    PhotoStorage(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    static final class SaveResult {
        final String displayPath;
        final long bytes;
        final long timestampMs;
        final Uri uri;

        SaveResult(String displayPath, long bytes, long timestampMs, Uri uri) {
            this.displayPath = displayPath;
            this.bytes = bytes;
            this.timestampMs = timestampMs;
            this.uri = uri;
        }
    }

    static final class FolderStats {
        final long bytes;
        final int count;

        FolderStats(long bytes, int count) {
            this.bytes = bytes;
            this.count = count;
        }
    }

    SaveResult saveBitmap(android.graphics.Bitmap bitmap, String baseName, String format, int quality) throws Exception {
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("bitmap invalid");
        }
        String ext = normalizeExt(format);
        long ts = System.currentTimeMillis();
        String displayName = baseName + "." + ext;

        String mode = prefs.getString(PREF_PHOTO_STORE_MODE, STORE_MODE_INTERNAL);
        if (STORE_MODE_USB_PATH.equals(mode)) {
            String basePath = prefs.getString(PREF_PHOTO_STORE_PATH, null);
            if (basePath == null || basePath.length() == 0) {
                basePath = StorageHelper.getSdCardFromMounts();
                if (basePath == null) basePath = StorageHelper.getSdCardFromExternalFilesDirs(context);
            }
            if (basePath != null && basePath.length() > 0) {
                File dir = new File(basePath, DEFAULT_DIR_NAME);
                if (!dir.exists()) dir.mkdirs();
                return saveToFile(bitmap, new File(dir, displayName), ext, quality, ts);
            }
        }
        return saveToInternalDcim(bitmap, displayName, ext, quality, ts);
    }

    void enforceFolderLimitBytes(long limitBytes) {
        if (limitBytes <= 0) return;
        String mode = prefs.getString(PREF_PHOTO_STORE_MODE, STORE_MODE_INTERNAL);
        if (STORE_MODE_USB_PATH.equals(mode)) {
            String basePath = prefs.getString(PREF_PHOTO_STORE_PATH, null);
            if (basePath == null || basePath.length() == 0) {
                basePath = StorageHelper.getSdCardFromMounts();
                if (basePath == null) basePath = StorageHelper.getSdCardFromExternalFilesDirs(context);
            }
            if (basePath != null && basePath.length() > 0) {
                enforceFileDirLimit(new File(new File(basePath, DEFAULT_DIR_NAME).getAbsolutePath()), limitBytes);
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            enforceMediaStoreLimit(limitBytes);
        } else {
            File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            enforceFileDirLimit(new File(dcim, DEFAULT_DIR_NAME), limitBytes);
        }
    }

    FolderStats getFolderStats() {
        String mode = prefs.getString(PREF_PHOTO_STORE_MODE, STORE_MODE_INTERNAL);
        if (STORE_MODE_USB_PATH.equals(mode)) {
            String basePath = prefs.getString(PREF_PHOTO_STORE_PATH, null);
            if (basePath == null || basePath.length() == 0) {
                basePath = StorageHelper.getSdCardFromMounts();
                if (basePath == null) basePath = StorageHelper.getSdCardFromExternalFilesDirs(context);
            }
            if (basePath != null && basePath.length() > 0) {
                return getFileDirStats(new File(new File(basePath, DEFAULT_DIR_NAME).getAbsolutePath()));
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getMediaStoreStats();
        }
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        return getFileDirStats(new File(dcim, DEFAULT_DIR_NAME));
    }

    private SaveResult saveToFile(android.graphics.Bitmap bitmap, File file, String ext, int quality, long ts)
            throws Exception {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        long before = file.exists() ? file.length() : 0;
        try (FileOutputStream fos = new FileOutputStream(file)) {
            android.graphics.Bitmap.CompressFormat cf = toCompressFormat(ext);
            boolean ok = bitmap.compress(cf, quality, fos);
            if (!ok) throw new RuntimeException("compress failed");
        }
        long after = file.length();
        return new SaveResult(file.getAbsolutePath(), Math.max(0, after - before), ts, null);
    }

    private SaveResult saveToInternalDcim(android.graphics.Bitmap bitmap, String displayName, String ext, int quality,
            long ts) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = context.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            cv.put(MediaStore.Images.Media.MIME_TYPE, mimeTypeFor(ext));
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + DEFAULT_DIR_NAME);
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new RuntimeException("mediastore insert failed");
            long bytes = 0;
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new RuntimeException("mediastore stream null");
                android.graphics.Bitmap.CompressFormat cf = toCompressFormat(ext);
                boolean ok = bitmap.compress(cf, quality, os);
                if (!ok) throw new RuntimeException("compress failed");
            }
            ContentValues finalize = new ContentValues();
            finalize.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, finalize, null, null);
            return new SaveResult(uri.toString(), bytes, ts, uri);
        }
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File dir = new File(dcim, DEFAULT_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return saveToFile(bitmap, new File(dir, displayName), ext, quality, ts);
    }

    private void enforceFileDirLimit(File dir, long limitBytes) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name != null && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp")));
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File f : files) total += safeLen(f);
        if (total <= limitBytes) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        long target = (long) (limitBytes * 0.9);
        for (File f : files) {
            if (total <= target) break;
            long len = safeLen(f);
            if (f.delete()) total -= len;
        }
    }

    private void enforceMediaStoreLimit(long limitBytes) {
        ContentResolver cr = context.getContentResolver();
        String rel = Environment.DIRECTORY_DCIM + "/" + DEFAULT_DIR_NAME + "/";
        String[] proj = new String[] { MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED };
        String sel = MediaStore.Images.Media.RELATIVE_PATH + "=?";
        String[] args = new String[] { rel };
        List<Item> items = new ArrayList<>();
        long total = 0;
        try (android.database.Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                MediaStore.Images.Media.DATE_ADDED + " ASC")) {
            if (c == null) return;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                long size = c.getLong(sizeIdx);
                total += Math.max(0, size);
                items.add(new Item(id, size));
            }
        } catch (Exception ignored) {
            return;
        }
        if (total <= limitBytes) return;
        long target = (long) (limitBytes * 0.9);
        for (Item it : items) {
            if (total <= target) break;
            Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(it.id));
            try {
                int del = cr.delete(uri, null, null);
                if (del > 0) total -= Math.max(0, it.size);
            } catch (Exception ignored) {
            }
        }
    }

    private FolderStats getFileDirStats(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return new FolderStats(0, 0);
        File[] files = dir.listFiles((d, name) -> name != null && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp")));
        if (files == null || files.length == 0) return new FolderStats(0, 0);
        long total = 0;
        int count = 0;
        for (File f : files) {
            total += safeLen(f);
            count++;
        }
        return new FolderStats(total, count);
    }

    private FolderStats getMediaStoreStats() {
        ContentResolver cr = context.getContentResolver();
        String rel = Environment.DIRECTORY_DCIM + "/" + DEFAULT_DIR_NAME + "/";
        String[] proj = new String[] { MediaStore.Images.Media.SIZE };
        String sel = MediaStore.Images.Media.RELATIVE_PATH + "=?";
        String[] args = new String[] { rel };
        long total = 0;
        int count = 0;
        try (android.database.Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)) {
            if (c == null) return new FolderStats(0, 0);
            int sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            while (c.moveToNext()) {
                long size = c.getLong(sizeIdx);
                total += Math.max(0, size);
                count++;
            }
        } catch (Exception ignored) {
            return new FolderStats(0, 0);
        }
        return new FolderStats(total, count);
    }

    private static long safeLen(File f) {
        try {
            return f.length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String normalizeExt(String format) {
        if (format == null) return "jpg";
        String f = format.trim().toLowerCase();
        if ("jpeg".equals(f) || "jpg".equals(f)) return "jpg";
        if ("png".equals(f)) return "png";
        if ("webp".equals(f)) return "webp";
        return "jpg";
    }

    private static android.graphics.Bitmap.CompressFormat toCompressFormat(String ext) {
        if ("png".equals(ext)) return android.graphics.Bitmap.CompressFormat.PNG;
        if ("webp".equals(ext)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return android.graphics.Bitmap.CompressFormat.WEBP_LOSSY;
            return android.graphics.Bitmap.CompressFormat.WEBP;
        }
        return android.graphics.Bitmap.CompressFormat.JPEG;
    }

    private static String mimeTypeFor(String ext) {
        if ("png".equals(ext)) return "image/png";
        if ("webp".equals(ext)) return "image/webp";
        return "image/jpeg";
    }

    private static final class Item {
        final long id;
        final long size;

        Item(long id, long size) {
            this.id = id;
            this.size = size;
        }
    }

}
