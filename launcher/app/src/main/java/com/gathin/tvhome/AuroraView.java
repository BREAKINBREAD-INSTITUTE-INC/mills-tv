package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * The moving backdrops (Settings > Background), drawn over the wallpaper's own base gradient,
 * which shows through everywhere this view is transparent.
 *   Deep Aurora   two soft glows, teal + gold, drifting in opposite directions
 *   Aurora Color  five saturated glows (violet, magenta, blue, teal, gold) drifting and blending
 *   Color Waves   long soft bands of colour rolling slowly across
 *   Nebula        deep-space glows, slow-drifting light and faint twinkling stars
 *   Sunset Drift  warm orange, pink and purple pools slowly shifting
 *
 * Light on the TV's GPU: a handful of large radial gradients, each made once and only moved by
 * its matrix, painted into a small bitmap (1/SCALE of the screen) on a background thread at most
 * ~30 times a second (FRAME_MS). The screen only scales that one bitmap up, smoothly, so the GPU
 * draws a single quad, never six full-screen gradients, and the UI thread does no painting. Soft
 * glows lose nothing at that size; only the stars are drawn at full size. Nothing is allocated
 * per frame. It stops while the screen is paused or hidden or something plays, and Motion
 * Reduced / Off shows one still frame. A soft scrim keeps the clock and dock name readable.
 */
public class AuroraView extends View {

    static final int NONE = 0, DEEP = 1, COLOR = 2, WAVES = 3, NEBULA = 4, SUNSET = 5;

    private static final long FRAME_MS = 33;             // ~30 fps cap: smooth drift, no more than that
    private static final long PLAYING_CHECK_MS = 1000;   // while media plays, look again once a second
    private static final float R0 = 1000f;               // shaders are made at this radius, then scaled
    private static final long START_MS = 37_000L;        // start mid-drift, not at the edges
    private static final int SCALE = 6;                   // the glows are painted at 1/6 size, then scaled up

    private static android.os.Handler painter;            // one background thread for every backdrop
    private static synchronized android.os.Handler painter() {
        if (painter == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("mills-aurora", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            painter = new android.os.Handler(t.getLooper());
        }
        return painter;
    }

    /**
     * One soft glow. It drifts around (cx, cy) by (ax, ay) of the screen over px / py seconds,
     * breathes in size and brightness, and slides from color to color2 and back over shift
     * seconds. sx > sy stretches it into a band. Every cycle is 8-20 s, so it visibly moves.
     */
    private static final class Glow {
        final int color, color2, alpha;
        final float cx, cy, ax, ay, px, py, phase, r, sx, sy, shift;
        Shader[] shaders;                                   // SHADES steps from color to color2, made once
        Glow(int color, int color2, int alpha, float cx, float cy, float ax, float ay, float px, float py,
             float shift, float phase, float r, float sx, float sy) {
            this.color = color; this.color2 = color2; this.alpha = alpha; this.cx = cx; this.cy = cy;
            this.ax = ax; this.ay = ay; this.px = px; this.py = py; this.shift = shift; this.phase = phase;
            this.r = r; this.sx = sx; this.sy = sy;
        }
        Glow(int color, int color2, int alpha, float cx, float cy, float ax, float ay, float px, float py, float shift, float phase, float r) {
            this(color, color2, alpha, cx, cy, ax, ay, px, py, shift, phase, r, 1f, 1f);
        }
    }

    private static final int SHADES = 24;                  // colour steps per glow: smooth, and nothing made per frame

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint();
    private final Matrix m = new Matrix();
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Object lock = new Object();             // guards the glows' shaders and the bitmaps
    private Bitmap front, back;                           // shown / being painted, both 1/SCALE size
    private Canvas backCanvas;
    private float frontSec = -1f;                         // the phase the front bitmap was painted at
    private volatile boolean painting;
    private final android.graphics.Rect dst = new android.graphics.Rect();
    private int style = DEEP;
    private boolean light, still, paused, scrim = true;
    private Glow[] glows = glows(DEEP, false);
    private float[][] stars;                             // Nebula: 3 twinkle groups of x,y pairs (0..1)
    private float[][] starPx;                            // the same in pixels for this size
    private int sizedW, sizedH;
    private long runStart, runBase = START_MS;           // the drift's phase survives pauses
    private boolean ticking;

    public AuroraView(Context c) { super(c); init(); }
    public AuroraView(Context c, AttributeSet a) { super(c, a); init(); }
    public AuroraView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        starPaint.setStrokeCap(Paint.Cap.ROUND);
        starPaint.setColor(0xFFFFFFFF);
    }

    /** Which backdrop, for the dark or light theme. Cheap when nothing changed. */
    void setStyle(int style, boolean light) {
        if (style == this.style && light == this.light) return;
        synchronized (lock) {
            this.style = style;
            this.light = light;
            glows = glows(style, light);
            stars = style == NEBULA ? makeStars() : null;
            starPx = null;
            sizedW = sizedH = 0;
            frontSec = -1f;
        }
        invalidate();
    }

    /** Draw one still frame and never animate (the Settings live preview). */
    void setStill(boolean still) { this.still = still; schedule(); }

    /** The legibility scrim; off where the host already has one (Theater, the dimmed screensaver). */
    void setScrim(boolean on) { synchronized (lock) { scrim = on; sizedW = 0; frontSec = -1f; } invalidate(); }

    /** Hosts call this from onPause / onResume: a paused screen draws nothing new. */
    void setPaused(boolean p) {
        if (p == paused) return;
        paused = p;
        schedule();
    }

    private boolean animates() {
        return !still && !paused && isAttachedToWindow() && getWindowVisibility() == VISIBLE
                && getVisibility() == VISIBLE && Motion.ambient(getContext());   // GONE (a still wallpaper, a video over it): paint nothing
    }

    private long phaseMs() { return ticking ? runBase + SystemClock.uptimeMillis() - runStart : runBase; }

    private void startClock() { if (!ticking) { runStart = SystemClock.uptimeMillis(); ticking = true; } }
    private void stopClock()  { if (ticking) { runBase = phaseMs(); ticking = false; } }

    private void schedule() {
        removeCallbacks(tick);
        if (animates()) { startClock(); postOnAnimationDelayed(tick, FRAME_MS); }
        else stopClock();
        invalidate();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!animates()) { schedule(); return; }
            if (mediaPlaying()) {                         // a video or a song: hold still, check back
                stopClock();
                postDelayed(this, PLAYING_CHECK_MS);
                return;
            }
            startClock();
            if (!painting && getWidth() > 0) {            // a slow frame is skipped, never queued up
                painting = true;
                final float sec = phaseMs() / 1000f;
                final int w = getWidth(), h = getHeight();
                painter().post(() -> { paint(w, h, sec); painting = false; postInvalidateOnAnimation(); });
            }
            postOnAnimationDelayed(this, FRAME_MS);
        }
    };

    private boolean mediaPlaying() {
        if (MediaWatcher.playing()) return true;
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Exception e) { return false; }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); schedule(); }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
        stopClock();
    }

    @Override protected void onVisibilityChanged(View changed, int v) {
        super.onVisibilityChanged(changed, v);
        if (changed == this && isAttachedToWindow()) schedule();
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (isAttachedToWindow()) schedule();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float sec = phaseMs() / 1000f;                // Reduced / Off: runBase, the same still every time
        synchronized (lock) {
            // nothing painted yet, a new size or style, or standing still: paint it here (small, quick)
            if (front == null || w != sizedW || h != sizedH || frontSec < 0 || (!ticking && frontSec != sec)) paintLocked(w, h, sec);
            dst.set(0, 0, w, h);
            canvas.drawBitmap(front, null, dst, blit);
            if (starPx != null) drawStars(canvas, frontSec);
        }
    }

    /** Paint one frame into the back bitmap and swap it to the front. Background thread. */
    private void paint(int w, int h, float sec) { synchronized (lock) { paintLocked(w, h, sec); } }

    private void paintLocked(int w, int h, float sec) {
        if (w != sizedW || h != sizedH) size(w, h);
        int bw = Math.max(32, w / SCALE), bh = Math.max(18, h / SCALE);
        if (back == null || back.getWidth() != bw || back.getHeight() != bh) {
            back = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            backCanvas = new Canvas(back);
            if (front != null && (front.getWidth() != bw || front.getHeight() != bh)) front = null;
        }
        back.eraseColor(0);
        backCanvas.save();
        backCanvas.scale(bw / (float) w, bh / (float) h);
        drawFrame(backCanvas, w, h, sec, false);
        backCanvas.restore();
        Bitmap t = front; front = back; back = t;
        if (back == null) { back = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); }
        backCanvas = new Canvas(back);
        frontSec = sec;
    }

    private void size(int w, int h) {
        sizedW = w; sizedH = h;
        if (stars != null) {
            starPx = new float[stars.length][];
            for (int g = 0; g < stars.length; g++) {
                starPx[g] = new float[stars[g].length];
                for (int i = 0; i < stars[g].length; i += 2) { starPx[g][i] = stars[g][i] * w; starPx[g][i + 1] = stars[g][i + 1] * h; }
            }
        }
        starPaint.setStrokeWidth(Math.max(2f, h / 540f * 1.6f));
        // scrim: darken (in light, lift) the top where the clock sits and the bottom under the dock
        int edge = light ? 0x73F4F6F9 : 0x66000000, clear = edge & 0x00FFFFFF;
        scrimPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{edge, clear, clear, light ? 0x4DF4F6F9 : 0x59000000},
                new float[]{0f, 0.42f, 0.62f, 1f}, Shader.TileMode.CLAMP));
    }

    private void drawFrame(Canvas canvas, int w, int h, float sec, boolean withStars) {
        float big = Math.max(w, h);
        boolean deep = style == DEEP;                      // Deep Aurora keeps its slow, steady drift
        for (Glow g : glows) {
            float x = w * (g.cx + g.ax * sin(sec / g.px + g.phase));
            float y = h * (g.cy + g.ay * sin(sec / g.py + g.phase * (deep ? 1f : 1.3f)));
            float breathe = deep ? 0f : sin(sec / (g.px * 0.6f) + g.phase * 2f);   // ~5-12 s pulse
            float r = big * g.r * (1f + (deep ? 0.06f * sin(sec / (g.px * 0.7f) + g.phase) : 0.16f * breathe));
            float rx = r * g.sx, ry = r * g.sy;
            int shade = g.shift <= 0 ? 0 : Math.round((SHADES - 1) * (0.5f + 0.5f * sin(sec / g.shift + g.phase)));
            Shader sh = g.shaders[shade];
            m.setScale(rx / R0, ry / R0);
            m.postTranslate(x, y);
            sh.setLocalMatrix(m);
            paint.setShader(sh);
            paint.setAlpha(deep ? 255 : Math.round(255 * (0.80f + 0.20f * breathe)));
            canvas.drawOval(x - rx, y - ry, x + rx, y + ry, paint);
        }
        if (scrim && style != DEEP) canvas.drawRect(0, 0, w, h, scrimPaint);
        if (withStars && starPx != null) drawStars(canvas, sec);
    }

    /** sin of a full turn: sin(t) with t in cycles, so a period p is just sec / p. */
    private static float sin(float cycles) { return (float) Math.sin(2 * Math.PI * cycles); }

    /** Nebula's stars, at full size so they stay points of light, twinkling in three groups. */
    private void drawStars(Canvas canvas, float sec) {
        for (int gi = 0; gi < starPx.length; gi++) {
            float a = 0.34f + 0.26f * sin(sec / (3f + gi * 1.7f) + gi * 0.33f);   // a 3-6 s twinkle
            starPaint.setAlpha((int) (255 * a));
            canvas.drawPoints(starPx[gi], starPaint);
        }
    }

    // ---- the five backdrops ----------------------------------------------------------------------

    private static Glow[] glows(int style, boolean light) {
        Glow[] g;
        float k = light ? 1f : 1.45f;   // dark: brighter so the colour reads on the deep base; the scrim keeps text readable
        // Glow(colour, shifts to, alpha, cx, cy, drift x, drift y, period x s, period y s, colour shift s, phase, radius[, sx, sy])
        switch (style) {
            case COLOR: g = new Glow[]{
                    new Glow(0x8B5CF6, 0xE0409F, a(0x78, k), 0.24f, 0.30f, 0.20f, 0.18f, 14, 11, 17, 0.00f, 0.46f),  // violet → magenta
                    new Glow(0xE0409F, 0x3B82F6, a(0x66, k), 0.76f, 0.30f, 0.18f, 0.20f, 12, 16, 13, 0.27f, 0.42f),  // magenta → blue
                    new Glow(0x3B82F6, 0x2BB3A3, a(0x70, k), 0.62f, 0.78f, 0.24f, 0.14f, 17, 10, 19, 0.53f, 0.44f),  // blue → teal
                    new Glow(0x2BB3A3, 0x8B5CF6, a(0x66, k), 0.20f, 0.80f, 0.16f, 0.16f, 10, 13, 15, 0.71f, 0.40f),  // teal → violet
                    new Glow(0xE0A526, 0xE0409F, a(0x55, k), 0.50f, 0.52f, 0.28f, 0.22f, 19, 15, 12, 0.86f, 0.32f),  // gold → magenta
            }; break;
            case WAVES: g = new Glow[]{                    // wide, flat glows: bands rolling side to side, swaying
                    new Glow(0x2BB3A3, 0x3B6FE0, a(0x66, k), 0.50f, 0.18f, 0.42f, 0.06f, 12, 8, 16, 0.00f, 0.40f, 2.0f, 0.34f),
                    new Glow(0x3B6FE0, 0x7C4DDB, a(0x66, k), 0.50f, 0.42f, 0.42f, 0.07f, 15, 9, 13, 0.35f, 0.40f, 2.0f, 0.32f),
                    new Glow(0x7C4DDB, 0xC2407F, a(0x5C, k), 0.50f, 0.66f, 0.42f, 0.07f, 13, 11, 18, 0.62f, 0.40f, 2.0f, 0.32f),
                    new Glow(0xC2407F, 0x2BB3A3, a(0x50, k), 0.50f, 0.90f, 0.42f, 0.06f, 17, 10, 14, 0.18f, 0.40f, 2.0f, 0.30f),
            }; break;
            case NEBULA: g = new Glow[]{
                    new Glow(0x4338CA, 0x6D28D9, a(0x70, k), 0.38f, 0.42f, 0.14f, 0.12f, 18, 15, 20, 0.00f, 0.50f),  // indigo core
                    new Glow(0xA21CAF, 0xDB2777, a(0x58, k), 0.68f, 0.30f, 0.16f, 0.14f, 14, 19, 16, 0.31f, 0.38f),  // magenta dust
                    new Glow(0x0E7490, 0x4338CA, a(0x55, k), 0.70f, 0.76f, 0.16f, 0.12f, 20, 13, 18, 0.58f, 0.42f),  // teal gas
                    new Glow(0xDB2777, 0xA21CAF, a(0x38, k), 0.24f, 0.74f, 0.12f, 0.14f, 16, 17, 14, 0.80f, 0.30f),  // rose
            }; break;
            case SUNSET: g = new Glow[]{
                    new Glow(0xFF7A3D, 0xFF4F8B, a(0x78, k), 0.30f, 0.80f, 0.24f, 0.08f, 16, 12, 18, 0.00f, 0.48f),  // orange → pink
                    new Glow(0xFF4F8B, 0x8E44AD, a(0x66, k), 0.72f, 0.60f, 0.20f, 0.14f, 13, 17, 14, 0.29f, 0.44f),  // pink → purple
                    new Glow(0x8E44AD, 0xC2387F, a(0x6A, k), 0.55f, 0.18f, 0.28f, 0.10f, 19, 14, 16, 0.55f, 0.48f),  // purple sky
                    new Glow(0xFFB547, 0xFF7A3D, a(0x55, k), 0.80f, 0.88f, 0.16f, 0.08f, 11, 15, 12, 0.77f, 0.32f),  // amber → orange
            }; break;
            default: g = new Glow[]{                       // Deep Aurora as it always was: teal up-left, gold down-right, opposite
                    new Glow(0x2BB3A3, 0, 0x40, 0.22f, 0.13f, 0.08f, 0.07f, 84, 84, 0, -0.25f, 0.60f),
                    new Glow(0xE0A526, 0, 0x33, 0.81f, 0.87f, 0.09f, 0.07f, 84, 84, 0, 0.25f, 0.55f),
            };
        }
        for (Glow x : g) {
            int n = x.shift > 0 ? SHADES : 1;
            x.shaders = new Shader[n];
            for (int i = 0; i < n; i++) {
                int c = n == 1 ? x.color & 0x00FFFFFF : mix(x.color, x.color2, i / (float) (n - 1));
                // three stops so the edge falls away softly, like a blur, not a hard cone
                x.shaders[i] = new RadialGradient(0, 0, R0,
                        new int[]{(x.alpha << 24) | c, ((x.alpha * 2 / 5) << 24) | c, c},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            }
        }
        return g;
    }

    private static int mix(int c1, int c2, float f) {
        int r = Math.round(((c1 >> 16) & 0xFF) * (1 - f) + ((c2 >> 16) & 0xFF) * f);
        int g = Math.round(((c1 >> 8) & 0xFF) * (1 - f) + ((c2 >> 8) & 0xFF) * f);
        int b = Math.round((c1 & 0xFF) * (1 - f) + (c2 & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static int a(int alpha, float k) { return Math.min(255, Math.round(alpha * k)); }

    private static float[][] makeStars() {
        Random r = new Random(7);                         // the same sky every time
        float[][] s = new float[3][];
        for (int g = 0; g < 3; g++) {
            s[g] = new float[2 * 26];
            for (int i = 0; i < s[g].length; i += 2) { s[g][i] = r.nextFloat(); s[g][i + 1] = r.nextFloat(); }
        }
        return s;
    }

    /** A still frame of a backdrop over its base, for the Settings thumbnails. */
    static Bitmap thumb(Context c, int style, int baseRes, boolean light, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Drawable base = c.getDrawable(baseRes);
        if (base != null) { base.setBounds(0, 0, w, h); base.draw(cv); }
        AuroraView v = new AuroraView(c);
        v.setStyle(style, light);
        v.scrim = false;
        v.size(w, h);
        v.drawFrame(cv, w, h, START_MS / 1000f, true);
        return b;
    }
}
