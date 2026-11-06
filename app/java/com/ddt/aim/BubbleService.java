package com.ddt.aim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.HapticFeedbackConstants;
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

/**
 * Bubble overlay.
 * Ball: tap = live map pick straight away · long-press = full panel · drag onto the bin = close.
 * HUD: small floating result bar next to the ball (index.html?hud=1), game stays touchable.
 */
public class BubbleService extends Service implements Bridge.Host {
    static boolean running = false;
    private static final int NID = 41;
    private static final String CH = "bubble";
    private static final int BALL_DP = 56, HUD_W = 330, HUD_H = 132;

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private ImageView ball;
    private WindowManager.LayoutParams ballLp;
    private TrashView trash;

    private AimOverlay aim;
    private float[] lastPick;            // {fw, sx, sy, tx, ty} screen px of the last map pick
    private WindowManager.LayoutParams trashLp;

    private FrameLayout hud;
    private WebView hudWeb;
    private WindowManager.LayoutParams hudLp;
    private boolean hudShown = false;

    private Panel panel;
    private WebView panelWeb;
    private View closeBtn;

    private MediaProjection proj;
    private boolean fgMedia = false;
    private float pendingPick = -1;
    private boolean pendingToHud = false;

    // live pick
    private LivePick pick;
    private boolean pickToHud = false;
    private HandlerThread capThread;
    private ImageReader reader;
    private VirtualDisplay vd;
    private Bitmap[] bufs = new Bitmap[2];
    private int bufIdx = 0;
    private long lastFrame = 0;
    private ByteBuffer pad;

    private static boolean isBlack(Bitmap b, int w, int h) {
        int lit = 0, n = 0;
        for (int gy = 1; gy < 10; gy++) for (int gx = 1; gx < 17; gx++) {
            int p = b.getPixel(w * gx / 17, h * gy / 10);
            n++;
            if (((p >> 16) & 255) > 18 || ((p >> 8) & 255) > 18 || (p & 255) > 18) lit++;
        }
        return lit < n / 20;
    }

    // ---------- small views ----------
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

    /** Bin at the bottom centre while the ball is dragged. */
    private class TrashView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean hot;
        TrashView(Context c) { super(c); }

        void setHot(boolean h) { if (h != hot) { hot = h; invalidate(); if (h) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), r = w / 2 - dp(2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(hot ? 0xF0FF453A : 0xD02C2C2E);
            c.drawCircle(w / 2, w / 2, hot ? r : r * 0.86f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.2f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.WHITE);
            float cx = w / 2, cy = w / 2, s = w * 0.17f;
            c.drawLine(cx - s * 1.15f, cy - s * 0.85f, cx + s * 1.15f, cy - s * 0.85f, p);   // lid
            c.drawLine(cx - s * 0.35f, cy - s * 1.15f, cx + s * 0.35f, cy - s * 1.15f, p);   // handle
            c.drawLine(cx - s * 0.85f, cy - s * 0.6f, cx - s * 0.65f, cy + s * 1.2f, p);     // can
            c.drawLine(cx + s * 0.85f, cy - s * 0.6f, cx + s * 0.65f, cy + s * 1.2f, p);
            c.drawLine(cx - s * 0.65f, cy + s * 1.2f, cx + s * 0.65f, cy + s * 1.2f, p);
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

    private DisplayMetrics real() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
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
                .setContentText("Bubble เปิดอยู่ · ลากลูกบอลไปที่ถังขยะเพื่อปิด")
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
        prefs = getSharedPreferences("ddt", MODE_PRIVATE);
        try {
            addAim();
            addHud();
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
                        final boolean toHud = pendingToHud;
                        pendingPick = -1;
                        ui.postDelayed(new Runnable() { public void run() { startPick(f, toHud); } }, 400);
                    } else if (proj == null) {
                        pickFailed("จับภาพหน้าจอไม่ได้");
                    }
                }
            } else if ("capfail".equals(a)) {
                pickFailed("ไม่ได้อนุญาตให้จับภาพหน้าจอ");
            } else {
                goForeground(0, null);
            }
        } catch (Throwable t) {
            CrashLog.note("onStartCommand", t);
        }
        return START_STICKY;
    }

    private void pickFailed(String msg) {
        pendingPick = -1;
        if (pendingToHud) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            if (hudShown) setHudVisible(true);
        } else {
            showPanel();
            Updater.js(panelWeb, "ddtShotFail(" + org.json.JSONObject.quote(msg) + ")");
        }
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
        ball.setContentDescription("DDT แรงยิง: แตะเพื่อวัดจากแผนที่ กดค้างเพื่อเปิดแผง");
        int sz = dp(BALL_DP);
        ballLp = new WindowManager.LayoutParams(sz, sz, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.LEFT;
        DisplayMetrics m = getResources().getDisplayMetrics();
        ballLp.x = prefs.getInt("ballX", m.widthPixels - sz - dp(8));
        ballLp.y = prefs.getInt("ballY", m.heightPixels / 3);
        ball.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved, longDone;
            final Runnable longPress = new Runnable() {
                public void run() {
                    longDone = true;
                    ball.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    try { expand(); } catch (Throwable t) { CrashLog.note("expand", t); collapse(); }
                }
            };

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = ballLp.x; oy = ballLp.y;
                        moved = false; longDone = false;
                        ui.postDelayed(longPress, 550);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (longDone) return true;
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.hypot(dx, dy) > dp(8)) {
                            moved = true;
                            ui.removeCallbacks(longPress);
                            showTrash(true);
                        }
                        if (moved && ball != null) {
                            ballLp.x = ox + (int) dx; ballLp.y = oy + (int) dy;
                            try { wm.updateViewLayout(ball, ballLp); } catch (Exception ignored) { }
                            trash.setHot(overTrash());
                            placeHud();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(longPress);
                        if (ball == null || longDone) return true;
                        if (moved) {
                            boolean bin = overTrash();
                            showTrash(false);
                            if (bin) {
                                Toast.makeText(BubbleService.this, "ปิด Bubble แล้ว", Toast.LENGTH_SHORT).show();
                                stopSelf();
                            } else {
                                snap();
                            }
                        } else if (e.getAction() == MotionEvent.ACTION_UP) {
                            onBallTap();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(ball, ballLp);

        trash = new TrashView(this);
        int ts = dp(68);
        trashLp = new WindowManager.LayoutParams(ts, ts, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        trashLp.gravity = Gravity.TOP | Gravity.LEFT;
        trash.setVisibility(View.GONE);
        wm.addView(trash, trashLp);
    }

    private void showTrash(boolean on) {
        if (trash == null) return;
        if (on) {
            DisplayMetrics m = real();
            int ts = dp(68);
            trashLp.x = (m.widthPixels - ts) / 2;
            trashLp.y = m.heightPixels - ts - dp(20);
            try { wm.updateViewLayout(trash, trashLp); } catch (Exception ignored) { }
            trash.setHot(false);
        }
        trash.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private boolean overTrash() {
        if (trash == null || trash.getVisibility() != View.VISIBLE) return false;
        int bs = dp(BALL_DP), ts = dp(68);
        float bx = ballLp.x + bs / 2f, by = ballLp.y + bs / 2f;
        float tx = trashLp.x + ts / 2f, ty = trashLp.y + ts / 2f;
        return Math.hypot(bx - tx, by - ty) < dp(64);
    }

    private void snap() {
        DisplayMetrics m = real();
        int sz = dp(BALL_DP);
        ballLp.x = (ballLp.x + sz / 2 < m.widthPixels / 2) ? dp(4) : m.widthPixels - sz - dp(4);
        ballLp.y = Math.max(0, Math.min(m.heightPixels - sz, ballLp.y));
        try { wm.updateViewLayout(ball, ballLp); } catch (Exception ignored) { }
        prefs.edit().putInt("ballX", ballLp.x).putInt("ballY", ballLp.y).apply();
        placeHud();
    }

    private void onBallTap() {
        if (pick != null) return;
        pickToHudStart(prefs.getFloat("fwr", 0));
    }

    // ---------- HUD ----------
    // ---------- aim overlay (path on the minimap + tick on the power bar) ----------
    private void addAim() {
        aim = new AimOverlay(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        cutout(lp);
        aim.setBar(prefs.getFloat("barL", 0), prefs.getFloat("barR", 0), prefs.getFloat("barY", 0));
        aim.setVisibility(View.INVISIBLE);
        wm.addView(aim, lp);
    }

    /** JSON from the HUD page: {p, hit, t:[tx,ty] battle px, pts:[[x,y],...] battle px from our tank, y up}. */
    private void onAim(String json) {
        if (aim == null) return;
        if (json == null || json.length() == 0) { aim.clear(); return; }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            int p = o.getInt("p");
            boolean hit = o.optBoolean("hit");
            float[] path = null;
            float tx = -1, ty = -1;
            org.json.JSONArray pts = o.optJSONArray("pts"), t = o.optJSONArray("t");
            if (lastPick != null && pts != null && t != null) {
                float k = lastPick[0] / 1000f, sx = lastPick[1], sy = lastPick[2];
                // only when the calculator still holds the picked target
                float btx = (lastPick[3] - sx) / k, bty = (sy - lastPick[4]) / k;
                if (Math.abs(btx - t.getDouble(0)) < 15 && Math.abs(bty - t.getDouble(1)) < 15) {
                    path = new float[pts.length() * 2];
                    for (int i = 0; i < pts.length(); i++) {
                        org.json.JSONArray q = pts.getJSONArray(i);
                        path[i * 2] = sx + (float) q.getDouble(0) * k;
                        path[i * 2 + 1] = sy - (float) q.getDouble(1) * k;
                    }
                    tx = lastPick[3]; ty = lastPick[4];
                }
            }
            aim.setAim(p, hit, path, tx, ty);
        } catch (Throwable e) {
            CrashLog.note("aim", e);
        }
    }

    private void setAimVisible(boolean on) {
        if (aim != null) aim.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private void doResetBar() {
        prefs.edit().remove("barL").remove("barR").remove("barY").apply();
        if (aim != null) aim.setBar(0, 0, 0);
        Toast.makeText(this, "ล้างตำแหน่งหลอดแรงแล้ว ครั้งต่อไปที่แตะลูกบอลจะให้แตะปลายหลอดใหม่", Toast.LENGTH_LONG).show();
    }

    private void addHud() {
        hud = new FrameLayout(this);
        hudWeb = new WebView(this);
        hudWeb.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = hudWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        hudWeb.addJavascriptInterface(new Bridge(this, hudWeb, new Bridge.Host() {
            public void toggleBubble() { stopSelf(); }
            public void close() { hudShown = false; setHudVisible(false); }
            public void capture() { onBallTap(); }
            public void pickLive(float fwr) { onBallTap(); }
            public void openPanel() { try { expand(); } catch (Throwable t) { CrashLog.note("expand", t); collapse(); } }
            public void install() { installViaMain(); }
            public void showAim(String json) { onAim(json); }
            public void resetBar() { doResetBar(); }
        }), "DDT");
        hudWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail d) {
                ui.post(new Runnable() { public void run() { rebuildHud(); } });
                return true;
            }
        });
        hud.addView(hudWeb, new FrameLayout.LayoutParams(-1, -1));
        hudLp = new WindowManager.LayoutParams(dp(HUD_W), dp(HUD_H), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        hudLp.gravity = Gravity.TOP | Gravity.LEFT;
        hud.setVisibility(View.INVISIBLE);
        wm.addView(hud, hudLp);
        hudWeb.loadUrl("file:///android_asset/index.html?hud=1");
    }

    private void rebuildHud() {
        boolean was = hudShown;
        try { wm.removeView(hud); } catch (Exception ignored) { }
        try { hudWeb.destroy(); } catch (Exception ignored) { }
        hud = null; hudWeb = null;
        addHud();
        if (was) { hudShown = true; setHudVisible(true); }
    }

    /** Hidden HUD stays loaded but lets touches through to the game. */
    private void setHudVisible(boolean on) {
        if (hud == null) return;
        if (on) hudLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else hudLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        placeHudLp();
        try { wm.updateViewLayout(hud, hudLp); } catch (Exception ignored) { }
        hud.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        setAimVisible(on);
    }

    private void placeHudLp() {
        if (ballLp == null) return;
        DisplayMetrics m = real();
        int bs = dp(BALL_DP), hw = dp(HUD_W), hh = dp(HUD_H), g = dp(8);
        boolean right = ballLp.x + bs / 2 > m.widthPixels / 2;
        hudLp.x = right ? ballLp.x - hw - g : ballLp.x + bs + g;
        hudLp.x = Math.max(0, Math.min(m.widthPixels - hw, hudLp.x));
        hudLp.y = Math.max(0, Math.min(m.heightPixels - hh, ballLp.y + bs / 2 - hh / 2));
    }

    private void placeHud() {
        if (hud == null || hud.getVisibility() != View.VISIBLE) return;
        placeHudLp();
        try { wm.updateViewLayout(hud, hudLp); } catch (Exception ignored) { }
    }

    // ---------- full panel ----------
    private void expand() {
        if (panel != null || pick != null) return;
        ball.setVisibility(View.GONE);
        setHudVisible(false);
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
        if (pick != null) endPick(null);
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            if (panelWeb != null) { try { panelWeb.destroy(); } catch (Exception ignored) { } panelWeb = null; }
            panel = null;
            closeBtn = null;
            Updater.js(hudWeb, "window.ddtReload&&ddtReload()");   // pick up changes made in the panel
            if (hudShown) setHudVisible(true);
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
    public void capture() { pickLive(prefs.getFloat("fwr", 0)); }

    @Override
    public void openPanel() { }

    @Override
    public void install() { installViaMain(); }

    @Override
    public void showAim(String json) { }

    @Override
    public void resetBar() { doResetBar(); }

    /** The overlay cannot rotate the screen: hand the install to the main screen, which turns portrait. */
    private void installViaMain() {
        collapse();
        Intent i = new Intent(this, MainActivity.class).setAction("ddt.install");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ---------- live map pick ----------
    /** From the full panel's map tab: result goes back to the panel. */
    @Override
    public void pickLive(float fwr) { requestPick(fwr, false); }

    /** From the ball: result goes to the floating HUD. */
    private void pickToHudStart(float fwr) { requestPick(fwr, true); }

    private void requestPick(float fwr, boolean toHud) {
        if (pick != null) return;
        hidePanel();
        setHudVisible(false);
        if (proj == null) {
            pendingPick = fwr;
            pendingToHud = toHud;
            Intent i = new Intent(this, ScreenCapActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
            return;
        }
        final float f = fwr;
        final boolean h = toHud;
        ui.postDelayed(new Runnable() { public void run() { startPick(f, h); } }, toHud ? 30 : 150);
    }

    private void startPick(final float fwr, final boolean toHud) {
        DisplayMetrics m = real();
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
            requestPick(fwr, toHud);
            return;
        }
        pickToHud = toHud;
        pick = new LivePick(this, fwr, w, aim == null || !aim.hasBar(), new LivePick.Cb() {
            public void done(float fw, float sx, float sy, float tx, float ty, int sw, float[] bar) {
                if (sw > 0 && fw > 3) prefs.edit().putFloat("fwr", fw / sw).apply();
                if (bar != null) {
                    prefs.edit().putFloat("barL", bar[0]).putFloat("barR", bar[1]).putFloat("barY", bar[2]).apply();
                    if (aim != null) aim.setBar(bar[0], bar[1], bar[2]);
                }
                lastPick = new float[]{fw, sx, sy, tx, ty};
                endPick("ddtPickDone(" + fw + "," + sx + "," + sy + "," + tx + "," + ty + "," + sw + ")");
            }
            public void cancel() { endPick(null); }
            public void reauth() {
                final boolean h = pickToHud;
                endPick(null);
                if (proj != null) { try { proj.stop(); } catch (Exception ignored) { } }
                proj = null;
                requestPick(fwr, h);
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        cutout(lp);
        setAimVisible(false);                // never let the overlay into the capture
        wm.addView(pick, lp);
        pick.requestFocus();
        if (ball != null) ball.setVisibility(View.INVISIBLE);   // keep the ball off the minimap while picking
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
            int need = b.getRowBytes() * h;
            if (buf.remaining() < need) {          // last row is not padded on some devices
                if (pad == null || pad.capacity() != need) pad = ByteBuffer.allocateDirect(need);
                pad.clear();
                pad.put(buf);
                while (pad.position() < need) pad.put((byte) 0);
                pad.flip();
                buf = pad;
            }
            synchronized (p.lock()) { b.copyPixelsFromBuffer(buf); }
            bufIdx ^= 1;
            p.setFrame(b, now, isBlack(b, w, h));
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

    private void endPick(String js) {
        if (pick != null) {
            try { wm.removeView(pick); } catch (Exception ignored) { }
            pick = null;
        }
        stopCapture();
        if (ball != null && panel == null) ball.setVisibility(View.VISIBLE);
        if (pickToHud) {
            if (js != null) {
                Updater.js(hudWeb, js);
                hudShown = true;
            }
            if (hudShown) setHudVisible(true);
        } else {
            showPanel();
            if (js != null) Updater.js(panelWeb, js);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        collapse();
        if (pick != null) endPick(null);
        for (View v : new View[]{ball, trash, hud, aim}) {
            if (v != null) { try { wm.removeView(v); } catch (Exception ignored) { } }
        }
        if (hudWeb != null) { try { hudWeb.destroy(); } catch (Exception ignored) { } }
        ball = null; trash = null; hud = null; hudWeb = null;
        if (proj != null) { try { proj.stop(); } catch (Exception ignored) { } proj = null; }
        super.onDestroy();
    }
}
