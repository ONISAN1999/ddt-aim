package com.ddt.aim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Touch-through layer over the game:
 *  - the shot's path drawn on the minimap, from our marker to the target (needs a map pick),
 *  - a tick on the game's power bar at the power to shoot (needs the bar measured once).
 * All coordinates are screen px.
 */
public class AimOverlay extends View {
    private final float d;
    private final int[] loc = new int[2];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] path;            // x0,y0,x1,y1,... screen px (minimap)
    private float tx = -1, ty = -1;   // target marker on the minimap
    private int power = -1;
    private boolean hit;
    private float barL, barR, barY;   // power bar, 0 % .. 100 %

    public AimOverlay(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setBar(float l, float r, float y) { barL = l; barR = r; barY = y; invalidate(); }

    public boolean hasBar() { return barR - barL > 20 * d; }

    public void setAim(int power, boolean hit, float[] path, float tx, float ty) {
        this.power = power; this.hit = hit; this.path = path; this.tx = tx; this.ty = ty;
        invalidate();
    }

    public void clear() { power = -1; path = null; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        getLocationOnScreen(loc);
        float ox = loc[0], oy = loc[1];
        int col = hit ? 0xFF30D158 : 0xFFFF9F0A;

        if (path != null && path.length >= 4) {
            Path p = new Path();
            p.moveTo(path[0] - ox, path[1] - oy);
            for (int i = 2; i + 1 < path.length; i += 2) p.lineTo(path[i] - ox, path[i + 1] - oy);
            line.setColor(0xCC000000);
            line.setStrokeWidth(4.5f * d);
            c.drawPath(p, line);
            line.setColor(col);
            line.setStrokeWidth(2f * d);
            c.drawPath(p, line);
            if (tx >= 0) {
                line.setStrokeWidth(2f * d);
                c.drawCircle(tx - ox, ty - oy, 7 * d, line);
            }
        }

        if (power >= 0 && hasBar()) {
            float x = barL + (barR - barL) * power / 100f - ox, y = barY - oy;
            float h = 16 * d;
            line.setColor(0xE6000000);
            line.setStrokeWidth(6 * d);
            c.drawLine(x, y - h, x, y + h, line);
            line.setColor(col);
            line.setStrokeWidth(3 * d);
            c.drawLine(x, y - h, x, y + h, line);
            String s = power + "%";
            text.setTextSize(15 * d);
            float tw = text.measureText(s) + 14 * d, top = y - h - 26 * d;
            RectF box = new RectF(x - tw / 2, top, x + tw / 2, top + 22 * d);
            fill.setColor(0xF01C1C1E);
            c.drawRoundRect(box, 7 * d, 7 * d, fill);
            Path tri = new Path();
            tri.moveTo(x - 5 * d, top + 22 * d);
            tri.lineTo(x + 5 * d, top + 22 * d);
            tri.lineTo(x, top + 28 * d);
            tri.close();
            c.drawPath(tri, fill);
            text.setColor(col);
            c.drawText(s, x, top + 16.5f * d, text);
        }
    }
}
