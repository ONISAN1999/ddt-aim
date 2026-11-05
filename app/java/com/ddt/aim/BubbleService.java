package com.ddt.aim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class BubbleService extends Service implements Bridge.Host {
    static boolean running = false;
    private static final int NID = 41;
    private static final String CH = "bubble";

    private WindowManager wm;
    private ImageView ball;
    private WindowManager.LayoutParams ballLp;
    private Panel panel;
    private WebView panelWeb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private MediaProjection proj;
    private boolean capturing = false;

    /** Panel container that hands the Back key to the page. */
    private class Panel extends FrameLayout {
        Panel(Context c) { super(c); }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP && panelWeb != null) {
                    panelWeb.evaluateJavascript("window.ddtBack?ddtBack():false", new ValueCallback<String>() {
                        public void onReceiveValue(String v) { if (!"true".equals(v)) collapse(); }
                    });
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    @SuppressWarnings("deprecation")
    private Notification notif() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH, "Bubble", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), fl);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("DDT แรงยิง")
                .setContentText("Bubble เปิดอยู่ · กดค้างที่ลูกบอลเพื่อปิด")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(NID, notif());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addBall();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if ("cap".equals(a)) {
            onCapGranted(intent.getIntExtra("code", 0), (Intent) intent.getParcelableExtra("data"));
        } else if ("capfail".equals(a)) {
            capturing = false;
            showPanel();
            Updater.js(panelWeb, "ddtShotFail('ไม่ได้อนุญาตให้แคปหน้าจอ')");
        }
        return START_STICKY;
    }

    // ---------- ball ----------
    private void addBall() {
        ball = new ImageView(this);
        ball.setImageResource(R.drawable.ball);
        ball.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int sz = dp(56);
        ballLp = new WindowManager.LayoutParams(sz, sz, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.LEFT;
        DisplayMetrics m = getResources().getDisplayMetrics();
        ballLp.x = m.widthPixels - sz - dp(8);
        ballLp.y = m.heightPixels / 3;
        ball.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved;
            final Runnable longPress = new Runnable() {
                public void run() {
                    Toast.makeText(BubbleService.this, "ปิด Bubble แล้ว", Toast.LENGTH_SHORT).show();
                    stopSelf();
                }
            };

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = ballLp.x; oy = ballLp.y; moved = false;
                        ui.postDelayed(longPress, 800);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.hypot(dx, dy) > dp(8)) { moved = true; ui.removeCallbacks(longPress); }
                        if (moved) {
                            ballLp.x = ox + (int) dx; ballLp.y = oy + (int) dy;
                            try { wm.updateViewLayout(ball, ballLp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(longPress);
                        if (moved) snap();
                        else if (e.getAction() == MotionEvent.ACTION_UP) expand();
                        return true;
                }
                return false;
            }
        });
        wm.addView(ball, ballLp);
    }

    private void snap() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int sz = dp(56);
        ballLp.x = (ballLp.x + sz / 2 < m.widthPixels / 2) ? dp(4) : m.widthPixels - sz - dp(4);
        ballLp.y = Math.max(0, Math.min(m.heightPixels - sz, ballLp.y));
        try { wm.updateViewLayout(ball, ballLp); } catch (Exception ignored) { }
    }

    // ---------- panel ----------
    private void expand() {
        if (panel != null) return;
        ball.setVisibility(View.GONE);
        panel = new Panel(this);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panelWeb = new WebView(this);
        panelWeb.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = panelWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        panelWeb.addJavascriptInterface(new Bridge(this, panelWeb, this), "DDT");
        panelWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail d) {
                ui.post(new Runnable() { public void run() { collapse(); } });
                return true;
            }
        });
        panel.addView(panelWeb, new FrameLayout.LayoutParams(-1, -1));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        wm.addView(panel, lp);
        panelWeb.loadUrl("file:///android_asset/index.html?bubble=1");
    }

    private void collapse() {
        if (panel == null) return;
        try { wm.removeView(panel); } catch (Exception ignored) { }
        if (panelWeb != null) { panelWeb.destroy(); panelWeb = null; }
        panel = null;
        if (ball != null) ball.setVisibility(View.VISIBLE);
    }

    private void hidePanel() { if (panel != null) panel.setVisibility(View.INVISIBLE); }

    private void showPanel() { if (panel != null) panel.setVisibility(View.VISIBLE); }

    @Override
    public void toggleBubble() { stopSelf(); }

    @Override
    public void close() { collapse(); }

    // ---------- screen capture ----------
    @Override
    public void capture() {
        if (capturing) return;
        capturing = true;
        hidePanel();
        if (proj == null) {
            Intent i = new Intent(this, ScreenCapActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
        } else {
            ui.postDelayed(new Runnable() { public void run() { grab(); } }, 350);
        }
    }

    private void onCapGranted(int code, Intent data) {
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            proj = m.getMediaProjection(code, data);
            proj.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() { proj = null; }
            }, ui);
            ui.postDelayed(new Runnable() { public void run() { grab(); } }, 600);
        } catch (Exception e) {
            proj = null;
            fail("แคปจอไม่สำเร็จ: " + e.getMessage());
        }
    }

    private void fail(String msg) {
        capturing = false;
        showPanel();
        Updater.js(panelWeb, "ddtShotFail(" + org.json.JSONObject.quote(msg) + ")");
    }

    private void grab() {
        if (proj == null) { capturing = false; capture(); return; }
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        final int w = m.widthPixels, h = m.heightPixels;
        final ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        final VirtualDisplay vd;
        try {
            vd = proj.createVirtualDisplay("ddt-shot", w, h, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ui);
        } catch (Exception e) {
            reader.close();
            proj = null;          // token used up: ask again next time
            capturing = false;
            capture();
            return;
        }
        final int[] tries = {0};
        ui.postDelayed(new Runnable() {
            public void run() {
                Image img = null;
                try { img = reader.acquireLatestImage(); } catch (Exception ignored) { }
                if (img == null && ++tries[0] < 15) { ui.postDelayed(this, 90); return; }
                Bitmap bmp = null;
                if (img != null) {
                    try {
                        Image.Plane p = img.getPlanes()[0];
                        ByteBuffer buf = p.getBuffer();
                        int ps = p.getPixelStride(), rs = p.getRowStride();
                        Bitmap full = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888);
                        full.copyPixelsFromBuffer(buf);
                        bmp = (full.getWidth() == w) ? full : Bitmap.createBitmap(full, 0, 0, w, h);
                    } catch (Exception ignored) { }
                    img.close();
                }
                vd.release();
                reader.close();
                capturing = false;
                showPanel();
                if (bmp == null) { fail("แคปจอไม่สำเร็จ ลองใหม่อีกครั้ง"); return; }
                deliver(bmp);
            }
        }, 120);
    }

    private void deliver(final Bitmap src) {
        new Thread(new Runnable() {
            public void run() {
                Bitmap b = src;
                int maxW = 2400;
                if (b.getWidth() > maxW) b = Bitmap.createScaledBitmap(b, maxW, Math.round(b.getHeight() * (maxW / (float) b.getWidth())), true);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 88, bo);
                String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                Updater.js(panelWeb, "ddtShot('" + b64 + "')");
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        running = false;
        collapse();
        if (ball != null) { try { wm.removeView(ball); } catch (Exception ignored) { } ball = null; }
        if (proj != null) { try { proj.stop(); } catch (Exception ignored) { } proj = null; }
        super.onDestroy();
    }
}
