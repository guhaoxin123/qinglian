package io.ghx.vpn.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 统一日志：logcat(tag=LitLink) + 外部文件 litlink.log，便于用户反馈问题。 */
public final class Log {

    private static final String TAG = "LitLink";
    private static final Object LOCK = new Object();

    private static Context appCtx;
    private static File logFile;

    private Log() {
    }

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        File dir = appCtx.getExternalFilesDir(null);
        if (dir != null) {
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, "litlink.log");
        }
    }

    public static void d(String msg) {
        android.util.Log.d(TAG, msg);
        write("D", msg);
    }

    public static void i(String msg) {
        android.util.Log.i(TAG, msg);
        write("I", msg);
    }

    public static void w(String msg, Throwable t) {
        android.util.Log.w(TAG, msg, t);
        write("W", msg + (t == null ? "" : " | " + t.getClass().getSimpleName() + ": " + t.getMessage()));
    }

    public static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
        write("E", msg + (t == null ? "" : " | " + t.getClass().getSimpleName() + ": " + t.getMessage()));
    }

    private static void write(String level, String msg) {
        synchronized (LOCK) {
            if (logFile == null) return;
            try {
                String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date()) + " " + level + " " + msg + "\n";
                FileOutputStream fo = new FileOutputStream(logFile, true);
                OutputStreamWriter w = new OutputStreamWriter(fo, "UTF-8");
                w.write(line);
                w.close();
                fo.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 清空日志（用户主动清理时用）。 */
    public static void clear() {
        synchronized (LOCK) {
            if (logFile != null && logFile.exists()) logFile.delete();
        }
    }
}
