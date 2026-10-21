package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Someone's at the door": while Home is showing, ask the Blink bridge every 20 s; if the doorbell
 * rang in the last 5 minutes, slide a small card in at the top with the event's snapshot. It never
 * takes focus (the remote keeps doing what it was doing) and each ring shows once.
 */
final class DoorbellBanner {

    private static final long POLL_MS = 20_000L, RECENT_MS = 5 * 60_000L, SHOW_MS = 45_000L;
    private static String shownId;

    private final Activity a;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui.T t;
    private View card;
    private boolean running;

    DoorbellBanner(Activity a) { this.a = a; }

    void start(Ui.T t) {
        this.t = t;
        running = true;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
        hide();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            final android.content.Context app = a.getApplicationContext();
            new Thread(() -> {
                Live.Cams c = Live.fetchCamsBlocking(app);
                Live.CamEvent ring = c.recentRing(RECENT_MS);
                Bitmap shot = ring != null && !ring.id.equals(shownId)
                        ? Live.camImage(app, "/event/" + ring.id + ".jpg", 2) : null;
                main.post(() -> {
                    if (running && ring != null && !ring.id.equals(shownId)) show(ring, shot);
                });
            }, "mills-doorbell").start();
            main.postDelayed(this, POLL_MS);
        }
    };

    private void show(Live.CamEvent ring, Bitmap shot) {
        shownId = ring.id;
        hide();
        LinearLayout p = Ui.hbox(a);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Ui.pill(a, t.light ? 0xF2FFFFFF : 0xF2161B22));
        p.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8));
        p.setElevation(Ui.dp(a, 8));
        p.setFocusable(false);

        FrameLayout img = new FrameLayout(a);
        img.setBackgroundColor(0xFF0B0F14);
        img.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        img.setClipToOutline(true);
        if (shot != null) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(shot);
            img.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.addView(Ui.dot(a, t.gold, 12), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        int s = Ui.dp(a, 56);
        p.addView(img, new LinearLayout.LayoutParams(s, s));

        LinearLayout words = Ui.vbox(a);
        LinearLayout.LayoutParams wp = Ui.lp(-2, -2);
        wp.leftMargin = Ui.dp(a, 14);
        p.addView(words, wp);
        TextView h = Ui.text(a, "DOORBELL", 7.5f, t.goldText, 700);
        h.setLetterSpacing(0.18f);
        words.addView(h);
        String cam = ring.camera == null ? "" : ring.camera.trim();   // Blink names can carry a trailing space
        words.addView(Ui.text(a, "Someone rang " + (cam.isEmpty() ? "the doorbell" : "at " + cam), 15, t.text, 600), Ui.lpTop(a, -2, -2, 2));
        // Theater has no command center, so only the calm home points there
        words.addView(Ui.text(a, Live.ago(ring.at) + (a instanceof MainActivity ? " · Cameras is in the command center (DOWN)" : ""),
                9.5f, t.dim, 300), Ui.lpTop(a, -2, -2, 2));

        ViewGroup root = a.findViewById(android.R.id.content);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Ui.dp(a, 28);
        root.addView(p, lp);
        card = p;
        p.setAlpha(0f);
        p.setTranslationY(-Ui.dp(a, 24));
        p.animate().alpha(1f).translationY(0).setDuration(Motion.ambient(a) ? 320 : 0).start();
        main.postDelayed(this::hide, SHOW_MS);
    }

    private void hide() {
        if (card == null) return;
        final View c = card;
        card = null;
        c.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (c.getParent() instanceof ViewGroup) ((ViewGroup) c.getParent()).removeView(c);
        }).start();
    }
}
