package com.elliot.blindspot;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.regex.Pattern;

final class StorageHelper {
    private static final Pattern STORAGE_ID = Pattern.compile("^/storage/[0-9a-fA-F]{4}-[0-9a-fA-F]{4}$");

    static String getSdCardFromMounts() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                String mount = parts[1];
                if (!STORAGE_ID.matcher(mount).matches()) continue;
                File dir = new File(mount);
                if (dir.exists() && dir.isDirectory() && dir.canRead()) return mount;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String getSdCardFromExternalFilesDirs(Context context) {
        if (context == null) return null;
        try {
            File[] dirs = context.getExternalFilesDirs(null);
            if (dirs == null || dirs.length <= 1) return null;
            for (int i = 1; i < dirs.length; i++) {
                File f = dirs[i];
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int idx = p.indexOf("/Android/data/");
                if (idx <= 0) continue;
                String root = p.substring(0, idx);
                if (!STORAGE_ID.matcher(root).matches()) continue;
                File dir = new File(root);
                if (dir.exists() && dir.isDirectory() && dir.canRead()) return root;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String getExternalSdCardPath(Context context) {
        if (context == null) return null;
        try {
            File[] dirs = context.getExternalFilesDirs(null);
            if (dirs == null || dirs.length <= 1) return null;
            for (int i = 1; i < dirs.length; i++) {
                File f = dirs[i];
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int idx = p.indexOf("/Android/data/");
                if (idx <= 0) continue;
                String root = p.substring(0, idx);
                File dir = new File(root);
                if (dir.exists() && dir.isDirectory() && dir.canRead()) return root;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean hasExternalSdCard(Context context) {
        String root = getSdCardFromMounts();
        if (root == null) root = getSdCardFromExternalFilesDirs(context);
        if (root == null) return false;
        File dcim = new File(root, Environment.DIRECTORY_DCIM);
        if (!dcim.exists()) dcim.mkdirs();
        return dcim.exists() && dcim.canWrite();
    }

    private StorageHelper() {
    }
}

