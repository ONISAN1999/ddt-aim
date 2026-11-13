package com.ddt.aim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the wind badge at the top centre of the battle screen (FightVaneUIComp):
 * an orange shield with white chevrons (pointing the way the wind blows; mirrored for negative wind)
 * and the number underneath in the game's Thai UI font CSPraKasFD (white, orange-red outline).
 * The number is printed like LuaJIT's tostring: "0", "1" … "9.9" (never "2.0"), so two digits mean x.y.
 * Digit shapes are compared with the same font rendered here (assets/CSPraKasFD.otf from the game files).
 */
public class WindRead {
    static final int GW = 12, GH = 18;
    static float[][] tpl;
    static float[] tplAr;

    public static class Result {
        public int whole, tenth, dir;   // dir: +1 right, -1 left, 0 unknown
        public float conf;
        public String text;
    }

    static synchronized void init(Context c) {
        if (tpl != null) return;
        Typeface tf;
        try { tf = Typeface.createFromAsset(c.getAssets(), "CSPraKasFD.otf"); } catch (Throwable t) { tf = Typeface.DEFAULT; }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(80);
        p.setColor(Color.WHITE);
        tpl = new float[10][];
        tplAr = new float[10];
        for (int d = 0; d < 10; d++) {
            Bitmap b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            cv.drawColor(Color.BLACK);
            cv.drawText(String.valueOf(d), 20, 90, p);
            int[] px = new int[120 * 120];
            b.getPixels(px, 0, 120, 0, 0, 120, 120);
            b.recycle();
            boolean[] m = new boolean[px.length];
            for (int i = 0; i < px.length; i++) m[i] = ((px[i] >> 8) & 255) > 128;
            float[] ar = new float[1];
            tpl[d] = norm(m, 120, 0, 0, 119, 119, ar);
            tplAr[d] = ar[0];
        }
    }

    /** Crop the mask to its own box, scale to GW×GH, 3×3 box blur, scale to max 1. ar[0] = width/height. */
    static float[] norm(boolean[] m, int stride, int x0, int y0, int x1, int y1, float[] ar) {
        int bx0 = x1, by0 = y1, bx1 = x0, by1 = y0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++)
            if (m[y * stride + x]) { if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y; }
        if (bx1 < bx0) return new float[GW * GH];
        int w = bx1 - bx0 + 1, h = by1 - by0 + 1;
        ar[0] = w / (float) h;
        float[] g = new float[GW * GH];
        for (int gy = 0; gy < GH; gy++)
            for (int gx = 0; gx < GW; gx++) {
                // area average of the source cells covering this grid cell
                float sx0 = gx * w / (float) GW, sx1 = (gx + 1) * w / (float) GW, sy0 = gy * h / (float) GH, sy1 = (gy + 1) * h / (float) GH;
                float sum = 0, cnt = 0;
                for (int y = (int) sy0; y < Math.ceil(sy1); y++)
                    for (int x = (int) sx0; x < Math.ceil(sx1); x++) {
                        float wx = Math.min(x + 1, sx1) - Math.max(x, sx0), wy = Math.min(y + 1, sy1) - Math.max(y, sy0);
                        if (wx <= 0 || wy <= 0) continue;
                        if (m[(by0 + y) * stride + bx0 + x]) sum += wx * wy;
                        cnt += wx * wy;
                    }
                g[gy * GW + gx] = cnt > 0 ? sum / cnt : 0;
            }
        float[] o = new float[GW * GH];
        float mx = 1e-6f;
        for (int y = 0; y < GH; y++)
            for (int x = 0; x < GW; x++) {
                float s = 0; int n = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= GW || yy >= GH) { n++; continue; }
                    s += g[yy * GW + xx]; n++;
                }
                o[y * GW + x] = s / n;
                mx = Math.max(mx, o[y * GW + x]);
            }
        for (int i = 0; i < o.length; i++) o[i] /= mx;
        return o;
    }

    static class Comp { int x0 = 1 << 30, y0 = 1 << 30, x1 = -1, y1 = -1, n; List<Integer> px = new ArrayList<>(); }

    static List<Comp> label(boolean[] m, int w, int h, boolean eight) {
        List<Comp> out = new ArrayList<>();
        boolean[] seen = new boolean[m.length];
        int[] q = new int[m.length];
        for (int s = 0; s < m.length; s++) {
            if (!m[s] || seen[s]) continue;
            Comp c = new Comp();
            int qh = 0, qt = 0;
            q[qt++] = s; seen[s] = true;
            while (qh < qt) {
                int i = q[qh++], x = i % w, y = i / w;
                c.n++; c.px.add(i);
                if (x < c.x0) c.x0 = x; if (x > c.x1) c.x1 = x; if (y < c.y0) c.y0 = y; if (y > c.y1) c.y1 = y;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    if (!eight && dx != 0 && dy != 0) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = ny * w + nx;
                    if (m[j] && !seen[j]) { seen[j] = true; q[qt++] = j; }
                }
            }
            out.add(c);
        }
        return out;
    }

    /** frame: full screen bitmap. Returns null when no badge is found. */
    public static Result read(Context ctx, Bitmap frame, int W, int H) {
        try {
            init(ctx);
            int rx0 = (int) (W * 0.42f), rw = (int) (W * 0.58f) - rx0, rh = (int) (H * 0.15f);
            int[] p = new int[rw * rh];
            frame.getPixels(p, 0, rw, rx0, 0, rw, rh);
            // 1) the orange shield: the orange blob nearest the centre line
            boolean[] org = new boolean[p.length];
            for (int i = 0; i < p.length; i++) {
                int R = (p[i] >> 16) & 255, G = (p[i] >> 8) & 255, B = p[i] & 255;
                org[i] = R > 180 && G > 45 && G < 145 && B < 95 && R - G > 90;
            }
            float sc = W / 2576f;
            Comp shield = null;
            float bestD = 1e9f;
            for (Comp c : label(org, rw, rh, false)) {
                int bw = c.x1 - c.x0 + 1, bh = c.y1 - c.y0 + 1;
                if (c.n < 150 * sc * sc) continue;
                float ar = bw / (float) bh;
                if (ar < 0.6f || ar > 1.6f) continue;
                float d = Math.abs((c.x0 + c.x1) / 2f - rw / 2f);
                if (d < bestD) { bestD = d; shield = c; }
            }
            if (shield == null) return null;
            int bx0 = shield.x0, by0 = shield.y0, bw = shield.x1 - shield.x0 + 1, bh = shield.y1 - shield.y0 + 1;
            // 2) white parts inside the shield
            boolean[] wm = new boolean[bw * bh];
            for (int y = 0; y < bh; y++)
                for (int x = 0; x < bw; x++) {
                    int c = p[(by0 + y) * rw + bx0 + x];
                    int R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
                    wm[y * bw + x] = R > 215 && G > 148 && B > 118;
                }
            List<Comp> digs = new ArrayList<>(), arrows = new ArrayList<>();
            int hmax = 0;
            for (Comp c : label(wm, bw, bh, true)) {
                if (c.n < 3) continue;
                int w_ = c.x1 - c.x0 + 1, h_ = c.y1 - c.y0 + 1;
                if (c.x1 < bw * 0.1f || c.x0 > bw * 0.9f) continue;          // rim bumps
                if (c.y0 < 0.25f * bh) { arrows.add(c); continue; }           // chevrons on top
                if (w_ > 1.3f * h_ && h_ > 3) continue;                        // border arcs
                digs.add(c);
                hmax = Math.max(hmax, h_);
            }
            List<Comp> real = new ArrayList<>();
            for (Comp c : digs) if (c.y1 - c.y0 + 1 >= 0.6f * hmax) real.add(c);
            if (real.isEmpty() || real.size() > 2) return null;
            java.util.Collections.sort(real, new java.util.Comparator<Comp>() {
                public int compare(Comp a, Comp b) { return a.x0 - b.x0; }
            });
            StringBuilder s = new StringBuilder();
            float conf = 1;
            int[] val = new int[2];
            for (int k = 0; k < real.size(); k++) {
                Comp c = real.get(k);
                boolean[] one = new boolean[bw * bh];
                for (int i : c.px) one[i] = true;
                float[] ar = new float[1];
                float[] g = norm(one, bw, c.x0, c.y0, c.x1, c.y1, ar);
                int bd = -1; float best = 1e9f;
                for (int d = 0; d < 10; d++) {
                    float e = 0;
                    for (int i = 0; i < g.length; i++) e += Math.abs(g[i] - tpl[d][i]);
                    e = e / g.length + 0.3f * Math.abs(ar[0] - tplAr[d]);
                    if (e < best) { best = e; bd = d; }
                }
                val[k] = bd;
                s.append(bd);
                conf = Math.min(conf, 1 - best);
            }
            Result r = new Result();
            if (real.size() == 2) { r.whole = val[0]; r.tenth = val[1]; s.insert(1, '.'); }
            else { r.whole = val[0]; r.tenth = 0; }
            r.text = s.toString();
            r.conf = conf;
            // 3) direction: each chevron's tip (the row through its middle reaches furthest that way)
            int votes = 0;
            for (Comp c : arrows) {
                if (c.n < 8) continue;
                float ym = (c.y0 + c.y1) / 2f;
                int midMax = -1, midMin = 1 << 30, topMax = -1, topMin = 1 << 30, botMax = -1, botMin = 1 << 30;
                for (int i : c.px) {
                    int x = i % bw, y = i / bw;
                    if (Math.abs(y - ym) <= 1) { midMax = Math.max(midMax, x); midMin = Math.min(midMin, x); }
                    if (y <= c.y0 + 1) { topMax = Math.max(topMax, x); topMin = Math.min(topMin, x); }
                    if (y >= c.y1 - 1) { botMax = Math.max(botMax, x); botMin = Math.min(botMin, x); }
                }
                if (midMax < 0 || topMax < 0 || botMax < 0) continue;
                votes += Integer.signum(midMax - Math.max(topMax, botMax));
            }
            r.dir = votes > 0 ? 1 : votes < 0 ? -1 : 0;
            return r;
        } catch (Throwable t) {
            CrashLog.note("windRead", t);
            return null;
        }
    }
}
