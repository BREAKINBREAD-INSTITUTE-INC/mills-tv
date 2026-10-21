package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * The dock's frost slice (blueprint D2). Pre-rendered wallpapers ship theirs; wallpapers drawn
 * from XML get one made here, once, on a background thread when they're applied: drawn at a
 * quarter size, blurred (three box passes ≈ gaussian), desaturated 15%, highlights soft-clipped.
 * Never a per-frame blur. A video background's frost is made the same way from one frame of
 * the clip it is showing (the Nebula's, if the clip can't be read).
 */
final class FrostMaker {
    private FrostMaker() {}

    private static final int W = 480, H = 270;   // quarter of the 1920x1080 panel
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Got { void got(Bitmap frost); }

    static void get(Context ctx, String wallpaperId, boolean light, Got cb) {
        final java.io.File clip = VideoBackgrounds.fileFor(ctx, wallpaperId);
        final String key = clip != null ? "video:" + clip.getName() + "@" + clip.lastModified()
                : wallpaperId + (light ? ":l" : ":d");
        Bitmap hit = CACHE.get(key);
        if (hit != null) { cb.got(hit); return; }
        final int res = Wallpapers.frostFor(wallpaperId, light);
        final int wp = Wallpapers.drawableFor(wallpaperId, light);
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Bitmap out;
            Bitmap frame = clip != null ? VideoBackgrounds.frameNow(clip, W, H) : null;
            if (frame != null) {
                out = make(new android.graphics.drawable.BitmapDrawable(app.getResources(), frame));
            } else if (res != 0) {
                out = BitmapFactory.decodeResource(app.getResources(), res);
            } else {
                out = make(app.getDrawable(wp));
            }
            final Bitmap f = out;
            MAIN.post(() -> { if (f != null) CACHE.put(key, f); cb.got(f); });
        }, "mills-frost").start();
    }

    private static Bitmap make(Drawable d) {
        try {
            if (d == null) return null;
            Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, W, H);
            d.draw(cv);
            int[] px = new int[W * H];
            b.getPixels(px, 0, W, 0, 0, W, H);
            int[] tmp = new int[W * H];
            for (int pass = 0; pass < 3; pass++) {       // ~42 px at full size
                boxH(px, tmp, 6);
                boxV(tmp, px, 6);
            }
            for (int i = 0; i < px.length; i++) px[i] = grade(px[i]);
            b.setPixels(px, 0, W, 0, 0, W, H);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Desaturate 15% and pull anything above ~L85 down, so a bright glow can't bloom through. */
    private static int grade(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        r = (int) (r + (y - r) * 0.15f); g = (int) (g + (y - g) * 0.15f); b = (int) (b + (y - b) * 0.15f);
        if (y > 214f) { float k = 214f / y; r = (int) (r * k); g = (int) (g * k); b = (int) (b * k); }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void boxH(int[] in, int[] out, int rad) {
        int div = rad * 2 + 1;
        for (int y = 0; y < H; y++) {
            int row = y * W, sr = 0, sg = 0, sb = 0;
            for (int k = -rad; k <= rad; k++) {
                int p = in[row + clamp(k, W)];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int x = 0; x < W; x++) {
                out[row + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[row + clamp(x - rad, W)], pIn = in[row + clamp(x + rad + 1, W)];
                sr += ((pIn >> 16) & 255) - ((pOut >> 16) & 255);
                sg += ((pIn >> 8) & 255) - ((pOut >> 8) & 255);
                sb += (pIn & 255) - (pOut & 255);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int rad) {
        int div = rad * 2 + 1;
        for (int x = 0; x < W; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -rad; k <= rad; k++) {
                int p = in[clamp(k, H) * W + x];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int y = 0; y < H; y++) {
                out[y * W + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[clamp(y - rad, H) * W + x], pIn = in[clamp(y + rad + 1, H) * W + x];
                sr += ((pIn >> 16) & 255) - ((pOut >> 16) & 255);
                sg += ((pIn >> 8) & 255) - ((pOut >> 8) & 255);
                sb += (pIn & 255) - (pOut & 255);
            }
        }
    }

    // mirror at the edges (D2: no dark fringe)
    private static int clamp(int i, int n) {
        if (i < 0) return Math.min(n - 1, -i);
        if (i >= n) return Math.max(0, 2 * n - i - 2);
        return i;
    }
}
