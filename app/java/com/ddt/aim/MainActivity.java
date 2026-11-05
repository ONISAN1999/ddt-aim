package com.ddt.aim;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class MainActivity extends Activity implements Bridge.Host {
    private static final int REQ_FILE = 5555;
    private static final int REQ_CAP = 7778;
    private FrameLayout root;
    private WebView web;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        immersive();
        initWeb();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    private void initWeb() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(this, web, this), "DDT");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail d) {
                root.removeView(web);
                web.destroy();
                initWeb();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "เลือกรูปแคปหน้าจอเกม"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_CAP) {
            startBubble(res == RESULT_OK ? data : null, res);
            return;
        }
        if (req == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCb.onReceiveValue(r);
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.ddtBack?ddtBack():false", new ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (!"true".equals(v)) moveTaskToBack(true);
            }
        });
    }

    @Override
    public void toggleBubble() {
        if (BubbleService.running) {
            stopService(new Intent(this, BubbleService.class));
            Toast.makeText(this, "ปิด Bubble แล้ว", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "อนุญาต “แสดงทับแอปอื่น” ให้ DDT แรงยิง แล้วกดปุ่ม Bubble อีกครั้ง", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        // ask for screen capture first: the live map picker needs it, and the
        // foreground service can then start with a valid media-projection token
        try {
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Toast.makeText(this, "อนุญาตการจับภาพหน้าจอ เพื่อใช้เลือกจากแผนที่แบบจอสด", Toast.LENGTH_SHORT).show();
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAP);
        } catch (Throwable t) {
            CrashLog.note("capIntent", t);
            startBubble(null, RESULT_CANCELED);
        }
    }

    private void startBubble(Intent capData, int code) {
        try {
            Intent i = new Intent(this, BubbleService.class).setAction("start");
            if (capData != null) {
                i.putExtra("code", code);
                i.putExtra("data", capData);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            } else {
                startService(i);
                Toast.makeText(this, "ไม่ได้อนุญาตจับภาพจอ: ใช้กดเลขได้ ส่วนแผนที่จะขอสิทธิ์อีกครั้งตอนกด", Toast.LENGTH_LONG).show();
            }
            Toast.makeText(this, "เปิด Bubble แล้ว สลับไปที่เกมได้เลย", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
        } catch (Throwable t) {
            CrashLog.note("startBubble", t);
            Toast.makeText(this, "เปิด Bubble ไม่สำเร็จ: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void close() { moveTaskToBack(true); }

    @Override
    public void capture() { }

    @Override
    public void pickLive(float fwr) { }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
