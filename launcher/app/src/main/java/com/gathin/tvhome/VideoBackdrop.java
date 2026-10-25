package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;

/**
 * A video background (Settings > Background > Video): one clip, muted and looping, cropped to
 * fill the screen behind Calm, Theater and the Wallpaper screensaver.
 *
 * A SurfaceView fed by a MediaPlayer: the TV's hardware decoder writes frames straight to their
 * own layer under the window, so a new video frame never redraws the launcher (menus over it
 * cost what they cost over a still wallpaper). A still frame sits on top until the first video
 * frame lands, and the same soft top/bottom scrim as the moving backdrops keeps the clock and
 * dock name readable. The player only exists while the screen is showing: it is released on
 * pause, when the window is hidden, and while anything else plays (MediaWatcher / AudioManager),
 * so it never holds a decoder another app needs, and it picks up where it was when it comes
 * back. Motion Reduced / Off, and the Settings preview, show the still frame and never make a
 * surface or a player. A missing clip or a playback error calls onFail once (the host shows the
 * moving Nebula instead) and logs one line; never an error screen.
 */
public class VideoBackdrop extends FrameLayout implements SurfaceHolder.Callback {

    interface OnFail { void failed(); }

    private static final long CHECK_MS = 1000;            // while active, look for other playback once a second
    /**
     * Tearing a player down (reset + release) takes ~0.7 s on the Bravia's hardware decoder. On the main
     * thread that froze every move from Home to another screen ("Activity pause timeout", 44 skipped
     * frames), so it happens here instead, one player at a time, in order.
     */
    private static final java.util.concurrent.ExecutorService RELEASER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "mills-video-release"));

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** reset + release a player off the main thread (its listeners should be cleared first). */
    static void releaseLater(MediaPlayer p) {
        RELEASER.execute(() -> {
            try { p.reset(); } catch (Exception ignored) {}
            try { p.release(); } catch (Exception ignored) {}
        });
    }

    /**
     * Run r on the main thread once every player handed to releaseLater so far has let go: a new
     * player can't take a surface the old one is still connected to ("already connected").
     */
    static void afterReleases(Runnable r) { RELEASER.execute(() -> MAIN.post(r)); }

    private boolean opening;

    /** Open a player once earlier ones are gone, if it is still wanted then. */
    private void openSoon() {
        if (opening) return;
        opening = true;
        afterReleases(() -> {
            opening = false;
            if (mp == null && hasSurface && wanted() && !otherMediaPlaying()) open();
        });
    }

    private final SurfaceView sv;
    private final ImageView still;
    private final View scrim;
    private MediaPlayer mp;
    private boolean hasSurface;
    private File file;
    private boolean paused, stillOnly, failed;
    private int videoW, videoH, resumeAt;
    private OnFail onFail;

    public VideoBackdrop(Context c) { this(c, null); }

    public VideoBackdrop(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFF000000);
        sv = new SurfaceView(c);
        sv.getHolder().addCallback(this);
        sv.setVisibility(GONE);                            // no surface until there is something to play
        addView(sv, new LayoutParams(-1, -1));
        still = new ImageView(c);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(still, new LayoutParams(-1, -1));
        // the moving backdrops' scrim, deeper: real footage is brighter and busier than a gradient.
        // Darkest under the clock and status (top) and the dock (bottom), lightest across the middle.
        scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x73000000, 0x2E000000, 0x4D000000, 0x99000000}));
        addView(scrim, new LayoutParams(-1, -1));
    }

    void setOnFail(OnFail f) { onFail = f; }

    /** The legibility scrim; off where the host already has one (Theater, the dimmed screensaver). */
    void setScrim(boolean on) { scrim.setVisibility(on ? VISIBLE : GONE); }

    /** Theater: its own scrim darkens the bottom; this adds only the top fade under the clock. */
    void setTopScrimOnly() {
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA6000000, 0x40000000, 0x00000000, 0x00000000, 0x00000000}));
        scrim.setVisibility(VISIBLE);
    }

    /** The clip to show. Cheap when it is the one already showing. */
    void setClip(File f) {
        if (f != null && f.equals(file) && !failed) { update(); return; }
        release();
        file = f;
        failed = false;
        resumeAt = 0;
        videoW = videoH = 0;
        still.setImageDrawable(null);
        still.setVisibility(VISIBLE);
        if (f == null || !f.isFile()) { fail("missing " + (f == null ? "clip" : f.getName())); return; }
        final File want = f;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.min(1920, Math.max(dm.widthPixels, dm.heightPixels));
        VideoBackgrounds.frame(f, w, w * 9 / 16, b -> { if (want.equals(file) && b != null) still.setImageBitmap(b); });
        update();
    }

    /** No clip (the background is not a video): let go of everything. */
    void clear() {
        removeCallbacks(check);
        release();
        file = null;
        failed = false;
        still.setImageDrawable(null);
        sv.setVisibility(GONE);
    }

    /** Draw the still frame only and never play (the Settings preview, Motion Reduced / Off). */
    void setStill(boolean s) { stillOnly = s; update(); }

    /** Hosts call this from onPause / onResume. */
    void setPaused(boolean p) { paused = p; update(); }

    boolean failed() { return failed; }

    /** Could play now if nothing else were playing: a clip, motion on, and this screen in front. */
    private boolean wanted() {
        return file != null && !failed && !stillOnly && !paused && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE && getVisibility() == VISIBLE && Motion.ambient(getContext());
    }

    private boolean otherMediaPlaying() {
        if (MediaWatcher.playing()) return true;
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();   // our clips are silent, so this is someone else
        } catch (Exception e) { return false; }
    }

    private void update() {
        removeCallbacks(check);
        boolean want = wanted();
        if (want && !otherMediaPlaying()) {
            if (sv.getVisibility() != VISIBLE) sv.setVisibility(VISIBLE);   // surfaceCreated opens the player
            else if (mp == null && hasSurface) openSoon();
        } else {
            release();
            if (!want) sv.setVisibility(GONE);             // hidden or still: give the layer back too
        }
        if (want) postDelayed(check, CHECK_MS);
    }

    private final Runnable check = this::update;

    private void open() {
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setDisplay(sv.getHolder());
            p.setDataSource(file.getAbsolutePath());
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setScreenOnWhilePlaying(false);
            p.setOnVideoSizeChangedListener((m, w, h) -> { if (w > 0 && h > 0 && (w != videoW || h != videoH)) { videoW = w; videoH = h; requestLayout(); } });
            p.setOnInfoListener((m, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && m == mp) still.setVisibility(GONE);
                return false;
            });
            p.setOnErrorListener((m, what, extra) -> { fail(file.getName() + " error " + what + "/" + extra); return true; });
            p.setOnPreparedListener(m -> {
                if (m != mp) return;
                if (resumeAt > 0) m.seekTo(resumeAt);
                m.start();
            });
            p.prepareAsync();
        } catch (Exception e) {
            fail(file.getName() + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void release() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try { if (p.isPlaying()) resumeAt = p.getCurrentPosition(); } catch (Exception ignored) {}
        p.setOnInfoListener(null);
        p.setOnErrorListener(null);
        p.setOnPreparedListener(null);
        p.setOnVideoSizeChangedListener(null);
        releaseLater(p);
        if (file != null && !failed) still.setVisibility(VISIBLE);   // the layer may go blank; the still covers it
    }

    private void fail(String why) {
        if (failed) return;
        failed = true;
        release();
        removeCallbacks(check);
        Log.w(VideoBackgrounds.TAG, why + " · showing Nebula");
        sv.setVisibility(GONE);
        setVisibility(GONE);
        if (onFail != null) onFail.failed();
    }

    /** Centre-crop: lay the surface out larger than the screen, centred, so the video covers it. */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int w = r - l, h = b - t;
        if (w == 0 || h == 0 || videoW == 0 || videoH == 0) return;
        float s = Math.max(w / (float) videoW, h / (float) videoH);
        int cw = Math.round(videoW * s), ch = Math.round(videoH * s);
        int x = (w - cw) / 2, y = (h - ch) / 2;
        sv.layout(x, y, x + cw, y + ch);
    }

    @Override public void surfaceCreated(SurfaceHolder h) { hasSurface = true; if (mp == null && wanted() && !otherMediaPlaying()) openSoon(); }
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { hasSurface = false; release(); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); update(); }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(check);
        release();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (isAttachedToWindow()) update();
    }
}
