package com.ddt.aim;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Keeps the last crash / error so it can be shown in Settings. */
public class CrashLog {
    private static File file;

    public static void install(Context c) {
        if (file != null) return;
        file = new File(c.getApplicationContext().getFilesDir(), "crash.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                note("CRASH", e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    public static void note(String where, Throwable e) {
        if (file == null) return;
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String s = sw.toString();
            if (s.length() > 3000) s = s.substring(0, 3000);
            String head = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " [" + where + "] " + android.os.Build.MODEL + " Android " + android.os.Build.VERSION.RELEASE + "\n";
            FileOutputStream o = new FileOutputStream(file, false);
            o.write((head + s).getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
    }

    public static String read() {
        if (file == null || !file.exists()) return "";
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] b = new byte[(int) Math.min(file.length(), 8000)];
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static void clear() {
        if (file != null) file.delete();
    }
}
