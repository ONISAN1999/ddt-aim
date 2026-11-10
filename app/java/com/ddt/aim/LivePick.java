package com.ddt.aim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Transparent layer over the live game. Drag a finger: a 4x magnifier shows the
 * live screen around it; lift to drop the point. Points: frame left edge, frame
 * right edge (both skipped when remembered), self, target.
 */
public class LivePick extends View {
    public interface Cb {
        /** bar = {left x, right x, y} of the game's power bar in screen px, or null when not measured this time. */
        void done(float fw, float sx, float sy, float tx, float ty, int screenW, float[] bar);
        void cancel();
        void reauth();
    }

    private static final String[] LBL = {
            "ขอบซ้ายของกรอบจอสีขาว (ในแผนที่ย่อ)", "ขอบขวาของกรอบจอสีขาว", "ตัวเรา", "เป้าหมาย",
            "ซ้ายสุดของหลอดแรง (0%)", "ขวาสุดของหลอดแรง (100%)"};
    private static final int[] COL = {0xFFFFFFFF, 0xFFFFFFFF, 0xFF5AC8FA, 0xFFFF9F0A, 0xFF30D158, 0xFF30D158};
    private static final int N = 6;
    private static final float ZOOM = 4f;

    private final Cb cb;
    private final float d;
    private final int screenW;
    private final float fwr;
    private boolean useMem;
    private final float[][] pts = new float[N][];
    private final boolean needBar;
    private int step;

    private boolean touching, onButton;
    private float fx, fy;                 // finger, screen coords
    private final int[] loc = new int[2];

    private final Object lock = new Object();
    private Bitmap frame;
    private long frameTime, touchStart, shownAt;
    private boolean black;
    private final RectF bReauth = new RectF();
    private int[] px = new int[0];

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pix = new Paint();
    private final RectF bUndo = new RectF(), bCancel = new RectF(), bReframe = new RectF();

    public LivePick(Context c, float fwr, int screenW, boolean needBar, Cb cb) {
        super(c);
        this.cb = cb;
        this.d = c.getResources().getDisplayMetrics().density;
        this.screenW = screenW;
        this.fwr = fwr;
        this.needBar = needBar;
        this.useMem = fwr > 0;
        this.step = useMem ? 2 : 0;
        setFocusable(true);
        setFocusableInTouchMode(true);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pix.setFilterBitmap(false);
        shownAt = android.os.SystemClock.uptimeMillis();
        postDelayed(new Runnable() { public void run() { invalidate(); } }, 1700);
    }

    private boolean noImage() {
        synchronized (lock) {
            return black || (frame == null && android.os.SystemClock.uptimeMillis() - shownAt > 1500);
        }
    }

    public void setFrame(Bitmap b, long t, boolean isBlack) {
        synchronized (lock) { frame = b; frameTime = t; black = isBlack; }
        postInvalidate();
    }

    // ---------- auto snap ----------
    /** 1 = enemy marker red, 2 = own/ally marker blue, 0 = anything else (the minimap is see-through). */
    private static int markerClass(int p) {
        int R = (p >> 16) & 255, G = (p >> 8) & 255, B = p & 255;
        // markers are flat, fully saturated discs; map art (candy canes, ornaments) is shaded and duller
        if (R >= 190 && G <= 70 && B <= 70 && R - Math.max(G, B) >= 150) return 1;
        if (B >= 140 && R <= 50 && B - R >= 110 && B - G >= 70) return 2;
        return 0;
    }

    /**
     * Steps 0-1: the frame box edge (left = dark->bright, right = bright->dark), x only.
     * Steps 2-3: centre of the nearest red / blue marker; arrows sitting above a marker are ignored.
     * Screen coords; null = nothing to snap to.
     */
    private float[] snap(float x, float y) {
        synchronized (lock) {
            if (step >= 4) return null;                                        // power bar ends: plain tap
            if (frame == null || frameTime < touchStart + 150) return null;   // frame must not contain our own markers
            int r = Math.round(14 * d);
            int fw = Math.min(frame.getWidth(), screenW), fh = frame.getHeight();
            int x0 = Math.max(0, Math.round(x) - r), y0 = Math.max(0, Math.round(y) - r);
            int x1 = Math.min(fw - 1, Math.round(x) + r), y1 = Math.min(fh - 1, Math.round(y) + r);
            int w = x1 - x0 + 1, h = y1 - y0 + 1;
            if (w < 5 || h < 5) return null;
            if (px.length < w * h) px = new int[w * h];
            frame.getPixels(px, 0, w, x0, y0, w, h);
            float fx0 = x - x0, fy0 = y - y0;

            if (step < 2) {
                int[] L = new int[w * h];
                for (int i = 0; i < w * h; i++) {
                    int p = px[i];
                    L[i] = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
                }
                int sg = step == 0 ? 1 : -1, best = -1;
                float bs = 0;
                for (int c = 1; c < w - 1; c++) {
                    int cnt = 0;
                    long sum = 0;
                    for (int rr = 0; rr < h; rr++) {
                        int g = (L[rr * w + c + 1] - L[rr * w + c - 1]) * sg;
                        sum += g;
                        if (g > 8) cnt++;
                    }
                    float cons = cnt / (float) h, mag = sum / (float) h;
                    float sc = mag * cons / (1 + Math.abs(c - fx0) / (r * 0.6f));
                    if (cons >= 0.5f && mag >= 6 && sc > bs) { bs = sc; best = c; }
                }
                return best < 0 ? null : new float[]{x0 + best + 0.5f, y};
            }

            int n0 = w * h;
            int[] cls = new int[n0];
            for (int i = 0; i < n0; i++) cls[i] = markerClass(px[i]);
            // chessboard distance to the nearest pixel of another class: thick discs score high, stripes stay at 1
            int[] dt = new int[n0];
            for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) {
                int i = yy * w + xx, c0 = cls[i];
                if (c0 == 0) continue;
                int m = 1 + Math.min(xx == 0 ? 0 : (cls[i - 1] == c0 ? dt[i - 1] : 0),
                        yy == 0 ? 0 : Math.min(cls[i - w] == c0 ? dt[i - w] : 0,
                        Math.min(xx == 0 ? 0 : (cls[i - w - 1] == c0 ? dt[i - w - 1] : 0),
                                 xx == w - 1 ? 0 : (cls[i - w + 1] == c0 ? dt[i - w + 1] : 0))));
                dt[i] = m;
            }
            for (int yy = h - 1; yy >= 0; yy--) for (int xx = w - 1; xx >= 0; xx--) {
                int i = yy * w + xx, c0 = cls[i];
                if (c0 == 0) continue;
                int m = 1 + Math.min(xx == w - 1 ? 0 : (cls[i + 1] == c0 ? dt[i + 1] : 0),
                        yy == h - 1 ? 0 : Math.min(cls[i + w] == c0 ? dt[i + w] : 0,
                        Math.min(xx == w - 1 ? 0 : (cls[i + w + 1] == c0 ? dt[i + w + 1] : 0),
                                 xx == 0 ? 0 : (cls[i + w - 1] == c0 ? dt[i + w - 1] : 0))));
                if (m < dt[i]) dt[i] = m;
            }
            boolean[] seen = new boolean[n0];
            int[] q = new int[n0];
            float[][] comps = new float[64][];      // {class, cx, cy, pixels, solid 0/1}
            int nc = 0;
            for (int s0 = 0; s0 < n0 && nc < 64; s0++) {
                if (cls[s0] == 0 || seen[s0]) continue;
                int col = cls[s0], qh = 0, qt = 0, n = 0, mx = 0;
                int bx0 = w, bx1 = -1, by0 = h, by1 = -1;
                q[qt++] = s0; seen[s0] = true;
                while (qh < qt) {
                    int i = q[qh++], cx = i % w, cy = i / w;
                    n++;
                    if (dt[i] > mx) mx = dt[i];
                    if (cx < bx0) bx0 = cx; if (cx > bx1) bx1 = cx;
                    if (cy < by0) by0 = cy; if (cy > by1) by1 = cy;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int j = ny * w + nx;
                        if (!seen[j] && cls[j] == col) { seen[j] = true; q[qt++] = j; }
                    }
                }
                int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
                if (n < 6 || bw > r * 1.5f || bh > r * 1.5f) continue;
                float ar = bw / (float) bh;
                if (ar < 0.6f || ar > 1.67f) continue;
                // a marker is a filled round disc: well filled box and a thick core
                boolean solid = n >= 0.55f * bw * bh && mx >= 2 && mx >= 0.28f * Math.min(bw, bh);
                // centre = middle of the thickest core (not the box: a disc touching art keeps its true centre)
                float sx = 0, sy = 0;
                int k = 0;
                for (int t = 0; t < qt; t++) {
                    int i = q[t];
                    if (dt[i] >= mx - 1) { sx += i % w; sy += i / w; k++; }
                }
                comps[nc++] = new float[]{col, sx / k, sy / k, n, solid ? 1 : 0};
            }
            int pref = step == 2 ? 2 : 1;
            float bestCost = Float.MAX_VALUE;
            float[] pick = null;
            for (int i = 0; i < nc; i++) {
                float[] a = comps[i];
                if (a[4] == 0) continue;               // stripes, rings, fragments of map art
                boolean arrow = false;                 // a bigger same-colour disc just below => this is its arrow
                for (int j = 0; j < nc && !arrow; j++) {
                    float[] b = comps[j];
                    if (j != i && b[4] == 1 && b[0] == a[0] && b[3] > a[3] * 1.3f
                            && Math.abs(b[1] - a[1]) < 6 * d && b[2] - a[2] > 0 && b[2] - a[2] < 14 * d) arrow = true;
                }
                if (arrow) continue;
                float dd = (float) Math.hypot(a[1] - fx0, a[2] - fy0) * (a[0] == pref ? 1f : 1.6f);
                if (dd < bestCost) { bestCost = dd; pick = a; }
            }
            return pick == null ? null : new float[]{x0 + pick[1] + 0.5f, y0 + pick[2] + 0.5f};
        }
    }

    public Object lock() { return lock; }

    public boolean wantsFrames() { return touching; }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) cb.cancel();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        getLocationOnScreen(loc);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (bCancel.contains(x, y)) { onButton = true; cb.cancel(); return true; }
                if (!bReauth.isEmpty() && bReauth.contains(x, y)) { onButton = true; cb.reauth(); return true; }
                if (bUndo.contains(x, y)) { onButton = true; undo(); return true; }
                if (useMem && bReframe.contains(x, y)) { onButton = true; reframe(); return true; }
                onButton = false;
                touching = true;
                touchStart = android.os.SystemClock.uptimeMillis();
                fx = x + loc[0]; fy = y + loc[1];
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (touching) { fx = x + loc[0]; fy = y + loc[1]; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (touching) {
                    touching = false;
                    float[] sp = snap(fx, fy);
                    if (sp != null) place(sp[0], sp[1]); else place(fx, fy);
                }
                onButton = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false; onButton = false; invalidate();
                return true;
        }
        return false;
    }

    private boolean needed(int i) { return !(i < 2 && useMem) && !(i >= 4 && !needBar); }

    private void place(float sx, float sy) {
        pts[step] = new float[]{sx, sy};
        int next = -1;
        for (int i = 0; i < N; i++) if (pts[i] == null && needed(i)) { next = i; break; }
        if (next >= 0) { step = next; invalidate(); return; }
        float fw = (pts[0] != null && pts[1] != null) ? Math.abs(pts[1][0] - pts[0][0]) : fwr * screenW;
        if (fw < 3) { reframe(); return; }
        float[] bar = null;
        if (pts[4] != null && pts[5] != null && Math.abs(pts[5][0] - pts[4][0]) > 20 * d)
            bar = new float[]{Math.min(pts[4][0], pts[5][0]), Math.max(pts[4][0], pts[5][0]), (pts[4][1] + pts[5][1]) / 2};
        cb.done(fw, pts[2][0], pts[2][1], pts[3][0], pts[3][1], screenW, bar);
    }

    private void undo() {
        int last = -1;
        for (int i = N - 1; i >= 0; i--) if (pts[i] != null) { last = i; break; }
        if (last < 0) return;
        pts[last] = null;
        step = last;
        invalidate();
    }

    private void reframe() {
        useMem = false;
        pts[0] = null; pts[1] = null;
        step = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        getLocationOnScreen(loc);
        int w = getWidth(), h = getHeight();

        // thin blue border = pick mode is on
        stroke.setColor(0xFF0A84FF);
        stroke.setStrokeWidth(3 * d);
        c.drawRect(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d, stroke);

        // while a finger is down nothing is drawn over the map: the capture would see it
        if (!touching) for (int i = 0; i < 2; i++) {
            if (pts[i] == null) continue;
            float x = pts[i][0] - loc[0], y = pts[i][1] - loc[1];
            stroke.setColor(0xCCFFFFFF);
            stroke.setStrokeWidth(1.5f * d);
            c.drawLine(x, y - 40 * d, x, y + 40 * d, stroke);
        }
        // markers
        if (!touching) for (int i = 0; i < N; i++) {
            if (pts[i] == null) continue;
            marker(c, pts[i][0] - loc[0], pts[i][1] - loc[1], i);
        }

        if (touching) loupe(c, fx - loc[0]);
        hud(c, w, h);
        warn(c, w, h);
    }

    /** Shown when the capture gives no picture: usually a single-app share or a stopped projection. */
    private void warn(Canvas c, int w, int h) {
        if (!noImage()) { bReauth.setEmpty(); return; }
        float bw = Math.min(w - 40 * d, 460 * d), bh = 112 * d, l = (w - bw) / 2, t = h * 0.22f;
        RectF box = new RectF(l, t, l + bw, t + bh);
        fill.setColor(0xF22C2C2E);
        c.drawRoundRect(box, 16 * d, 16 * d, fill);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFFFF9F0A);
        text.setTextSize(14 * d);
        c.drawText("ไม่ได้รับภาพหน้าจอ (แว่นขยายดำ)", l + 16 * d, t + 26 * d, text);
        text.setColor(0xFFEBEBF5);
        text.setTextSize(12 * d);
        c.drawText("กด ‘ขอสิทธิ์ใหม่’ แล้วเลือก “ทั้งหน้าจอ” (ไม่ใช่แอปเดียว)", l + 16 * d, t + 48 * d, text);
        float rw = 140 * d, rh = 38 * d;
        bReauth.set(l + 16 * d, t + bh - rh - 12 * d, l + 16 * d + rw, t + bh - 12 * d);
        btn(c, bReauth, "ขอสิทธิ์ใหม่", 0xFF0A84FF);
        if (!black) postInvalidateDelayed(500);
    }

    private void marker(Canvas c, float x, float y, int i) {
        fill.setColor(0x66000000);
        c.drawCircle(x, y, 12 * d, fill);
        stroke.setColor(COL[i]);
        stroke.setStrokeWidth(2 * d);
        c.drawCircle(x, y, 12 * d, stroke);
        fill.setColor(COL[i]);
        c.drawCircle(x, y, 1.6f * d, fill);
        text.setColor(COL[i]);
        text.setTextSize(10 * d);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(String.valueOf(i + 1), x + 15 * d, y - 12 * d, text);
    }

    private void loupe(Canvas c, float fingerViewX) {
        int w = getWidth(), h = getHeight();
        float r = 82 * d;
        float cx = fingerViewX > w / 2f ? w * 0.27f : w * 0.73f;
        float cy = Math.max(r + 12 * d, h * 0.38f);
        Path clip = new Path();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawColor(0xFF1C1C1E);
        synchronized (lock) {
            if (frame != null) {
                float half = r / ZOOM;
                Rect src = new Rect(Math.round(fx - half), Math.round(fy - half), Math.round(fx + half), Math.round(fy + half));
                c.drawBitmap(frame, src, new RectF(cx - r, cy - r, cx + r, cy + r), pix);
            }
        }
        c.restore();
        float[] sp = snap(fx, fy);
        if (sp != null) {
            float lx = cx + (sp[0] - fx) * ZOOM, ly = cy + (sp[1] - fy) * ZOOM;
            stroke.setColor(0xFF30D158);
            stroke.setStrokeWidth(2.5f * d);
            if (step < 2) c.drawLine(lx, cy - r * 0.8f, lx, cy + r * 0.8f, stroke);
            else c.drawCircle(lx, ly, 9 * d, stroke);
        }
        // crosshair in the loupe
        stroke.setColor(0xFF000000);
        stroke.setStrokeWidth(3.5f * d);
        cross(c, cx, cy);
        stroke.setColor(COL[step]);
        stroke.setStrokeWidth(1.5f * d);
        cross(c, cx, cy);
        stroke.setColor(0xFF000000);
        stroke.setStrokeWidth(5 * d);
        c.drawCircle(cx, cy, r, stroke);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeWidth(2.5f * d);
        c.drawCircle(cx, cy, r, stroke);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(11 * d);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(sp != null ? "×4 · เขียว = ดูดเข้าจุดนี้" : "×4 · ปล่อยนิ้วเพื่อวาง", cx, cy + r + 16 * d, text);
    }

    private void cross(Canvas c, float cx, float cy) {
        float g = 6 * d, l = 22 * d;
        c.drawLine(cx - l, cy, cx - g, cy, stroke);
        c.drawLine(cx + g, cy, cx + l, cy, stroke);
        c.drawLine(cx, cy - l, cx, cy - g, stroke);
        c.drawLine(cx, cy + g, cx, cy + l, stroke);
    }

    private void hud(Canvas c, int w, int h) {
        float hw = Math.min(w - 24 * d, 600 * d), hh = 54 * d;
        // the power bar sits at the bottom of the game: move this panel up while measuring it,
        // or whenever a finger works in the lower part of the screen
        boolean up = step >= 4 || (touching && fy - loc[1] > h * 0.6f);
        float left = (w - hw) / 2, top = up ? 10 * d : h - hh - 10 * d;
        RectF box = new RectF(left, top, left + hw, top + hh);
        fill.setColor(0xF01C1C1E);
        c.drawRoundRect(box, 16 * d, 16 * d, fill);

        int n = 0, done = 0;
        for (int i = 0; i < N; i++) if (needed(i)) { n++; if (pts[i] != null) done++; }
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(COL[step] == 0xFFFFFFFF ? 0xFF0A84FF : COL[step]);
        text.setTextSize(14 * d);
        c.drawText("แตะ: " + LBL[step] + "  (" + (done + 1) + "/" + n + ")", left + 14 * d, top + 23 * d, text);
        text.setColor(0xFFAEAEB2);
        text.setTextSize(11 * d);
        c.drawText(step >= 4 ? "ตั้งครั้งเดียว: แตะปลายหลอดแรงด้านล่างจอ (ใช้วางขีดบอกแรง)" : useMem ? "ใช้กรอบจอที่จำไว้ · ลากนิ้วดูแว่นขยาย" : "ลากนิ้วดูแว่นขยาย แล้วปล่อยตรงจุดที่ต้องการ",
                left + 14 * d, top + 42 * d, text);

        float bw = 72 * d, bh = 36 * d, by = top + (hh - bh) / 2, x = left + hw - 10 * d - bw;
        bCancel.set(x, by, x + bw, by + bh);
        btn(c, bCancel, "ยกเลิก", 0xFF3A3A3C);
        x -= bw + 8 * d;
        bUndo.set(x, by, x + bw, by + bh);
        btn(c, bUndo, "ย้อน", 0xFF2C2C2E);
        if (useMem) {
            float bw2 = 96 * d;
            x -= bw2 + 8 * d;
            bReframe.set(x, by, x + bw2, by + bh);
            btn(c, bReframe, "วัดกรอบใหม่", 0xFF2C2C2E);
        } else {
            bReframe.setEmpty();
        }
    }

    private void btn(Canvas c, RectF r, String s, int bg) {
        fill.setColor(bg);
        c.drawRoundRect(r, 10 * d, 10 * d, fill);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(13 * d);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, r.centerX(), r.centerY() + 4.5f * d, text);
    }
}
