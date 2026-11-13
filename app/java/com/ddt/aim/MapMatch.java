package com.ddt.aim;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Finds which battle map the in-game minimap shows and where it sits on screen, then builds a
 * "solid ground" bitmap of the minimap from the live screen (so ground already blown away counts as air).
 *
 * Templates: assets/maps/<id>.webp = ground layers only (dig + stone) at 2 px per minimap design unit
 * (2 × minimap scale, minimap scale = min(120 / mapH, 240 / mapW)), generated from the game's own map files.
 * On a given phone the minimap is drawn at a fixed size per design unit and pinned to the same top-right
 * spot, so one geometry (right edge R, top T, screen px per template px f) fits every map.
 */
public class MapMatch {
    static class Tpl {
        String id, name;
        int w, h, pw, ph;         // battlefield size, template size
        int[] sx, sy, lum;        // interior ground samples (template px) and their brightness
    }

    static List<Tpl> maps;
    static final int NS = 300;
    static String diag = "";      // why the last search failed (shown to the person)
    static final boolean DBG = System.getProperty("ddt.dbg") != null;

    static synchronized boolean load(Context c) {
        if (maps != null) return !maps.isEmpty();
        maps = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(readAsset(c, "maps/index.json"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tpl t = new Tpl();
                t.id = o.getString("id"); t.name = o.getString("name");
                t.w = o.getInt("w"); t.h = o.getInt("h"); t.pw = o.getInt("pw"); t.ph = o.getInt("ph");
                Bitmap b = bitmap(c, t.id);
                if (b == null) continue;
                sample(t, b);
                b.recycle();
                if (t.sx.length >= 20) maps.add(t);
            }
        } catch (Throwable e) {
            CrashLog.note("mapLoad", e);
        }
        return !maps.isEmpty();
    }

    static Bitmap bitmap(Context c, String id) {
        try {
            InputStream in = c.getAssets().open("maps/" + id + ".webp");
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) { return null; }
    }

    static String readAsset(Context c, String name) throws Exception {
        InputStream in = c.getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Ground pixels whose neighbours 2 px away are ground too (robust to a 1 px misfit). */
    static void sample(Tpl t, Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] p = new int[w * h];
        b.getPixels(p, 0, w, 0, 0, w, h);
        List<Integer> ok = new ArrayList<>();
        for (int y = 2; y < h - 2; y++)
            for (int x = 2; x < w - 2; x++) {
                int i = y * w + x;
                if (a(p[i]) < 250 || a(p[i - 2]) < 250 || a(p[i + 2]) < 250 || a(p[i - 2 * w]) < 250 || a(p[i + 2 * w]) < 250) continue;
                ok.add(i);
            }
        Random r = new Random(1);
        int n = Math.min(NS, ok.size());
        t.sx = new int[n]; t.sy = new int[n]; t.lum = new int[n];
        for (int k = 0; k < n; k++) {
            int j = k + r.nextInt(ok.size() - k);
            int v = ok.get(j); ok.set(j, ok.get(k)); ok.set(k, v);
            t.sx[k] = v % w; t.sy[k] = v / w; t.lum[k] = lum(p[v]);
        }
    }

    static int a(int c) { return c >>> 24; }
    static int lum(int c) { return (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3; }

    // ---------- scoring ----------
    /** Correlation of template vs screen brightness after dropping the 35 % worst-fitting samples
     *  (markers, the view box, smoke and blown-away ground do not spoil it). */
    static float score(Tpl t, int n, int[] scr, int sw, int sh, int ox, int oy, float f, float[] fit) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int[] L = new int[n];
        for (int k = 0; k < n; k++) {
            int x = ox + Math.round(t.sx[k] * f), y = oy + Math.round(t.sy[k] * f);
            if (x < 0 || y < 0 || x >= sw || y >= sh) return -1;
            int l = lum(scr[y * sw + x]);
            L[k] = l;
            double s = t.lum[k];
            sx += s; sy += l; sxx += s * s; sxy += s * l;
        }
        double vx = sxx - sx * sx / n;
        if (vx < 1) return -1;
        double kk = (sxy - sx * sy / n) / vx, bb = (sy - kk * sx) / n;
        if (kk < 0.2) return 0;
        if (n < 100) {                       // coarse search: plain correlation, no trimming
            double vy = 0;
            for (int k = 0; k < n; k++) vy += (L[k] - sy / n) * (L[k] - sy / n);
            return vy < 1 ? 0 : (float) ((sxy - sx * sy / n) / Math.sqrt(vx * vy));
        }
        float[] r = new float[n];
        for (int k = 0; k < n; k++) r[k] = (float) Math.abs(L[k] - (kk * t.lum[k] + bb));
        float[] srt = r.clone();
        java.util.Arrays.sort(srt);
        float cut = srt[(int) (n * 0.65f)];
        double a1 = 0, b1 = 0, aa = 0, bb2 = 0, ab = 0;
        int m = 0;
        for (int k = 0; k < n; k++) {
            if (r[k] > cut) continue;
            double s = t.lum[k], l = L[k];
            a1 += s; b1 += l; aa += s * s; bb2 += l * l; ab += s * l; m++;
        }
        if (m < 10) return 0;
        double cov = ab - a1 * b1 / m, va = aa - a1 * a1 / m, vb = bb2 - b1 * b1 / m;
        if (va < 1 || vb < 1) return 0;
        if (fit != null) { fit[0] = (float) kk; fit[1] = (float) bb; }
        return (float) (cov / Math.sqrt(va * vb));
    }

    static class Hit {
        Tpl t; float score; int ox, oy; float f; float[] fit = new float[2];
    }

    /** Best placement of one map near (ox, oy) within ±r px, scale f × (1 ± fs). */
    static Hit refine(Tpl t, int[] scr, int sw, int sh, int ox0, int oy0, int r, float f0, float fs) {
        return refine(t, scr, sw, sh, ox0, oy0, r, f0, fs, 0.01f);
    }

    static Hit refine(Tpl t, int[] scr, int sw, int sh, int ox0, int oy0, int r, float f0, float fs, float step) {
        Hit best = null;
        float[] fit = new float[2];
        for (float g = -fs; g <= fs + 1e-4f; g += step) {
            float f = f0 * (1 + g);
            int rx = ox0 + Math.round(t.pw * f0) - Math.round(t.pw * f);    // keep the right edge
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++) {
                    float s = score(t, t.sx.length, scr, sw, sh, rx + dx, oy0 + dy, f, fit);
                    if (best == null || s > best.score) {
                        best = new Hit(); best.t = t; best.score = s; best.ox = rx + dx; best.oy = oy0 + dy; best.f = f;
                        best.fit[0] = fit[0]; best.fit[1] = fit[1];
                    }
                }
        }
        return best;
    }

    /**
     * scr: screen pixels of region (rx0, ry0, sw, sh). fw: frame width estimate (screen px per 1000 battle px).
     * Returns null when no map fits well enough.
     */
    static Hit find(SharedPreferences prefs, String key, String lastId, int[] scr, int sw, int sh, int rx0, int ry0, float fw) {
        if (maps == null || maps.isEmpty()) return null;
        float R = prefs.getFloat(key + "R", -1), T = prefs.getFloat(key + "T", -1), F = prefs.getFloat(key + "F", -1);
        if (F <= 0) {
            // not learned yet: start from the spot measured on a 2576 × 1165 phone (right edge 48 units in, top 5 %,
            // 0.80 minimap units per design unit), scaled to this screen; a good match confirms and stores it
            int W = rx0 + sw, H = 2 * (ry0 + sh);
            R = W * (1 - 48.5f / 1136); T = H * 0.0498f; F = 0.9125f * W / 2576f;
            Hit h = null, s2 = null;
            for (Tpl t : maps) {
                Hit c = refine(t, scr, sw, sh, Math.round(R - t.pw * F) - rx0, Math.round(T) - ry0, 2, F, 0);
                if (c == null) continue;
                if (h == null || c.score > h.score) { s2 = h; h = c; } else if (s2 == null || c.score > s2.score) s2 = c;
            }
            if (h != null && h.score >= 0.88f && (s2 == null || h.score - s2.score >= 0.06f)) {
                Hit fine = refine(h.t, scr, sw, sh, h.ox, h.oy, 2, F, 0.015f, 0.0025f);
                if (fine != null && fine.score >= h.score) h = fine;
                prefs.edit().putFloat(key + "R", rx0 + h.ox + h.t.pw * h.f).putFloat(key + "T", ry0 + h.oy).putFloat(key + "F", h.f).apply();
                return finish(h, rx0, ry0);
            }
            F = -1;
        }
        Hit best = null, second = null, lastHit = null;
        if (F > 0) {
            // known geometry: every map at the stored spot
            for (Tpl t : maps) {
                int ox = Math.round(R - t.pw * F) - rx0, oy = Math.round(T) - ry0;
                Hit h = refine(t, scr, sw, sh, ox, oy, 1, F, 0);
                if (h == null) continue;
                if (lastId != null && t.id.equals(lastId)) { h.score += 0.03f; lastHit = h; }      // same match as last turn
                if (best == null || h.score > best.score) { second = best; best = h; }
                else if (second == null || h.score > second.score) second = h;
            }
            diag = "จำตำแหน่งแล้ว · ใกล้สุด " + (best == null ? "-" : best.t.name + " " + fmt(best.score)) + (second == null ? "" : " / " + second.t.name + " " + fmt(second.score));
            if (best != null && best.score >= 0.80f && (second == null || best.score - second.score >= 0.06f)) return finish(best, rx0, ry0);
            // a busy turn (smoke, half the minimap covered, ground blown away): keep last turn's map if it still fits
            if (lastHit != null && lastHit.score >= 0.55f && (best == null || best.score - lastHit.score < 0.12f)) return finish(lastHit, rx0, ry0);
        }
        if (fw <= 3) { diag += " · ไม่มีค่ากรอบ"; return null; }
        // first time on this phone (or the stored spot no longer fits): search the region, scale from the frame width.
        // The remembered frame width may come from another map (each map's minimap has its own scale), so try a few.
        // keep the best over all tries; a different map must trail it clearly (a wrong spot stored here would spoil every later turn)
        Hit all = null, other = null;
        for (float mul : new float[]{1f, 0.92f, 1.08f, 0.85f, 1.17f, 0.78f, 1.27f, 0.72f, 1.38f}) {
            Hit[] r = search(scr, sw, sh, rx0, ry0, fw * mul);
            for (Hit h : r) {
                if (h == null) continue;
                if (all == null || h.score > all.score) {
                    if (all != null && !all.t.id.equals(h.t.id) && (other == null || all.score > other.score)) other = all;
                    all = h;
                } else if (!h.t.id.equals(all.t.id) && (other == null || h.score > other.score)) other = h;
            }
        }
        if (all != null) {             // fine pass: scale in 0.25 % steps so the stored spot is exact
            Hit fine = refine(all.t, scr, sw, sh, all.ox, all.oy, 2, all.f, 0.015f, 0.0025f);
            if (fine != null && fine.score >= all.score) all = fine;
        }
        if (all != null) {
            int W = rx0 + sw, H0 = 2 * (ry0 + sh);   // the region is the top half of the screen
            float right = rx0 + all.ox + all.t.pw * all.f, top = ry0 + all.oy, uk = 2 * all.f * 1136f / W;
            if (right < W * (1 - 80f / 1136) || right > W * (1 - 20f / 1136) || top < H0 * 0.015f || top > H0 * 0.11f || uk < 0.55f || uk > 1.1f) all = null;
        }
        if (all == null || all.score < 0.9f || (other != null && all.score - other.score < 0.05f)) {
            diag = (F > 0 ? diag + " · " : "") + "หาตำแหน่งมินิแมพ: ใกล้สุด " + (all == null ? "-" : all.t.name + " " + fmt(all.score)) + (other == null ? "" : " / " + other.t.name + " " + fmt(other.score)) + " · กรอบ " + Math.round(fw) + " px";
            return null;
        }
        prefs.edit().putFloat(key + "R", rx0 + all.ox + all.t.pw * all.f).putFloat(key + "T", ry0 + all.oy).putFloat(key + "F", all.f).apply();
        return finish(all, rx0, ry0);
    }

    /** Best and runner-up (different maps) for one scale guess, positions relative to the region. */
    static Hit[] search(int[] scr, int sw, int sh, int rx0, int ry0, float fw) {
        Hit best, second;
        float q = fw / 1000f;
        List<Hit> top = new ArrayList<>();
        for (Tpl t : maps) {
            float ms = Math.min(120f / t.h, 240f / t.w);
            float f = q / (2 * ms);
            int tw = Math.round(t.pw * f), th = Math.round(t.ph * f);
            if (tw > sw || th > sh) continue;
            int n = Math.min(60, t.sx.length);
            Hit hb = null;
            // the game pins its minimap to the top-right corner (layout is FIXED_WIDTH: 1136 design units across):
            // right edge 10–90 units from the screen edge, top in the upper quarter of the screen
            int W = rx0 + sw, H0 = 2 * (ry0 + sh);   // the region is the top half of the screen
            // (seen on the phone: right edge 48 units from the screen edge, top at 5 % of the height)
            float uk = 2 * f * 1136f / W;            // minimap units on screen ÷ design units: ~0.8
            if (uk < 0.55f || uk > 1.1f) continue;
            int oxMin = Math.max(0, Math.round(W * (1 - 75f / 1136)) - tw - rx0), oxMax = Math.min(sw - tw, Math.round(W * (1 - 25f / 1136)) - tw - rx0);
            int oyMin = Math.max(0, Math.round(H0 * 0.02f) - ry0), oyMax = Math.min(sh - th, Math.round(H0 * 0.10f) - ry0);
            for (int oy = oyMin; oy <= oyMax; oy += 3)
                for (int ox = oxMin; ox <= oxMax; ox += 3) {
                    float s = score(t, n, scr, sw, sh, ox, oy, f, null);
                    if (hb == null || s > hb.score) { hb = new Hit(); hb.t = t; hb.score = s; hb.ox = ox; hb.oy = oy; hb.f = f; }
                }
            if (hb != null) top.add(hb);
        }
        java.util.Collections.sort(top, new java.util.Comparator<Hit>() {
            public int compare(Hit a, Hit b) { return Float.compare(b.score, a.score); }
        });
        best = null; second = null;
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            Hit c = top.get(i);
            Hit h = refine(c.t, scr, sw, sh, c.ox, c.oy, 4, c.f, 0.07f);
            if (DBG) System.out.println("  cand " + c.t.id + " coarse " + c.score + " -> " + h.score + " @" + (rx0 + h.ox) + "," + (ry0 + h.oy) + " f=" + h.f);
            if (best == null || h.score > best.score) { second = best; best = h; }
            else if (second == null || h.score > second.score) second = h;
        }
        return new Hit[]{best, second};
    }

    static String fmt(float v) { return String.valueOf(Math.round(v * 100) / 100f); }

    static Hit finish(Hit h, int rx0, int ry0) {
        h.ox += rx0; h.oy += ry0;
        return h;
    }

    /**
     * Solid-ground bits over the minimap rectangle on screen (1 bit per screen px, row-major, LSB first):
     * template ground that still looks like the template on the live screen. Ground blown away shows the
     * dimmed background instead, so it reads as air.
     */
    static JSONObject build(Context c, Hit h, Bitmap frame, int scrW, int scrH) {
        try {
            int w = Math.round(h.t.pw * h.f), hh = Math.round(h.t.ph * h.f);
            int x0 = Math.max(0, h.ox), y0 = Math.max(0, h.oy);
            int x1 = Math.min(scrW, h.ox + w), y1 = Math.min(scrH, h.oy + hh);
            if (x1 - x0 < 10 || y1 - y0 < 10) return null;
            Bitmap tb = bitmap(c, h.t.id);
            if (tb == null) return null;
            Bitmap ts = Bitmap.createScaledBitmap(tb, w, hh, true);
            int[] tp = new int[w * hh];
            ts.getPixels(tp, 0, w, 0, 0, w, hh);
            if (ts != tb) ts.recycle();
            tb.recycle();
            int rw = x1 - x0, rh = y1 - y0;
            int[] sp = new int[rw * rh];
            frame.getPixels(sp, 0, rw, x0, y0, rw, rh);
            float k = h.fit[0], b = h.fit[1];
            // residual per pixel (template ground only), then a 5×5 box mean so JPEG-like noise and thin
            // template edges do not punch holes; only real gaps (several px wide) survive
            float[] res = new float[rw * rh];
            boolean[] g = new boolean[rw * rh];
            for (int y = 0; y < rh; y++)
                for (int x = 0; x < rw; x++) {
                    int tx = x + x0 - h.ox, ty = y + y0 - h.oy;
                    int tpx = tp[ty * w + tx];
                    if (a(tpx) < 128) continue;
                    int s = sp[y * rw + x];
                    float d = (Math.abs(((s >> 16) & 255) - (k * ((tpx >> 16) & 255) + b))
                            + Math.abs(((s >> 8) & 255) - (k * ((tpx >> 8) & 255) + b))
                            + Math.abs((s & 255) - (k * (tpx & 255) + b))) / 3f;
                    res[y * rw + x] = d;
                    g[y * rw + x] = true;
                }
            float[] I = new float[(rw + 1) * (rh + 1)];
            int[] C = new int[(rw + 1) * (rh + 1)];
            for (int y = 0; y < rh; y++) {
                float rs = 0; int cs = 0;
                for (int x = 0; x < rw; x++) {
                    if (g[y * rw + x]) { rs += res[y * rw + x]; cs++; }
                    I[(y + 1) * (rw + 1) + x + 1] = I[y * (rw + 1) + x + 1] + rs;
                    C[(y + 1) * (rw + 1) + x + 1] = C[y * (rw + 1) + x + 1] + cs;
                }
            }
            byte[] bits = new byte[(rw * rh + 7) / 8];
            int solid = 0, holes = 0;
            for (int y = 0; y < rh; y++)
                for (int x = 0; x < rw; x++) {
                    int i = y * rw + x;
                    if (!g[i]) continue;
                    int ax = Math.max(0, x - 2), ay = Math.max(0, y - 2), bx = Math.min(rw, x + 3), by = Math.min(rh, y + 3);
                    float sum = I[by * (rw + 1) + bx] - I[ay * (rw + 1) + bx] - I[by * (rw + 1) + ax] + I[ay * (rw + 1) + ax];
                    int cnt = C[by * (rw + 1) + bx] - C[ay * (rw + 1) + bx] - C[by * (rw + 1) + ax] + C[ay * (rw + 1) + ax];
                    if (cnt > 0 && sum / cnt > 55) { holes++; continue; }
                    bits[i >> 3] |= (byte) (1 << (i & 7));
                    solid++;
                }
            JSONObject o = new JSONObject();
            o.put("id", h.t.id);
            o.put("name", h.t.name);
            o.put("score", Math.round(h.score * 100) / 100.0);
            o.put("ox", x0); o.put("oy", y0); o.put("w", rw); o.put("h", rh);
            o.put("mox", h.ox); o.put("moy", h.oy);
            o.put("mw", w); o.put("mh", hh);
            o.put("f", h.f);
            o.put("ms", Math.min(120.0 / h.t.h, 240.0 / h.t.w));
            o.put("solid", solid); o.put("holes", holes);
            o.put("bits", android.util.Base64.encodeToString(bits, android.util.Base64.NO_WRAP));
            return o;
        } catch (Throwable e) {
            CrashLog.note("mapBuild", e);
            return null;
        }
    }
}
