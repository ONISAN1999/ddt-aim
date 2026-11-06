package com.ddt.aim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch-through layer over the game. For every shot (Auto shows two, each in its own colour):
 *  - its path on the minimap, from our marker to the target (needs a map pick),
 *  - a tick + "angle · power" label on the game's power bar (needs the bar measured once).
 * All coordinates are screen px.
 */
public class AimOverlay extends View {
    public static class Shot {
        final int power, angle, color;
        final float[] path;           // x0,y0,x1,y1,... screen px, or null
        public Shot(int power, int angle, int color, float[] path) {
            this.power = power; this.angle = angle; this.color = color; this.path = path;
        }
    }

    private final float d;
    private final int[] loc = new int[2];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Shot> shots = new ArrayList<>();
    private float tx = -1, ty = -1;   // target marker on the minimap
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

    public void setShots(List<Shot> s, float tx, float ty) {
        shots = s == null ? new ArrayList<Shot>() : s;
        this.tx = tx; this.ty = ty;
        invalidate();
    }

    public void clear() { shots = new ArrayList<>(); invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        getLocationOnScreen(loc);
        float ox = loc[0], oy = loc[1];

        // paths on the minimap (second shot first, so the first one stays on top)
        boolean anyPath = false;
        for (int k = shots.size() - 1; k >= 0; k--) {
            Shot s = shots.get(k);
            if (s.path == null || s.path.length < 4) continue;
            anyPath = true;
            Path p = new Path();
            p.moveTo(s.path[0] - ox, s.path[1] - oy);
            for (int i = 2; i + 1 < s.path.length; i += 2) p.lineTo(s.path[i] - ox, s.path[i + 1] - oy);
            line.setColor(0xCC000000);
            line.setStrokeWidth(4.5f * d);
            c.drawPath(p, line);
            line.setColor(s.color);
            line.setStrokeWidth(2f * d);
            c.drawPath(p, line);
        }
        if (anyPath && tx >= 0) {
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(1.5f * d);
            c.drawCircle(tx - ox, ty - oy, 7 * d, line);
        }

        // ticks on the power bar; labels stacked when ticks sit close together
        if (!hasBar()) return;
        float h = 16 * d, prevX = -1e9f;
        int level = 0;
        for (int k = 0; k < shots.size(); k++) {
            Shot s = shots.get(k);
            if (s.power < 0) continue;
            float x = barL + (barR - barL) * s.power / 100f - ox, y = barY - oy;
            line.setColor(0xE6000000);
            line.setStrokeWidth(6 * d);
            c.drawLine(x, y - h, x, y + h, line);
            line.setColor(s.color);
            line.setStrokeWidth(3 * d);
            c.drawLine(x, y - h, x, y + h, line);

            String lbl = (shots.size() > 1 ? s.angle + "° " : "") + s.power + "%";
            text.setTextSize(15 * d);
            float tw = text.measureText(lbl) + 14 * d;
            level = Math.abs(x - prevX) < tw + 4 * d ? level + 1 : 0;
            prevX = x;
            float top = y - h - 26 * d - level * 26 * d;
            RectF box = new RectF(x - tw / 2, top, x + tw / 2, top + 22 * d);
            fill.setColor(0xF01C1C1E);
            c.drawRoundRect(box, 7 * d, 7 * d, fill);
            line.setColor(s.color);
            line.setStrokeWidth(1.5f * d);
            c.drawRoundRect(box, 7 * d, 7 * d, line);
            if (level == 0) {
                Path tri = new Path();
                tri.moveTo(x - 5 * d, top + 22 * d);
                tri.lineTo(x + 5 * d, top + 22 * d);
                tri.lineTo(x, top + 28 * d);
                tri.close();
                c.drawPath(tri, fill);
            } else {
                c.drawLine(x, top + 22 * d, x, y - h, line);
            }
            text.setColor(s.color);
            c.drawText(lbl, x, top + 16.5f * d, text);
        }
    }
}
