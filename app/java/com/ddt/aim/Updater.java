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

    /** Fallback when github.com itself fails (seen: 504 on every page while the API still works). */
    static final String API_LATEST = "https://api.github.com/repos/ONISAN1999/ddt-aim/releases/latest";
    static volatile String apiApkUrl;          // API asset url of the newest APK, found by check()

    static HttpURLConnection open(String url) throws Exception { return open(url, null); }

    static String readAll(HttpURLConnection c) throws Exception {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** version.json through the GitHub API (assets are fetched with Accept: application/octet-stream). */
    static String viaApi() throws Exception {
        org.json.JSONObject rel = new org.json.JSONObject(readAll(open(API_LATEST, "application/vnd.github+json")));
        org.json.JSONArray as = rel.getJSONArray("assets");
        String ver = null;
        for (int i = 0; i < as.length(); i++) {
            org.json.JSONObject a = as.getJSONObject(i);
            if ("version.json".equals(a.optString("name"))) ver = a.getString("url");
            if ("ddt-aim.apk".equals(a.optString("name"))) apiApkUrl = a.getString("url");
        }
        if (ver == null) throw new Exception("no version.json asset");
        return readAll(open(ver, "application/octet-stream"));
    }

    static HttpURLConnection open(String url, String accept) throws Exception {
        // follow redirects by hand (github.com -> objects.githubusercontent.com)
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "ddt-aim-app");
            if (accept != null && url.contains("api.github.com")) c.setRequestProperty("Accept", accept);
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
                    s = readAll(open(VERSION_URL + "?t=" + System.currentTimeMillis()));
                    new org.json.JSONObject(s);                 // github.com error pages are HTML
                } catch (Exception e) {
                    try { s = viaApi(); } catch (Exception e2) { s = ""; }
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
                    HttpURLConnection c;
                    try {
                        c = open(url);
                        String ct = c.getContentType();
                        if (ct != null && ct.startsWith("text/html")) throw new Exception("html");   // error page, not the APK
                    } catch (Exception e) {
                        if (apiApkUrl == null) { try { viaApi(); } catch (Exception ignored) { } }
                        if (apiApkUrl == null) throw e;
                        c = open(apiApkUrl, "application/octet-stream");
                    }
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
