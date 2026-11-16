package com.elliot.blindspot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

final class VideoStorage {
    static final String PREF_RECORD_STORE_MODE = "record_store_mode";
    static final String PREF_RECORD_STORE_PATH = "record_store_path";

    static final String STORE_MODE_INTERNAL = "internal";
    static final String STORE_MODE_USB_PATH = "usb_path";

    static final String PREF_PHOTO_STORE_MODE = "photo_store_mode";
    static final String PREF_PHOTO_STORE_PATH = "photo_store_path";

    private static final String DEFAULT_DIR_NAME = "E02REC";
    private static final String LEGACY_DIR_NAME = "E02CAMERA";

    private final Context context;
    private final SharedPreferences prefs;

    VideoStorage(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    static final class SegmentFile {
        final File file;
        final String displayPath;
        final long timestampMs;

        SegmentFile(File file, String displayPath, long timestampMs) {
            this.file = file;
            this.displayPath = displayPath;
            this.timestampMs = timestampMs;
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

    SegmentFile createNewSegmentFile(String baseName) throws Exception {
        long ts = System.currentTimeMillis();
        String displayName = baseName + ".mov";
        File dir = resolveWriteDir();
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, displayName);
        return new SegmentFile(f, f.getAbsolutePath(), ts);
    }

    File createNewPhotoFile(String baseName) throws Exception {
        File dir = resolveWriteDir();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, baseName + ".jpg");
    }

    void enforceFolderLimitBytes(long limitBytes) {
        if (limitBytes <= 0) return;
        File dir = resolveWriteDir();
        enforceFileDirLimit(dir, limitBytes);
    }

    FolderStats getFolderStats() {
        File dir = resolveWriteDir();
        return getFileDirStats(dir);
    }

    File[] listAllSegments() {
        File[] a = listMovFiles(resolveWriteDir());
        File[] b = listMovFiles(resolveLegacyReadDir());
        if (a.length == 0) return b;
        if (b.length == 0) return a;
        File[] out = new File[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        Arrays.sort(out, Comparator.comparing(File::getName));
        return out;
    }

    private File resolveWriteDir() {
        String mode = prefs.getString(PREF_RECORD_STORE_MODE, null);
        if (mode == null || mode.length() == 0) {
            mode = prefs.getString(PREF_PHOTO_STORE_MODE, STORE_MODE_INTERNAL);
        }
        if (STORE_MODE_USB_PATH.equals(mode)) {
            String basePath = prefs.getString(PREF_RECORD_STORE_PATH, null);
            if (basePath == null || basePath.length() == 0) {
                basePath = prefs.getString(PREF_PHOTO_STORE_PATH, null);
            }
            if (basePath == null || basePath.length() == 0) {
                basePath = StorageHelper.getSdCardFromMounts();
                if (basePath == null) basePath = StorageHelper.getSdCardFromExternalFilesDirs(context);
            }
            if (basePath != null && basePath.length() > 0) {
                return new File(basePath, DEFAULT_DIR_NAME);
            }
        }
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        return new File(dcim, DEFAULT_DIR_NAME);
    }

    private File resolveLegacyReadDir() {
        String mode = prefs.getString(PREF_RECORD_STORE_MODE, null);
        if (mode == null || mode.length() == 0) {
            mode = prefs.getString(PREF_PHOTO_STORE_MODE, STORE_MODE_INTERNAL);
        }
        if (STORE_MODE_USB_PATH.equals(mode)) {
            String basePath = prefs.getString(PREF_RECORD_STORE_PATH, null);
            if (basePath == null || basePath.length() == 0) {
                basePath = prefs.getString(PREF_PHOTO_STORE_PATH, null);
            }
            if (basePath == null || basePath.length() == 0) {
                basePath = StorageHelper.getSdCardFromMounts();
                if (basePath == null) basePath = StorageHelper.getSdCardFromExternalFilesDirs(context);
            }
            if (basePath != null && basePath.length() > 0) {
                return new File(basePath, LEGACY_DIR_NAME);
            }
        }
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        return new File(dcim, LEGACY_DIR_NAME);
    }

    private static void enforceFileDirLimit(File dir, long limitBytes) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = listMovFiles(dir);
        if (files.length == 0) return;
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

    private static FolderStats getFileDirStats(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return new FolderStats(0, 0);
        File[] files = listMovFiles(dir);
        if (files.length == 0) return new FolderStats(0, 0);
        long total = 0;
        for (File f : files) total += safeLen(f);
        return new FolderStats(total, files.length);
    }

    private static File[] listMovFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return new File[0];
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".mov"));
        if (files == null) return new File[0];
        return files;
    }

    private static long safeLen(File f) {
        try {
            return f.length();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
