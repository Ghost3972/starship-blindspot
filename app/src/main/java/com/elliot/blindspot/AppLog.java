package com.elliot.blindspot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志工具类
 */
public class AppLog {
    private static final String PREFIX = "TBS_";

    private static final Object FILE_LOCK = new Object();
    private static volatile boolean fileLoggingEnabled = false;
    private static Context appContext;
    private static HandlerThread fileThread;
    private static Handler fileHandler;
    private static BufferedWriter fileWriter;
    private static Uri fileUri;
    private static String lastSavedPath;
    private static volatile boolean mediaStorePending;
    private static volatile Process logcatCaptureProcess;
    private static Thread logcatCaptureThread;

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
    }

    public static String startFileLogging(Context context) {
        if (context != null) {
            init(context);
        }
        synchronized (FILE_LOCK) {
            if (fileLoggingEnabled) {
                return lastSavedPath;
            }
            if (appContext == null) {
                return null;
            }

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "blindspot_log_" + ts + ".txt";

            try {
                OutputStream os;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = appContext.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        return null;
                    }
                    fileUri = uri;
                    mediaStorePending = true;
                    os = resolver.openOutputStream(uri, "w");
                    lastSavedPath = "Downloads/" + fileName;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (dir != null && !dir.exists()) {
                        dir.mkdirs();
                    }
                    File file = new File(dir, fileName);
                    os = new FileOutputStream(file, false);
                    lastSavedPath = file.getAbsolutePath();
                }
                if (os == null) {
                    return null;
                }

                if (fileThread == null) {
                    fileThread = new HandlerThread("AppLogFileWriter");
                    fileThread.start();
                    fileHandler = new Handler(fileThread.getLooper());
                }
                fileWriter = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
                fileLoggingEnabled = true;
                writeLineLocked("I", PREFIX + "AppLog", "File logging started: " + lastSavedPath, null);
                startLogcatCaptureLocked();
                return lastSavedPath;
            } catch (Exception e) {
                safeCloseWriterLocked();
                return null;
            }
        }
    }

    public static String stopFileLogging(Context context) {
        if (context != null) {
            init(context);
        }
        synchronized (FILE_LOCK) {
            if (!fileLoggingEnabled) {
                return lastSavedPath;
            }
            writeLineLocked("I", PREFIX + "AppLog", "File logging stopped", null);
            stopLogcatCaptureLocked();
            safeCloseWriterLocked();
            fileLoggingEnabled = false;
            finalizeMediaStoreFileLocked();
            return lastSavedPath;
        }
    }
    
    public static void d(String tag, String message) {
        String t = PREFIX + tag;
        Log.d(t, message);
        writeLine("D", t, message, null);
    }
    
    public static void i(String tag, String message) {
        String t = PREFIX + tag;
        Log.i(t, message);
        writeLine("I", t, message, null);
    }
    
    public static void w(String tag, String message) {
        String t = PREFIX + tag;
        Log.w(t, message);
        writeLine("W", t, message, null);
    }
    
    public static void e(String tag, String message) {
        String t = PREFIX + tag;
        Log.e(t, message);
        writeLine("E", t, message, null);
    }
    
    public static void e(String tag, String message, Throwable t) {
        String tg = PREFIX + tag;
        Log.e(tg, message, t);
        writeLine("E", tg, message, t);
    }

    private static void writeLine(String level, String tag, String message, Throwable t) {
        if (!fileLoggingEnabled) return;
        Handler h;
        synchronized (FILE_LOCK) {
            if (!fileLoggingEnabled || fileWriter == null || fileHandler == null) return;
            h = fileHandler;
        }
        h.post(() -> writeLineLocked(level, tag, message, t));
    }

    private static void writeLineLocked(String level, String tag, String message, Throwable t) {
        BufferedWriter w;
        synchronized (FILE_LOCK) {
            if (!fileLoggingEnabled || fileWriter == null) return;
            w = fileWriter;
        }

        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            w.write(ts);
            w.write(" ");
            w.write(level);
            w.write("/");
            w.write(tag);
            w.write(": ");
            if (message != null) {
                w.write(message);
            }
            w.newLine();
            if (t != null) {
                String stack = Log.getStackTraceString(t);
                if (stack != null && !stack.isEmpty()) {
                    String[] lines = stack.split("\n");
                    for (String line : lines) {
                        w.write(ts);
                        w.write(" ");
                        w.write(level);
                        w.write("/");
                        w.write(tag);
                        w.write(": ");
                        w.write(line);
                        w.newLine();
                    }
                }
            }
            w.flush();
        } catch (Exception ignored) {
        }
    }

    private static void writeRawLocked(String rawLine) {
        BufferedWriter w;
        synchronized (FILE_LOCK) {
            if (!fileLoggingEnabled || fileWriter == null) return;
            w = fileWriter;
        }
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            w.write(ts);
            w.write(" L/LOGCAT: ");
            if (rawLine != null) {
                w.write(rawLine);
            }
            w.newLine();
            w.flush();
        } catch (Exception ignored) {
        }
    }

    private static void safeCloseWriterLocked() {
        BufferedWriter w = fileWriter;
        fileWriter = null;
        if (w != null) {
            try {
                w.flush();
            } catch (Exception ignored) {}
            try {
                w.close();
            } catch (Exception ignored) {}
        }
    }

    private static void startLogcatCaptureLocked() {
        if (logcatCaptureThread != null) {
            return;
        }
        if (appContext == null) {
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "logcat",
                    "-v", "time",
                    "-s",
                    "TURNING_DEBUG:*",
                    "RESOURCE_MONITOR:*",
                    "AndroidRuntime:*"
            });
            logcatCaptureProcess = p;
            Thread t = new Thread(() -> runLogcatCapture(p), "AppLogcatCapture");
            logcatCaptureThread = t;
            t.start();
        } catch (Exception ignored) {
        }
    }

    private static void stopLogcatCaptureLocked() {
        Process p = logcatCaptureProcess;
        logcatCaptureProcess = null;
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        Thread t = logcatCaptureThread;
        logcatCaptureThread = null;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Exception ignored) {
            }
        }
    }

    private static void runLogcatCapture(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while (fileLoggingEnabled && (line = r.readLine()) != null) {
                Handler h;
                synchronized (FILE_LOCK) {
                    if (!fileLoggingEnabled || fileWriter == null || fileHandler == null) return;
                    h = fileHandler;
                }
                final String l0 = line;
                h.post(() -> writeRawLocked(l0));
            }
        } catch (Exception ignored) {
        }
    }

    private static void finalizeMediaStoreFileLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (!mediaStorePending || fileUri == null || appContext == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            appContext.getContentResolver().update(fileUri, values, null, null);
        } catch (Exception ignored) {
        } finally {
            mediaStorePending = false;
            fileUri = null;
        }
    }
}
