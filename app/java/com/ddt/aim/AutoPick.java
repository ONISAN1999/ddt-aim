package com.ddt.aim;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One-tap measuring: finds our own marker and the enemy markers on the minimap without any taps.
 * Markers are flat discs (SmallObject, radius 4 units): team blue (0, 51, 204), team red (255, 0, 0).
 * Our own disc wears a soft blue halo ring; the ▼ above a disc marks whose turn it is.
 */
public class AutoPick {
    public static class Res {
        public float sx, sy;                       // our marker (screen px)
        public List<float[]> enemies = new ArrayList<>();   // nearest first
    }

    static class Disc { int col; float cx, cy, r; int n; boolean arrow; float ring; }

    /** rect: x0, y0, x1, y1 on screen to search (the minimap when known). */
    public static Res detect(Bitmap frame, int x0, int y0, int x1, int y1, float sc) {
        int w = x1 - x0, h = y1 - y0;
        if (w < 20 || h < 20) return null;
        int[] p = new int[w * h];
        frame.getPixels(p, 0, w, x0, y0, w, h);
        int[] cls = new int[w * h];
        for (int i = 0; i < p.length; i++) cls[i] = LivePick.markerClass(p[i]);
        boolean[] seen = new boolean[w * h];
        int[] q = new int[w * h];
        List<Disc> discs = new ArrayList<>();
        for (int s = 0; s < cls.length; s++) {
            if (cls[s] == 0 || seen[s]) continue;
            int c = cls[s], qh = 0, qt = 0, bx0 = w, by0 = h, bx1 = -1, by1 = -1;
            float sx = 0, sy = 0;
            q[qt++] = s; seen[s] = true;
            while (qh < qt) {
                int i = q[qh++], x = i % w, y = i / w;
                sx += x; sy += y;
                if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = ny * w + nx;
                    if (!seen[j] && cls[j] == c) { seen[j] = true; q[qt++] = j; }
                }
            }
            int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
            if (qt < 20 * sc * sc || qt > 900 * sc * sc) continue;
            float ar = bw / (float) bh;
            if (ar < 0.6f || ar > 1.67f || qt < 0.4f * bw * bh) continue;      // our disc can read half-filled (halo, antialiasing)
            Disc d = new Disc();
            d.col = c; d.n = qt; d.cx = sx / qt; d.cy = sy / qt; d.r = (bw + bh) / 4f;
            discs.add(d);
        }
        // the ▼ above a disc is also a filled blue blob: drop blobs that sit just above a bigger same-colour disc
        for (Disc a : discs)
            for (Disc b : discs)
                if (a != b && a.col == b.col && b.n > a.n * 1.2f && Math.abs(b.cx - a.cx) < b.r && a.cy < b.cy && b.cy - a.cy < 5 * b.r) a.arrow = true;
        Disc self = null;
        for (Disc d : discs) {
            if (d.arrow || d.col != 2) continue;
            d.ring = blueness(p, w, h, d, 1.5f, 2.1f) - blueness(p, w, h, d, 2.7f, 3.3f);
            if (d.ring >= 25 && (self == null || d.ring > self.ring)) self = d;
        }
        if (self == null) return null;
        Res r = new Res();
        r.sx = x0 + self.cx + 0.5f; r.sy = y0 + self.cy + 0.5f;
        final float fx = self.cx, fy = self.cy;
        List<Disc> en = new ArrayList<>();
        for (Disc d : discs) if (!d.arrow && d.col == 1) en.add(d);
        Collections.sort(en, new Comparator<Disc>() {
            public int compare(Disc a, Disc b) {
                return Float.compare((float) Math.hypot(a.cx - fx, a.cy - fy), (float) Math.hypot(b.cx - fx, b.cy - fy));
            }
        });
        for (Disc d : en) r.enemies.add(new float[]{x0 + d.cx + 0.5f, y0 + d.cy + 0.5f});
        return r.enemies.isEmpty() ? null : r;
    }

    /** Median of B − (R+G)/2 on rings between k1·r and k2·r around the disc. */
    static float blueness(int[] p, int w, int h, Disc d, float k1, float k2) {
        List<Float> v = new ArrayList<>();
        for (float rr = k1 * d.r; rr < k2 * d.r; rr += 0.7f)
            for (int t = 0; t < 36; t++) {
                double a = t * Math.PI * 2 / 36;
                int x = Math.round(d.cx + rr * (float) Math.cos(a)), y = Math.round(d.cy + rr * (float) Math.sin(a));
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int c = p[y * w + x];
                v.add((c & 255) - (((c >> 16) & 255) + ((c >> 8) & 255)) / 2f);
            }
        if (v.isEmpty()) return 0;
        Collections.sort(v);
        return v.get(v.size() / 2);
    }
}
