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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
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
    private View closeBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private MediaProjection proj;
    private boolean fgMedia = false;
    private float pendingPick = -1;

    // live pick
    private LivePick pick;
    private HandlerThread capThread;
    private ImageReader reader;
    private VirtualDisplay vd;
    private Bitmap[] bufs = new Bitmap[2];
    private int bufIdx = 0;
    private long lastFrame = 0;

    /** Panel container that hands the Back key to the page. */
    private class Panel extends FrameLayout {
        Panel(Context c) { super(c); }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (panelWeb == null) { collapse(); return true; }
                    panelWeb.evaluateJavascript("window.ddtBack?ddtBack():false", new ValueCallback<String>() {
                        public void onReceiveValue(String v) { if (!"true".equals(v)) collapse(); }
                    });
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    /** Native close button drawn exactly over the page's ✕ slot — works even if the page misbehaves. */
    private class CloseBtn extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CloseBtn(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), r = w / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2C2C2E);
            c.drawCircle(r, r, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(w * 0.07f);
            p.setColor(0xFFAEAEB2);
            float a = w * 0.33f, b = w * 0.67f;
            c.drawLine(a, a, b, b, p);
            c.drawLine(b, a, a, b, p);
        }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private static void cutout(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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
        CrashLog.install(this);
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            addBall();
        } catch (Throwable t) {
            CrashLog.note("addBall", t);
            Toast.makeText(this, "แสดง Bubble ไม่ได้: " + t.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        try {
            if ("cap".equals(a) || "start".equals(a)) {
                Intent data = intent.getParcelableExtra("data");
                goForeground(intent.getIntExtra("code", 0), data);
                if ("cap".equals(a)) {
                    if (proj != null && pendingPick >= 0) {
                        final float f = pendingPick;
                        pendingPick = -1;
                        ui.postDelayed(new Runnable() { public void run() { startPick(f); } }, 400);
                    } else if (proj == null) {
                        pendingPick = -1;
                        showPanel();
                        Updater.js(panelWeb, "ddtShotFail('จับภาพหน้าจอไม่ได้')");
                    }
                }
            } else if ("capfail".equals(a)) {
                pendingPick = -1;
                showPanel();
                Updater.js(panelWeb, "ddtShotFail('ไม่ได้อนุญาตให้จับภาพหน้าจอ')");
            } else {
                goForeground(0, null);
            }
        } catch (Throwable t) {
            CrashLog.note("onStartCommand", t);
        }
        return START_STICKY;
    }

    private void goForeground(int code, Intent data) {
        if (data != null) {
            try {
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(NID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                else
                    startForeground(NID, notif());
                fgMedia = true;
                MediaProjectionManager m = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                proj = m.getMediaProjection(code, data);
                proj.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() { proj = null; stopCapture(); }
                }, ui);
                return;
            } catch (Throwable t) {
                CrashLog.note("projection", t);
                proj = null;
            }
        }
        if (!fgMedia) {
            try { startForeground(NID, notif()); } catch (Throwable t) { CrashLog.note("startForeground", t); }
        }
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
                    moved = true;
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
                        if (moved && ball != null) {
                            ballLp.x = ox + (int) dx; ballLp.y = oy + (int) dy;
                            try { wm.updateViewLayout(ball, ballLp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(longPress);
                        if (ball == null) return true;
                        if (moved) snap();
                        else if (e.getAction() == MotionEvent.ACTION_UP) {
                            try { expand(); } catch (Throwable t) { CrashLog.note("expand", t); collapse(); }
                        }
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

        closeBtn = new CloseBtn(this);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { collapse(); }
        });
        closeBtn.setContentDescription("ย่อกลับเป็น Bubble");
        panel.addView(closeBtn, new FrameLayout.LayoutParams(dp(36), dp(36)));
        panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                placeClose(r - l, b - t);
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        cutout(lp);
        wm.addView(panel, lp);
        panelWeb.loadUrl("file:///android_asset/index.html?bubble=1");
    }

    /** Same maths as fit() in index.html: 844×390 stage scaled to fit; ✕ centre at stage (810, 30). */
    private void placeClose(int w, int h) {
        if (closeBtn == null || w == 0) return;
        float dens = getResources().getDisplayMetrics().density;
        float cw = w / dens, ch = h / dens;
        float s = Math.min(cw / 844f, ch / 390f);
        float ox = (cw - 844 * s) / 2, oy = (ch - 390 * s) / 2;
        int size = Math.round(36 * s * dens);
        int cx = Math.round((ox + 810 * s) * dens), cy = Math.round((oy + 30 * s) * dens);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) closeBtn.getLayoutParams();
        if (p.width == size && p.leftMargin == cx - size / 2 && p.topMargin == cy - size / 2) return;
        p.width = size; p.height = size;
        p.leftMargin = cx - size / 2; p.topMargin = cy - size / 2;
        p.gravity = Gravity.TOP | Gravity.LEFT;
        closeBtn.setLayoutParams(p);
    }

    private void collapse() {
        if (pick != null) endPick(false, null);
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            if (panelWeb != null) { try { panelWeb.destroy(); } catch (Exception ignored) { } panelWeb = null; }
            panel = null;
            closeBtn = null;
        }
        if (ball != null) ball.setVisibility(View.VISIBLE);
    }

    private void hidePanel() { if (panel != null) panel.setVisibility(View.GONE); }

    private void showPanel() { if (panel != null) panel.setVisibility(View.VISIBLE); }

    @Override
    public void toggleBubble() { stopSelf(); }

    @Override
    public void close() { collapse(); }

    @Override
    public void capture() { pickLive(0); }

    // ---------- live map pick ----------
    @Override
    public void pickLive(float fwr) {
        if (pick != null) return;
        hidePanel();
        if (proj == null) {
            pendingPick = fwr;
            Intent i = new Intent(this, ScreenCapActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
            return;
        }
        ui.postDelayed(new Runnable() { public void run() { startPick(fwr); } }, 150);
    }

    private void startPick(float fwr) {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        final int w = m.widthPixels, h = m.heightPixels;
        try {
            capThread = new HandlerThread("ddt-cap");
            capThread.start();
            Handler ch = new Handler(capThread.getLooper());
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) { onFrame(r, w, h); }
            }, ch);
            vd = proj.createVirtualDisplay("ddt-live", w, h, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ch);
        } catch (Throwable t) {
            CrashLog.note("startPick", t);
            stopCapture();
            proj = null;                 // token unusable: ask again
            pickLive(fwr);
            return;
        }
        pick = new LivePick(this, fwr, w, new LivePick.Cb() {
            public void done(float fw, float sx, float sy, float tx, float ty, int sw) {
                endPick(true, "ddtPickDone(" + fw + "," + sx + "," + sy + "," + tx + "," + ty + "," + sw + ")");
            }
            public void cancel() { endPick(false, null); }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        cutout(lp);
        wm.addView(pick, lp);
        pick.requestFocus();
    }

    private void onFrame(ImageReader r, int w, int h) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            LivePick p = pick;
            long now = SystemClock.uptimeMillis();
            if (p == null || (!p.wantsFrames() && now - lastFrame < 400) || now - lastFrame < 60) return;
            lastFrame = now;
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int bw = pl.getRowStride() / pl.getPixelStride();
            Bitmap b = bufs[bufIdx];
            if (b == null || b.getWidth() != bw || b.getHeight() != h) {
                b = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
                bufs[bufIdx] = b;
            }
            buf.rewind();
            synchronized (p.lock()) { b.copyPixelsFromBuffer(buf); }
            bufIdx ^= 1;
            p.setFrame(b);
        } catch (Throwable t) {
            CrashLog.note("frame", t);
        } finally {
            if (img != null) img.close();
        }
    }

    private void stopCapture() {
        if (vd != null) { try { vd.release(); } catch (Exception ignored) { } vd = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) { } reader = null; }
        if (capThread != null) { capThread.quitSafely(); capThread = null; }
        bufs = new Bitmap[2];
    }

    private void endPick(boolean ok, String js) {
        if (pick != null) {
            try { wm.removeView(pick); } catch (Exception ignored) { }
            pick = null;
        }
        stopCapture();
        showPanel();
        if (js != null) Updater.js(panelWeb, js);
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
