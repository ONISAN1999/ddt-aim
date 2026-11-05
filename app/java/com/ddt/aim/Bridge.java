package com.ddt.aim;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/** JS bridge exposed as window.DDT in both the main screen and the bubble panel. */
public class Bridge {
    public interface Host {
        void toggleBubble();
        void close();
        void capture();
        void pickLive(float fwr);
    }

    private final Context ctx;
    private final WebView web;
    private final Host host;

    public Bridge(Context ctx, WebView web, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.web = web;
        this.host = host;
    }

    private PackageInfo info() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (Exception e) {
            return null;
        }
    }

    private void onUi(final Runnable r) {
        web.post(new Runnable() {
            public void run() {
                try { r.run(); } catch (Throwable t) { CrashLog.note("bridge", t); }
            }
        });
    }

    @JavascriptInterface
    public String version() {
        PackageInfo p = info();
        return p == null ? "?" : p.versionName;
    }

    @JavascriptInterface
    @SuppressWarnings("deprecation")
    public int versionCode() {
        PackageInfo p = info();
        return p == null ? 0 : p.versionCode;
    }

    @JavascriptInterface
    public void checkUpdate() { Updater.check(web); }

    @JavascriptInterface
    public void downloadUpdate(String url) { Updater.download(ctx, web, url); }

    @JavascriptInterface
    public void installUpdate() { Updater.install(ctx, web); }

    @JavascriptInterface
    public void openUrl(final String url) {
        onUi(new Runnable() {
            public void run() {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                if (host != null) host.close();
            }
        });
    }

    @JavascriptInterface
    public String lastError() { return CrashLog.read(); }

    @JavascriptInterface
    public void clearError() { CrashLog.clear(); }

    @JavascriptInterface
    public void toggleBubble() {
        if (host != null) onUi(new Runnable() { public void run() { host.toggleBubble(); } });
    }

    @JavascriptInterface
    public void close() {
        if (host != null) onUi(new Runnable() { public void run() { host.close(); } });
    }

    @JavascriptInterface
    public void capture() {
        if (host != null) onUi(new Runnable() { public void run() { host.capture(); } });
    }

    @JavascriptInterface
    public void pickLive(final String fwr) {
        if (host == null) return;
        float f = 0;
        try { f = Float.parseFloat(fwr); } catch (Exception ignored) { }
        final float ff = f;
        onUi(new Runnable() { public void run() { host.pickLive(ff); } });
    }
}
