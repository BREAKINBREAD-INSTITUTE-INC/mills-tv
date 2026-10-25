package com.gathin.tvhome;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/**
 * The clip player's picture: a SurfaceView and a MediaPlayer, with the few calls VideoView had.
 * VideoView tears its player down on the main thread (reset + release, ~0.7 s on the Bravia's
 * decoder), so every ▲ ▼ to another clip and every BACK froze the screen for 40+ frames. Here the
 * old player is let go on a background thread (VideoBackdrop.releaseLater) and the next one starts
 * at once. The picture keeps the clip's shape, centred on black.
 */
final class ClipVideo extends FrameLayout implements SurfaceHolder.Callback {

    private final SurfaceView sv;
    private MediaPlayer mp;
    private boolean prepared, hasSurface;
    private Uri want;
    private int videoW, videoH;
    private MediaPlayer.OnPreparedListener onPrepared;
    private MediaPlayer.OnCompletionListener onCompletion;
    private MediaPlayer.OnErrorListener onError;

    ClipVideo(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        sv = new SurfaceView(c);
        sv.getHolder().addCallback(this);
        addView(sv, new LayoutParams(-1, -1, Gravity.CENTER));
    }

    void setOnPreparedListener(MediaPlayer.OnPreparedListener l) { onPrepared = l; }
    void setOnCompletionListener(MediaPlayer.OnCompletionListener l) { onCompletion = l; }
    void setOnErrorListener(MediaPlayer.OnErrorListener l) { onError = l; }

    /** Play this clip next: the current one is let go without waiting for it. */
    void setVideoURI(Uri u) {
        stopPlayback();
        want = u;
        if (hasSurface) open();
    }

    private int asked;

    /** Once the players before it have let go of the surface (VideoBackdrop.afterReleases). */
    private void open() {
        final int my = ++asked;
        VideoBackdrop.afterReleases(() -> { if (my == asked && hasSurface) openNow(); });
    }

    private void openNow() {
        if (want == null || mp != null) return;
        final MediaPlayer p = new MediaPlayer();
        mp = p;
        prepared = false;
        try {
            p.setDisplay(sv.getHolder());
            p.setScreenOnWhilePlaying(true);
            p.setOnPreparedListener(m -> {
                if (m != mp) return;
                prepared = true;
                if (onPrepared != null) onPrepared.onPrepared(m);
            });
            p.setOnCompletionListener(m -> { if (m == mp && onCompletion != null) onCompletion.onCompletion(m); });
            p.setOnErrorListener((m, what, extra) -> m == mp && onError != null && onError.onError(m, what, extra));
            p.setOnVideoSizeChangedListener((m, w, h) -> {
                if (m == mp && w > 0 && h > 0 && (w != videoW || h != videoH)) { videoW = w; videoH = h; requestLayout(); }
            });
            p.setDataSource(getContext(), want);
            p.prepareAsync();
        } catch (Exception e) {
            if (onError != null) onError.onError(p, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }

    /** Let the player go, off the main thread. */
    void stopPlayback() {
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        want = null;
        if (p == null) return;
        p.setOnPreparedListener(null);
        p.setOnCompletionListener(null);
        p.setOnErrorListener(null);
        p.setOnVideoSizeChangedListener(null);
        VideoBackdrop.releaseLater(p);
    }

    void start() { if (prepared) try { mp.start(); } catch (IllegalStateException ignored) {} }
    void pause() { if (prepared) try { mp.pause(); } catch (IllegalStateException ignored) {} }
    void seekTo(int ms) { if (prepared) try { mp.seekTo(ms); } catch (IllegalStateException ignored) {} }
    boolean isPlaying() { try { return prepared && mp.isPlaying(); } catch (IllegalStateException e) { return false; } }
    int getDuration() { try { return prepared ? mp.getDuration() : -1; } catch (IllegalStateException e) { return -1; } }
    int getCurrentPosition() { try { return prepared ? mp.getCurrentPosition() : 0; } catch (IllegalStateException e) { return 0; } }

    /** Fit the clip inside the screen, keeping its shape. */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int w = r - l, h = b - t;
        if (w == 0 || h == 0 || videoW == 0 || videoH == 0) return;
        float s = Math.min(w / (float) videoW, h / (float) videoH);
        int cw = Math.round(videoW * s), ch = Math.round(videoH * s);
        int x = (w - cw) / 2, y = (h - ch) / 2;
        sv.layout(x, y, x + cw, y + ch);
    }

    @Override public void surfaceCreated(SurfaceHolder h) { hasSurface = true; open(); }
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {
        hasSurface = false;
        Uri u = want;
        stopPlayback();
        want = u;   // a surface back later (screen on again) opens the same clip
    }
}
