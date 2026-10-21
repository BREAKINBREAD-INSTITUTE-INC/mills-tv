package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Random;

/**
 * The MILLS screensaver scene, built from Settings > Screensaver. Used by the real screensaver
 * (ClockDream) and by the Settings preview, so the preview is the real thing.
 *   Style: Clock · Clock + status (weather, AI team, Nest) · Wallpaper (dimmed, clock over it) · Minimal
 *   Clock size, date, wordmark, glow (gold+teal drift / gold / off), brightness, dim after 20 min,
 *   and how often everything drifts to a new spot so nothing burns in.
 * Everything is always dark: a screensaver should never light the room.
 */
final class DreamScene {

    private static final int[] GLOWS = {0xFFE0A526, 0xFF2BB3A3};
    private static final long DIM_AFTER_MS = 20 * 60_000L;

    private final Context c;
    private final FrameLayout root;
    private final boolean preview;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private LinearLayout content;
    private GradientDrawable glow;
    private TextView status;
    private int glowIndex;
    private int glowColor;
    private long startedAt;
    private float baseAlpha = 1f;

    DreamScene(Context c, FrameLayout root, boolean preview) {
        this.c = c;
        this.root = root;
        this.preview = preview;
    }

    private long moveMs() { return Prefs.ssMove(c) == 1 ? 5 * 60_000L : 60_000L; }

    void build() {
        root.removeAllViews();
        Ui.T t = new Ui.T(false);              // always the dark tokens
        int style = Prefs.ssStyle(c);
        root.setBackgroundColor(0xFF000000);

        if (style == Prefs.SS_WALLPAPER) {
            View wp = new View(c);
            wp.setBackgroundResource(Wallpapers.drawableFor(Prefs.wallpaper(c), false));
            root.addView(wp, new FrameLayout.LayoutParams(-1, -1));
            int motion = Wallpapers.motion(Prefs.wallpaper(c));
            AuroraView a = null;
            if (motion != AuroraView.NONE) {   // the moving backdrop under the same dim
                a = new AuroraView(c);
                a.setStyle(motion, false);
                a.setScrim(false);
                root.addView(a, new FrameLayout.LayoutParams(-1, -1));
            }
            if (Wallpapers.isVideo(Prefs.wallpaper(c))) {   // a video background plays here too (a still in the preview)
                final AuroraView nebula = a;
                VideoBackdrop v = new VideoBackdrop(c);
                v.setScrim(false);
                v.setStill(preview);
                v.setOnFail(() -> { if (nebula != null) nebula.setVisibility(View.VISIBLE); });
                root.addView(v, new FrameLayout.LayoutParams(-1, -1));
                v.setClip(VideoBackgrounds.fileFor(c, Prefs.wallpaper(c)));
                if (a != null && !v.failed()) a.setVisibility(View.GONE);
            }
            View dim = new View(c);
            // a moving colour backdrop dims a little less, or its colour is lost; still never bright
            dim.setBackgroundColor(Wallpapers.motion(Prefs.wallpaper(c)) != AuroraView.NONE ? 0x8C000000 : 0xB3000000);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        }

        int glowMode = Prefs.ssGlow(c);
        if (glowMode != 2 && style != Prefs.SS_MINIMAL) {
            View ambient = new View(c);
            glow = new GradientDrawable();
            glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            ambient.setBackground(glow);
            ambient.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> glow.setGradientRadius((r - l) * 0.55f));
            root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
            glowIndex = glowMode == 1 ? 0 : random.nextInt(GLOWS.length);
            setGlow(GLOWS[glowIndex]);
        } else {
            glow = null;
        }

        content = Ui.vbox(c);
        float[] sizes = {72, 96, 120};
        float clockSp = style == Prefs.SS_MINIMAL ? sizes[Prefs.ssSize(c)] * 0.8f : sizes[Prefs.ssSize(c)];
        boolean h24 = Prefs.clock24h(c);

        LinearLayout row = Ui.hbox(c);
        row.setBaselineAligned(true);
        TextClock clock = new TextClock(c);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        if (h24) clock.setFormat12Hour("H:mm");
        clock.setTextColor(0xE6FFFFFF);
        clock.setTextSize(clockSp);
        clock.setTypeface(Ui.outfit(c));
        clock.setFontVariationSettings("'wght' 200");
        clock.setLetterSpacing(-0.04f);
        clock.setIncludeFontPadding(false);
        row.addView(clock);
        if (!h24 && style != Prefs.SS_MINIMAL) {
            TextClock ampm = new TextClock(c);
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("");
            ampm.setTextColor(0x99FFFFFF);
            ampm.setTextSize(clockSp * 0.18f);
            ampm.setTypeface(Ui.outfit(c));
            ampm.setFontVariationSettings("'wght' 500");
            ampm.setAllCaps(true);
            LinearLayout.LayoutParams ap = Ui.lp(-2, -2);
            ap.leftMargin = Ui.dp(c, 10);
            row.addView(ampm, ap);
        }
        content.addView(row, Ui.lp(-2, -2));

        if (Prefs.ssDate(c) && style != Prefs.SS_MINIMAL) {
            TextClock date = new TextClock(c);
            date.setFormat12Hour("EEEE  d MMMM");
            date.setFormat24Hour("EEEE  d MMMM");
            date.setTextColor(0x8CFFFFFF);
            date.setTextSize(Math.max(14, clockSp * 0.15f));
            date.setTypeface(Ui.outfit(c));
            date.setFontVariationSettings("'wght' 400");
            date.setLetterSpacing(0.14f);
            date.setAllCaps(true);
            content.addView(date, Ui.lpTop(c, -2, -2, 4));
        }

        if (style == Prefs.SS_STATUS) {
            status = Ui.text(c, statusLine(), Math.max(12, clockSp * 0.12f), 0x99FFFFFF, 300);
            content.addView(status, Ui.lpTop(c, -2, -2, 14));
            refreshStatus();
        } else {
            status = null;
        }

        if (Prefs.ssMark(c) && style != Prefs.SS_MINIMAL) {
            content.addView(Ui.mark(c, t), Ui.lpTop(c, -2, -2, 22));
        }

        root.addView(content, new FrameLayout.LayoutParams(-2, -2));
        baseAlpha = new float[]{1f, 0.7f, 0.4f}[Prefs.ssBright(c)];
    }

    private String statusLine() {
        StringBuilder sb = new StringBuilder();
        Live.Weather w = Live.weather();
        if (w.has()) sb.append(w.tempF).append("° outside");
        Live.House h = Live.house();
        if (h.fresh() && h.indoorF != null) { if (sb.length() > 0) sb.append("   ·   "); sb.append(h.indoorF).append("° inside"); }
        Live.Mission m = Live.mission();
        if (m.ok && !m.stale()) { if (sb.length() > 0) sb.append("   ·   "); sb.append("AI team · ").append(m.summary(false)); }
        return sb.length() == 0 ? "" : sb.toString();
    }

    private void refreshStatus() {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            Live.fetchWeatherBlocking(app);
            Live.fetchHouseBlocking(app);
            handler.post(() -> Live.fetchMission(app, m -> { if (status != null) status.setText(statusLine()); }));
        }, "mills-dream").start();
    }

    void start() {
        startedAt = System.currentTimeMillis();
        content.setAlpha(0f);
        content.post(() -> {
            place();
            content.animate().alpha(baseAlpha).setDuration(preview ? 400 : 1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, preview ? 4000 : moveMs());
    }

    void stop() { handler.removeCallbacks(tick); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            float target = baseAlpha;
            if (Prefs.ssDimLater(c) && System.currentTimeMillis() - startedAt > DIM_AFTER_MS) target = Math.min(baseAlpha, 0.4f);
            final float to = target;
            content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                place();
                content.animate().alpha(to).setDuration(1200).start();
            }).start();
            if (glow != null && Prefs.ssGlow(c) == 0) shiftGlow();
            if (status != null) refreshStatus();
            handler.postDelayed(this, preview ? 4000 : moveMs());
        }
    };

    /** A random spot that keeps everything on screen, with the glow centred behind it. */
    private void place() {
        int maxX = Math.max(0, root.getWidth() - content.getWidth());
        int maxY = Math.max(0, root.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (glow != null && root.getWidth() > 0 && root.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / root.getWidth(),
                    (y + content.getHeight() / 2f) / root.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        glowIndex = (glowIndex + 1) % GLOWS.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, GLOWS[glowIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int col = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | col, 0x14000000 | col, col});
    }
}
