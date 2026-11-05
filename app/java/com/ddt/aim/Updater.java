package com.ddt.aim;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** In-app updater: version.json from the latest GitHub release, download the APK, install over itself. */
public class Updater {
    static final String VERSION_URL =
            "https://github.com/ONISAN1999/ddt-aim/releases/latest/download/version.json";

    static void js(final WebView w, final String code) {
        if (w == null) return;
        w.post(new Runnable() {
            public void run() {
                try { w.evaluateJavascript(code, null); } catch (Exception ignored) { }
            }
        });
    }

    static HttpURLConnection open(String url) throws Exception {
        // follow redirects by hand (github.com -> objects.githubusercontent.com)
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "ddt-aim-app");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirect without location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            return c;
        }
        throw new Exception("too many redirects");
    }

    static void check(final WebView w) {
        new Thread(new Runnable() {
            public void run() {
                String s = "";
                try {
                    HttpURLConnection c = open(VERSION_URL + "?t=" + System.currentTimeMillis());
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                    in.close();
                    s = bo.toString("UTF-8");
                } catch (Exception e) {
                    s = "";
                }
                js(w, "window.ddtUpdateInfo&&ddtUpdateInfo(" + JSONObject.quote(s) + ")");
            }
        }).start();
    }

    static File apkFile(Context c) {
        return new File(c.getCacheDir(), "update.apk");
    }

    static void download(final Context ctx, final WebView w, final String url) {
        new Thread(new Runnable() {
            public void run() {
                File f = apkFile(ctx);
                try {
                    HttpURLConnection c = open(url);
                    long total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    OutputStream out = new FileOutputStream(f);
                    byte[] b = new byte[16384];
                    long got = 0;
                    int n, last = -1;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        got += n;
                        if (total > 0) {
                            int p = (int) (got * 100 / total);
                            if (p != last) { last = p; js(w, "ddtUpdateProgress(" + p + ")"); }
                        }
                    }
                    out.close();
                    in.close();
                    js(w, "ddtUpdateProgress(100)");
                    js(w, "ddtUpdateDone(true,'')");
                } catch (Exception e) {
                    f.delete();
                    js(w, "ddtUpdateDone(false," + JSONObject.quote("ดาวน์โหลดไม่สำเร็จ: " + e.getMessage()) + ")");
                }
            }
        }).start();
    }

    static void install(final Context ctx, final WebView w) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            js(w, "ddtInstallMsg('เปิด “อนุญาตจากแหล่งที่มานี้” ให้ DDT แรงยิง แล้วกลับมากด ‘ติดตั้งทับเลย’ อีกครั้ง')");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    File f = apkFile(ctx);
                    PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams p =
                            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    p.setSize(f.length());
                    int id = pi.createSession(p);
                    PackageInstaller.Session s = pi.openSession(id);
                    OutputStream out = s.openWrite("base.apk", 0, f.length());
                    InputStream in = new FileInputStream(f);
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    in.close();
                    s.fsync(out);
                    out.close();
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pend = PendingIntent.getBroadcast(ctx, id,
                            new Intent(ctx, InstallReceiver.class), flags);
                    s.commit(pend.getIntentSender());
                    s.close();
                } catch (Exception e) {
                    js(w, "ddtInstallMsg(" + JSONObject.quote("ติดตั้งไม่สำเร็จ: " + e.getMessage()) + ")");
                }
            }
        }).start();
    }
}
