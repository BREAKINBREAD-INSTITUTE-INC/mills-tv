package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One archived clip, full screen, streamed from the Blink bridge (which reads it off the NAS and
 * answers Range requests, so seeking works). OK pauses, ◀ ▶ seek (2 s in a clip of 30 s or less,
 * as Blink's usually are; 10 s in a longer one), ▲ ▼ the newer and older clip, BACK returns to the
 * list on the clip last shown. Controls fade while playing.
 */
public class ClipPlayerActivity extends Activity {

    static final String URLS = "urls", TIMES = "times", WHATS = "whats", INDEX = "index", CAMERA = "camera", ARCHIVED = "archived";

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(ThemeManager.wrap(base)); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Ui.T t = new Ui.T(false);
    private ClipVideo video;
    private View chrome;
    private TextView head, sub, clock, state;
    private Bar bar;
    private String[] urls, whats;
    private long[] times;
    private boolean[] archived;
    private String camera;
    private int index;
    private boolean ended;
    private LinearLayout footBox;
    private View foot;
    private int footStep;

    /** The hints, with ◀ ▶'s step as it is for this clip. */
    private void setFoot(int step) {
        if (step == footStep) return;
        footStep = step;
        if (foot != null) footBox.removeView(foot);
        foot = Ui.foot(this, t, "OK", "pause", "◀ ▶", step / 1000 + " s", "▲ ▼", "newer / older clip", "BACK", "clips");
        LinearLayout.LayoutParams fp = Ui.lpTop(this, -2, -2, 12);
        fp.gravity = Gravity.END;
        footBox.addView(foot, fp);
    }

    /** Blink clips run a few seconds: a 10 s jump would skip the whole clip. */
    private int step() {
        int d = video.getDuration();
        return d > 0 && d <= 30_000 ? 2_000 : 10_000;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        urls = in.getStringArrayExtra(URLS);
        times = in.getLongArrayExtra(TIMES);
        whats = in.getStringArrayExtra(WHATS);
        camera = in.getStringExtra(CAMERA);
        archived = in.getBooleanArrayExtra(ARCHIVED);
        index = in.getIntExtra(INDEX, 0);
        if (urls == null || urls.length == 0) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new ClipVideo(this);
        video.setKeepScreenOn(true);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        FrameLayout ch = new FrameLayout(this);
        chrome = ch;
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE6000000, 0x99000000, 0x00000000}));
        ch.addView(top, new FrameLayout.LayoutParams(-1, Ui.dp(this, 160), Gravity.TOP));
        View bot = new View(this);
        bot.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        ch.addView(bot, new FrameLayout.LayoutParams(-1, Ui.dp(this, 150), Gravity.BOTTOM));

        LinearLayout words = Ui.vbox(this);
        TextView eb = Ui.text(this, "CLIP", 7.5f, t.goldText, 700);
        eb.setLetterSpacing(0.18f);
        words.addView(eb);
        head = Ui.single(Ui.text(this, "", 20, t.text, 500));
        words.addView(head, Ui.lpTop(this, -2, -2, 3));
        sub = Ui.text(this, "", 10, 0xE6F2F4F7, 400);
        sub.setShadowLayer(Ui.dp(this, 3), 0, 0, 0xCC000000);
        head.setShadowLayer(Ui.dp(this, 4), 0, 0, 0xCC000000);
        words.addView(sub, Ui.lpTop(this, -2, -2, 3));
        ch.addView(words, Ui.at(this, Gravity.TOP | Gravity.START, 64, 36, 64, 0));

        LinearLayout low = Ui.vbox(this);
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        state = Ui.text(this, "", 10, t.text, 600);
        row.addView(state, new LinearLayout.LayoutParams(0, -2, 1f));
        clock = Ui.text(this, "", 10, t.dim, 400);
        row.addView(clock);
        low.addView(row);
        bar = new Bar(this, t);
        low.addView(bar, Ui.lpTop(this, -1, Ui.dp(this, 4), 8));
        footBox = low;
        setFoot(10_000);
        ch.addView(low, Ui.at(this, Gravity.BOTTOM | Gravity.START, 64, 0, 64, 30));
        ((FrameLayout.LayoutParams) low.getLayoutParams()).width = -1;
        root.addView(ch, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            ended = false;
            setFoot(step());
            video.start();
            showChrome();
        });
        video.setOnCompletionListener(mp -> { ended = true; showChrome(); });
        video.setOnErrorListener((mp, what, extra) -> {
            state.setText("Couldn't play this clip · ▲ ▼ for another");
            ended = true;
            showChrome();
            return true;
        });
        play(index);
    }

    private void play(int i) {
        index = Math.max(0, Math.min(urls.length - 1, i));
        ended = false;
        String when = times != null && index < times.length && times[index] > 0
                ? new SimpleDateFormat("EEE MMM d · h:mm:ss a", Locale.US).format(new Date(times[index])) : "";
        head.setText((camera == null ? "Camera" : camera.trim()) + (whats != null && index < whats.length ? " · " + whats[index] : ""));
        sub.setText(when + (when.isEmpty() ? "" : " · ") + "clip " + (index + 1) + " of " + urls.length
                + (archived != null && index < archived.length && !archived[index] ? " · from Blink's cloud (not archived yet)" : " · from the NAS archive"));
        state.setText("loading…");
        bar.set(0);
        clock.setText("");
        video.setVideoURI(Uri.parse(urls[index]));
        showChrome();
    }

    // ---- controls -------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_SPACE:
                if (e.getRepeatCount() == 0) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:  if (!video.isPlaying()) toggle(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: if (video.isPlaying()) toggle(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(-step()); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seek(step()); return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_CHANNEL_UP:
                if (e.getRepeatCount() == 0) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (e.getRepeatCount() == 0) step(1);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    /** The list is newest first: ▲ goes to the newer clip, ▼ to the older; the ends don't wrap. */
    private void step(int d) {
        int to = index + d;
        if (to < 0 || to >= urls.length) {
            state.setText(to < 0 ? "This is the newest clip" : "This is the oldest clip loaded");
            showChrome();
            return;
        }
        play(to);   // play() lets the current player go without waiting for it
    }

    private void toggle() {
        if (ended) { ended = false; video.seekTo(0); video.start(); }
        else if (video.isPlaying()) video.pause();
        else video.start();
        showChrome();
    }

    private void seek(int by) {
        int d = video.getDuration();
        if (d <= 0) return;
        int to = Math.max(0, Math.min(d - 250, video.getCurrentPosition() + by));
        ended = false;
        video.seekTo(to);
        if (!video.isPlaying()) video.start();
        showChrome();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK, new Intent().putExtra(INDEX, index));
        finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onPause() { super.onPause(); if (video != null) video.pause(); main.removeCallbacks(tick); }
    @Override protected void onDestroy() { super.onDestroy(); main.removeCallbacksAndMessages(null); if (video != null) video.stopPlayback(); }

    // ---- chrome ---------------------------------------------------------------------------------

    private void showChrome() {
        chrome.animate().cancel();
        chrome.setAlpha(1f);
        main.removeCallbacks(fade);
        main.postDelayed(fade, 3500);
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private final Runnable fade = new Runnable() {
        @Override public void run() {
            if (video.isPlaying()) chrome.animate().alpha(0f).setDuration(Motion.ms(ClipPlayerActivity.this, 400)).start();
            else main.postDelayed(this, 3500);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            int d = video.getDuration(), p = video.getCurrentPosition();
            if (d > 0) {
                bar.set(ended ? 1f : p / (float) d);
                clock.setText(mmss(ended ? d : p) + " / " + mmss(d));
                if (!state.getText().toString().startsWith("Couldn't") && !state.getText().toString().startsWith("This is"))
                    state.setText(ended ? "End of clip · OK plays it again · ▼ older clip" : video.isPlaying() ? "Playing" : "Paused");
            }
            main.postDelayed(this, 250);
        }
    };

    private static String mmss(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** A thin progress line: dim track, gold played part. */
    private static final class Bar extends View {
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint done = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float f;
        Bar(Context c, Ui.T t) { super(c); track.setColor(0x40FFFFFF); done.setColor(t.gold); }
        void set(float v) { f = Math.max(0, Math.min(1, v)); invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, getWidth(), h, r, r, track);
            if (f > 0) c.drawRoundRect(0, 0, Math.max(h, getWidth() * f), h, r, r, done);
        }
    }
}
