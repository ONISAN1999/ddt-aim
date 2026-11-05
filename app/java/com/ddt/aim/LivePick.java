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
        void done(float fw, float sx, float sy, float tx, float ty, int screenW);
        void cancel();
    }

    private static final String[] LBL = {
            "ขอบซ้ายของกรอบจอสีขาว (ในแผนที่ย่อ)", "ขอบขวาของกรอบจอสีขาว", "ตัวเรา", "เป้าหมาย"};
    private static final int[] COL = {0xFFFFFFFF, 0xFFFFFFFF, 0xFF5AC8FA, 0xFFFF9F0A};
    private static final float ZOOM = 4f;

    private final Cb cb;
    private final float d;
    private final int screenW;
    private final float fwr;
    private boolean useMem;
    private final float[][] pts = new float[4][];
    private int step;

    private boolean touching, onButton;
    private float fx, fy;                 // finger, screen coords
    private final int[] loc = new int[2];

    private final Object lock = new Object();
    private Bitmap frame;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pix = new Paint();
    private final RectF bUndo = new RectF(), bCancel = new RectF(), bReframe = new RectF();

    public LivePick(Context c, float fwr, int screenW, Cb cb) {
        super(c);
        this.cb = cb;
        this.d = c.getResources().getDisplayMetrics().density;
        this.screenW = screenW;
        this.fwr = fwr;
        this.useMem = fwr > 0;
        this.step = useMem ? 2 : 0;
        setFocusable(true);
        setFocusableInTouchMode(true);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pix.setFilterBitmap(false);
    }

    public void setFrame(Bitmap b) {
        synchronized (lock) { frame = b; }
        postInvalidate();
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
                if (bUndo.contains(x, y)) { onButton = true; undo(); return true; }
                if (useMem && bReframe.contains(x, y)) { onButton = true; reframe(); return true; }
                onButton = false;
                touching = true;
                fx = x + loc[0]; fy = y + loc[1];
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (touching) { fx = x + loc[0]; fy = y + loc[1]; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (touching) { touching = false; place(fx, fy); }
                onButton = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false; onButton = false; invalidate();
                return true;
        }
        return false;
    }

    private boolean needed(int i) { return !(i < 2 && useMem); }

    private void place(float sx, float sy) {
        pts[step] = new float[]{sx, sy};
        int next = -1;
        for (int i = 0; i < 4; i++) if (pts[i] == null && needed(i)) { next = i; break; }
        if (next >= 0) { step = next; invalidate(); return; }
        float fw = (pts[0] != null && pts[1] != null) ? Math.abs(pts[1][0] - pts[0][0]) : fwr * screenW;
        if (fw < 3) { reframe(); return; }
        cb.done(fw, pts[2][0], pts[2][1], pts[3][0], pts[3][1], screenW);
    }

    private void undo() {
        int last = -1;
        for (int i = 3; i >= 0; i--) if (pts[i] != null) { last = i; break; }
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

        // frame edges as vertical guides
        for (int i = 0; i < 2; i++) {
            if (pts[i] == null) continue;
            float x = pts[i][0] - loc[0], y = pts[i][1] - loc[1];
            stroke.setColor(0xCCFFFFFF);
            stroke.setStrokeWidth(1.5f * d);
            c.drawLine(x, y - 40 * d, x, y + 40 * d, stroke);
        }
        // markers
        for (int i = 0; i < 4; i++) {
            if (pts[i] == null) continue;
            marker(c, pts[i][0] - loc[0], pts[i][1] - loc[1], i);
        }

        if (touching) {
            float x = fx - loc[0], y = fy - loc[1];
            stroke.setColor(COL[step]);
            stroke.setStrokeWidth(1.5f * d);
            c.drawLine(x - 18 * d, y, x - 5 * d, y, stroke);
            c.drawLine(x + 5 * d, y, x + 18 * d, y, stroke);
            c.drawLine(x, y - 18 * d, x, y - 5 * d, stroke);
            c.drawLine(x, y + 5 * d, x, y + 18 * d, stroke);
            loupe(c, x);
        }
        hud(c, w, h);
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
        c.drawText("×4 · ปล่อยนิ้วเพื่อวาง", cx, cy + r + 16 * d, text);
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
        float left = (w - hw) / 2, top = h - hh - 10 * d;
        RectF box = new RectF(left, top, left + hw, top + hh);
        fill.setColor(0xF01C1C1E);
        c.drawRoundRect(box, 16 * d, 16 * d, fill);

        int n = 0, done = 0;
        for (int i = 0; i < 4; i++) if (needed(i)) { n++; if (pts[i] != null) done++; }
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(COL[step] == 0xFFFFFFFF ? 0xFF0A84FF : COL[step]);
        text.setTextSize(14 * d);
        c.drawText("แตะ: " + LBL[step] + "  (" + (done + 1) + "/" + n + ")", left + 14 * d, top + 23 * d, text);
        text.setColor(0xFFAEAEB2);
        text.setTextSize(11 * d);
        c.drawText(useMem ? "ใช้กรอบจอที่จำไว้ · ลากนิ้วดูแว่นขยาย" : "ลากนิ้วดูแว่นขยาย แล้วปล่อยตรงจุดที่ต้องการ",
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
