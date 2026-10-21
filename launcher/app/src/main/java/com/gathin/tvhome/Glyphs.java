package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/**
 * Dock marks, built to blueprint D1. YouTube / Netflix / Music / Plex are monochrome shapes in a
 * 76 px glyph box; prime / hulu / tv are Outfit 300 wordmarks at 38 px; D+ / P+ are a 38 px letter
 * with a 24 px superscript. One fill colour (the theme's text token) so light mode gets ink glyphs.
 * Unknown apps get the honest fallback: the first letter of the label inside a thin ring.
 * Never the store icon tinted, never the app's own drawable.
 */
final class Glyphs {

    private static final int SVG = 76;   // vector glyph box (px)
    private static final int WM  = 38;   // wordmark cap height (px)
    private static final int PAD = 8;

    private Glyphs() {}

    static Drawable forApp(Context ctx, String label, String pkg, float scale, int ink, int cut) {
        float s = scale;
        // Match on the publishers' real package prefixes; a loose substring once gave
        // "Eco Dashboard" the max mark (dashBOard). Unknown apps get the honest monogram.
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        if (p.equals("com.google.android.youtube.tvmusic") || p.startsWith("com.spotify") || p.startsWith("com.pandora")
                || p.startsWith("com.apple.android.music") || p.startsWith("com.amazon.music"))
            return shape(ctx, Shape.MUSIC, SVG * s, ink, cut);
        if (p.startsWith("com.google.android.youtube")) return shape(ctx, Shape.YOUTUBE, SVG * s, ink, cut);
        if (p.startsWith("com.netflix.")) return shape(ctx, Shape.NETFLIX, SVG * s, ink, cut);
        if (p.startsWith("com.plexapp.")) return shape(ctx, Shape.PLEX, SVG * s, ink, cut);
        if (p.startsWith("com.amazon.amazonvideo")) return wm(ctx, "prime", 300, WM * s, ink);
        if (p.startsWith("com.disney.")) return wmPlus(ctx, "D", 600, WM * s, ink);
        if (p.startsWith("com.cbs.") || p.contains("paramount")) return wmPlus(ctx, "P", 300, WM * s, ink);
        if (p.startsWith("com.hulu.")) return wm(ctx, "hulu", 300, WM * s, ink);
        if (p.startsWith("com.apple.atve")) return wm(ctx, "tv", 300, WM * s, ink);
        if (p.startsWith("com.hbo.") || p.startsWith("com.wbd.")) return wm(ctx, "max", 300, WM * s, ink);
        if (p.startsWith("com.peacocktv.")) return wm(ctx, "peacock", 300, WM * 0.8f * s, ink);
        if (p.startsWith("com.tubitv")) return wm(ctx, "tubi", 600, WM * s, ink);
        if (p.startsWith("tv.pluto.")) return wm(ctx, "pluto", 300, WM * 0.9f * s, ink);
        return monogram(ctx, label, SVG * s, ink);
    }

    // ---- shapes (the blueprint's 64-unit glyph paths) ----------------------------------------------

    private enum Shape { YOUTUBE, NETFLIX, MUSIC, PLEX }

    private static Drawable shape(Context ctx, Shape sh, float px, int ink, int cut) {
        int n = Math.round(px);
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        bmp.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(bmp);
        float k = n / 64f;
        c.scale(k, k);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(ink);
        Path path = new Path();
        switch (sh) {
            case YOUTUBE:
                c.drawRoundRect(new RectF(6, 14, 58, 50), 10, 10, p);
                path.moveTo(27, 23); path.lineTo(27, 41); path.lineTo(42, 32); path.close();
                Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
                cp.setColor(cut);
                c.drawPath(path, cp);
                break;
            case NETFLIX:
                path.moveTo(18, 8); path.lineTo(27, 8); path.lineTo(37, 32); path.lineTo(37, 8); path.lineTo(46, 8);
                path.lineTo(46, 56); path.lineTo(37, 56); path.lineTo(27, 32); path.lineTo(27, 56); path.lineTo(18, 56); path.close();
                c.drawPath(path, p);
                break;
            case MUSIC:   // two beamed notes
                path.moveTo(26, 10); path.lineTo(54, 4); path.lineTo(54, 42); path.lineTo(48, 42);
                path.lineTo(48, 17); path.lineTo(32, 20.4f); path.lineTo(32, 46); path.lineTo(26, 46); path.close();
                c.drawPath(path, p);
                c.drawCircle(45, 42, 9, p);
                c.drawCircle(23, 46, 9, p);
                break;
            case PLEX:
                path.moveTo(20, 10); path.lineTo(44, 32); path.lineTo(20, 54); path.close();
                c.drawPath(path, p);
                break;
        }
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    // ---- corner marks (Theater's top-right: flat, no plates) ---------------------------------------

    enum Corner { WIFI, WIFI_OFF, ETHERNET, SLIDERS }

    /** A flat corner mark in the same 64-unit box and one ink as the dock glyphs. */
    static Drawable corner(Context ctx, Corner kind, float px, int ink) {
        int n = Math.round(px);
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        bmp.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(bmp);
        c.scale(n / 64f, n / 64f);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(ink);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(ink);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(5.5f);
        switch (kind) {
            case WIFI:
            case WIFI_OFF: {   // three arcs over a dot; offline is the same mark faded, struck through
                if (kind == Corner.WIFI_OFF) { line.setAlpha(0x59); fill.setAlpha(0x59); }
                for (int r = 13; r <= 39; r += 13) {
                    c.drawArc(new RectF(32 - r, 52 - r, 32 + r, 52 + r), -135, 90, false, line);
                }
                c.drawCircle(32, 52, 4.5f, fill);
                if (kind == Corner.WIFI_OFF) {
                    line.setAlpha(255);
                    c.drawLine(12, 10, 52, 56, line);
                }
                break;
            }
            case ETHERNET: {   // one box up top, a bus, two boxes below
                c.drawRoundRect(new RectF(24, 6, 40, 22), 3, 3, fill);
                c.drawRoundRect(new RectF(6, 42, 22, 58), 3, 3, fill);
                c.drawRoundRect(new RectF(42, 42, 58, 58), 3, 3, fill);
                line.setStrokeWidth(4.5f);
                c.drawLine(32, 22, 32, 32, line);
                c.drawLine(14, 32, 50, 32, line);
                c.drawLine(14, 32, 14, 42, line);
                c.drawLine(50, 32, 50, 42, line);
                break;
            }
            case SLIDERS: {    // three rails, a knob on each at a different stop
                float[] ys = {14, 32, 50}, xs = {40, 20, 46};
                for (int i = 0; i < 3; i++) {
                    c.drawLine(8, ys[i], 56, ys[i], line);
                    c.drawCircle(xs[i], ys[i], 7.5f, fill);
                }
                break;
            }
        }
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    /** Fallback: first letter of the label in a 4 px ring (D1). Correct and honest, not a gap. */
    private static Drawable monogram(Context ctx, String label, float px, int ink) {
        int n = Math.round(px);
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        bmp.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(bmp);
        float k = n / 64f;
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4 * k);
        ring.setColor(ink);
        c.drawCircle(n / 2f, n / 2f, 26 * k, ring);
        Paint t = paint(ctx, 600, ink);
        t.setTextSize(30 * k);
        t.setTextAlign(Paint.Align.CENTER);
        String ch = firstLetter(label);
        Rect b = new Rect();
        t.getTextBounds(ch, 0, ch.length(), b);
        c.drawText(ch, n / 2f, n / 2f + b.height() / 2f, t);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    // ---- wordmark text ---------------------------------------------------------------------------

    private static Paint paint(Context ctx, int weight, int ink) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(ink);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Ui.outfit(ctx));
        p.setFontVariationSettings("'wght' " + weight);
        return p;
    }

    private static Drawable wm(Context ctx, String str, int weight, float capPx, int ink) {
        if (str == null || str.isEmpty()) str = "·";
        Paint p = paint(ctx, weight, ink);
        p.setTextSize(100f);
        Rect b = new Rect();
        p.getTextBounds(str, 0, str.length(), b);
        if (b.height() == 0) b.set(0, -(int) capPx, (int) capPx, 0);
        p.setTextSize(100f * capPx / b.height());
        p.getTextBounds(str, 0, str.length(), b);
        Bitmap bmp = Bitmap.createBitmap(b.width() + PAD * 2, b.height() + PAD * 2, Bitmap.Config.ARGB_8888);
        bmp.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(bmp);
        c.drawText(str, PAD - b.left, PAD - b.top, p);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    /** A letter at capPx with a small superscript "+" (24/38 of cap, weight 500), top-aligned. */
    private static Drawable wmPlus(Context ctx, String letter, int weight, float capPx, int ink) {
        Paint pl = paint(ctx, weight, ink);
        pl.setTextSize(100f);
        Rect bl = new Rect();
        pl.getTextBounds(letter, 0, letter.length(), bl);
        pl.setTextSize(100f * capPx / bl.height());
        pl.getTextBounds(letter, 0, letter.length(), bl);

        float superCap = capPx * 24f / 38f;
        Paint ps = paint(ctx, 500, ink);
        ps.setTextSize(100f);
        Rect bs = new Rect();
        ps.getTextBounds("+", 0, 1, bs);
        ps.setTextSize(100f * superCap / bs.height());
        ps.getTextBounds("+", 0, 1, bs);

        int w = bl.width() + 2 + bs.width() + PAD * 2;
        int h = bl.height() + PAD * 2;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(bmp);
        c.drawText(letter, PAD - bl.left, PAD - bl.top, pl);
        c.drawText("+", PAD + bl.width() + 2 - bs.left, PAD - bs.top, ps);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    /**
     * The focus halo (blueprint B2: gold at 55%, blur 16 px) as a pre-rendered sprite: the glyph's
     * silhouette blurred once into a bitmap. The dock fades it in with alpha; nothing blurs per frame.
     */
    static Drawable halo(Context ctx, Drawable glyph, int gold) {
        if (!(glyph instanceof BitmapDrawable)) return null;
        Bitmap src = ((BitmapDrawable) glyph).getBitmap();
        int pad = 20;
        Bitmap out = Bitmap.createBitmap(src.getWidth() + pad * 2, src.getHeight() + pad * 2, Bitmap.Config.ARGB_8888);
        out.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(out);
        Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG);
        blur.setMaskFilter(new android.graphics.BlurMaskFilter(16, android.graphics.BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        Bitmap alpha = src.extractAlpha(blur, off);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor((gold & 0x00FFFFFF) | 0x8C000000);   // 55%
        c.drawBitmap(alpha, pad + off[0], pad + off[1], p);
        alpha.recycle();
        return new BitmapDrawable(ctx.getResources(), out);
    }

    static String firstLetter(String label) {
        if (label == null) return "·";
        for (int i = 0; i < label.length(); i++) {
            char ch = label.charAt(i);
            if (Character.isLetterOrDigit(ch)) return String.valueOf(Character.toUpperCase(ch));
        }
        return "·";
    }
}
