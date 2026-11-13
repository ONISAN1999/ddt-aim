package com.ddt.aim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Zoomed minimap for one-tap measuring: the minimap rectangle cut from the screen and drawn large in
 * the middle, with every marker the app found. Whatever was found is preset (our marker, nearest enemy);
 * a tap snaps to the nearest marker, so picking on the big picture is easy. Coordinates handed back are
 * screen px of the real minimap.
 */
public class ZoomPick extends View {
    public interface Cb {
        void done(float sx, float sy, float tx, float ty);
        void cancel();
    }

    private final float d;
    private final Bitmap crop;
    private final AutoPick.Res res;
    private final Cb cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF(), bOk = new RectF(), bSelf = new RectF(), bX = new RectF();
    private float k = 1;
    private float sx, sy, tx = Float.NaN, ty = Float.NaN;
    private boolean wantSelf;

    public ZoomPick(Context c, Bitmap crop, AutoPick.Res res, Cb cb) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        this.crop = crop; this.res = res; this.cb = cb;
        sx = res.sx; sy = res.sy;
        wantSelf = Float.isNaN(sx);
        if (!res.enemies.isEmpty()) { tx = res.enemies.get(0)[0]; ty = res.enemies.get(0)[1]; }
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setTextAlign(Paint.Align.CENTER);
    }

    private float vx(float x) { return dst.left + (x - res.x0) * k; }
    private float vy(float y) { return dst.top + (y - res.y0) * k; }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xD9000000);
        float cw = res.x1 - res.x0, ch = res.y1 - res.y0;
        k = Math.min(w * 0.86f / cw, (h - 120 * d) / ch);
        float dw = cw * k, dh = ch * k, left = (w - dw) / 2, top = 56 * d;
        dst.set(left, top, left + dw, top + dh);
        p.setFilterBitmap(true);
        c.drawBitmap(crop, new Rect(0, 0, crop.getWidth(), crop.getHeight()), dst, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setColor(0x99FFFFFF);
        c.drawRect(dst, p);

        // every marker found (thin rings), then our pick (thick, labelled)
        for (float[] b : res.blues) ring(c, b, 0xFF64D2FF, 1.5f, null);
        for (float[] r : res.reds) ring(c, r, 0xFFFF6961, 1.5f, null);
        if (!Float.isNaN(sx)) ring(c, new float[]{sx, sy, 7}, 0xFF0A84FF, 3.5f, "เรา");
        if (!Float.isNaN(tx)) ring(c, new float[]{tx, ty, 7}, 0xFFFF453A, 3.5f, "เป้า");

        // instruction + buttons
        t.setTextSize(17 * d);
        t.setColor(0xFFFFFFFF);
        String msg = wantSelf ? "แตะ “ตัวเรา” (จุดน้ำเงิน) บนแผนที่ขยาย"
                : Float.isNaN(tx) ? "แตะ “เป้า” (จุดแดง) บนแผนที่ขยาย"
                : "ถูกแล้วกด “ตกลง” · แตะจุดแดงอื่นเพื่อเปลี่ยนเป้า";
        c.drawText(msg, w / 2f, 36 * d, t);
        float by = h - 52 * d;
        button(c, bSelf, w / 2f - 230 * d, by, 140 * d, "เลือกตัวเราใหม่", 0xFF3A3A3C);
        button(c, bOk, w / 2f - 70 * d, by, 140 * d, "ตกลง", ready() ? 0xFF30D158 : 0xFF48484A);
        button(c, bX, w / 2f + 90 * d, by, 140 * d, "แตะบนจอจริง", 0xFF3A3A3C);
    }

    private boolean ready() { return !Float.isNaN(sx) && !Float.isNaN(tx); }

    private void ring(Canvas c, float[] m, int col, float sw, String lbl) {
        float x = vx(m[0]), y = vy(m[1]), r = Math.max(9 * d, m[2] * k * 1.6f);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xCC000000);
        p.setStrokeWidth((sw + 2) * d);
        c.drawCircle(x, y, r, p);
        p.setColor(col);
        p.setStrokeWidth(sw * d);
        c.drawCircle(x, y, r, p);
        if (lbl != null) {
            t.setTextSize(14 * d);
            float tw = t.measureText(lbl) + 12 * d;
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawRoundRect(new RectF(x - tw / 2, y - r - 26 * d, x + tw / 2, y - r - 6 * d), 6 * d, 6 * d, p);
            t.setColor(0xFFFFFFFF);
            c.drawText(lbl, x, y - r - 11 * d, t);
        }
    }

    private void button(Canvas c, RectF r, float x, float y, float w, String s, int col) {
        r.set(x, y, x + w, y + 40 * d);
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        c.drawRoundRect(r, 12 * d, 12 * d, p);
        t.setTextSize(15 * d);
        t.setColor(0xFFFFFFFF);
        c.drawText(s, r.centerX(), r.centerY() + 5 * d, t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY();
        if (bOk.contains(x, y)) { if (ready()) cb.done(sx, sy, tx, ty); return true; }
        if (bX.contains(x, y)) { cb.cancel(); return true; }
        if (bSelf.contains(x, y)) { wantSelf = true; invalidate(); return true; }
        if (!dst.contains(x, y)) return true;
        // screen px on the real minimap, snapped to the nearest marker of the wanted colour (any colour as fallback)
        float mx = res.x0 + (x - dst.left) / k, my = res.y0 + (y - dst.top) / k;
        float[] snap = nearest(wantSelf ? res.blues : res.reds, mx, my);
        if (snap == null) snap = new float[]{mx, my};
        if (wantSelf) { sx = snap[0]; sy = snap[1]; wantSelf = false; }
        else { tx = snap[0]; ty = snap[1]; }
        invalidate();
        return true;
    }

    private float[] nearest(java.util.List<float[]> l, float x, float y) {
        float[] best = null;
        float bd = 14 * d / k + 6;              // within ~14 dp on the big picture
        for (float[] m : l) {
            float dd = (float) Math.hypot(m[0] - x, m[1] - y);
            if (dd < bd) { bd = dd; best = m; }
        }
        return best;
    }
}
