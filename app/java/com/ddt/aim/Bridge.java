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
    public void checkUpdate() {
        Updater.check(web);
    }

    @JavascriptInterface
    public void downloadUpdate(String url) {
        Updater.download(ctx, web, url);
    }

    @JavascriptInterface
    public void installUpdate() {
        Updater.install(ctx, web);
    }

    @JavascriptInterface
    public void toggleBubble() {
        if (host != null) web.post(new Runnable() { public void run() { host.toggleBubble(); } });
    }

    @JavascriptInterface
    public void close() {
        if (host != null) web.post(new Runnable() { public void run() { host.close(); } });
    }

    @JavascriptInterface
    public void capture() {
        if (host != null) web.post(new Runnable() { public void run() { host.capture(); } });
    }
}
