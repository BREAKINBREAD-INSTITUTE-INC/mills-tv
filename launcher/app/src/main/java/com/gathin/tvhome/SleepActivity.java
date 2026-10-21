package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The Sleep screen: pure black while the sound plays (Settings > Sleep starts it). The BRAVIA's own
 * Picture off is a Sony system function with no app or adb hook, so this is the dark screen: an
 * all-black window asking for the lowest backlight a window may ask for. It keeps the TV awake
 * only while the sound plays, so the TV can't drop into standby (which would cut the sound) or
 * start the screensaver over it; when the timer ends the sound stops, the keep-awake goes, and the
 * TV goes to standby on its own timer (the MILLS screensaver stays black meanwhile).
 *
 * Any key shows a small dim panel for a few seconds: what's playing, the time left, Stop and
 * Change sound. Back stops the sound and leaves.
 */
public class SleepActivity extends Activity implements SleepService.Listener {

    static final String EXTRA_SOUND = "sound", EXTRA_TIMER = "timer";
    private static final long PANEL_MS = 8000;
    private static boolean shown;                         // this screen exists (the screensaver stays black)

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(ThemeManager.wrap(base)); }

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout panel;
    private TextView what, left, stop, change;

    private long createdAt;
    private boolean slept;                                // the screen went off (standby) while this was up
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, android.content.Intent i) { slept = true; }
    };

    static boolean shown() { return shown; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setBackgroundColor(0xFF000000);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;                          // the lowest this window may ask for (the panel may ignore it)
        lp.buttonBrightness = 0f;
        getWindow().setAttributes(lp);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        panel = Ui.vbox(this);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 16));
        panel.setBackground(Ui.round(this, 0xE6101418, 14, 0x26FFFFFF));
        panel.setMinimumWidth(Ui.dp(this, 300));
        what = Ui.text(this, "", 15, 0xB3F2F4F7, 400);
        left = Ui.text(this, "", 11, 0x80F2F4F7, 300);
        panel.addView(what);
        panel.addView(left, Ui.lpTop(this, -2, -2, 4));
        LinearLayout btns = Ui.hbox(this);
        stop = button("Stop", v -> { SleepService.stop(this); finish(); });
        change = button("Change sound", v -> changeSound());
        btns.addView(stop);
        LinearLayout.LayoutParams cl = Ui.lp(-2, -2); cl.leftMargin = Ui.dp(this, 10);
        btns.addView(change, cl);
        panel.addView(btns, Ui.lpTop(this, -2, -2, 12));
        panel.setVisibility(View.GONE);
        root.addView(panel, Ui.at(this, Gravity.BOTTOM | Gravity.START, 64, 0, 0, 48));

        shown = true;
        createdAt = System.currentTimeMillis();
        registerReceiver(screenOff, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
        if (!SleepService.running()) startFromIntent();
    }

    private TextView button(String label, View.OnClickListener ok) {
        TextView b = Ui.text(this, label, 11, 0xCCF2F4F7, 500);
        b.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        b.setFocusable(true);
        b.setBackground(Ui.round(this, 0x14FFFFFF, 8, 0));
        b.setOnFocusChangeListener((v, has) -> v.setBackground(Ui.round(this, has ? 0x40FFFFFF : 0x14FFFFFF, 8, 0)));
        b.setOnClickListener(ok);
        return b;
    }

    private void startFromIntent() {
        List<File> all = SleepService.sounds(this);
        if (all.isEmpty()) { finish(); return; }
        int s = Math.max(0, Math.min(all.size() - 1, getIntent().getIntExtra(EXTRA_SOUND, 0)));
        int ti = Math.max(0, Math.min(SleepService.TIMERS_MIN.length - 1, getIntent().getIntExtra(EXTRA_TIMER, 1)));
        SleepService.start(this, all.get(s), SystemClock.elapsedRealtime() + SleepService.TIMERS_MIN[ti] * 60_000L);
    }

    private void changeSound() {
        List<File> all = SleepService.sounds(this);
        File cur = SleepService.playing();
        if (all.isEmpty() || cur == null) return;
        int i = Math.max(0, all.indexOf(cur));
        File next = all.get((i + 1) % all.size());
        Prefs.prefs(this).edit().putInt("sleep_sound", (i + 1) % all.size()).apply();
        SleepService.start(this, next, SystemClock.elapsedRealtime() + SleepService.msLeft());
        showPanel();
    }

    @Override protected void onResume() {
        super.onResume();
        // the TV went to standby after the timer ended and was woken again: go home, not to black
        if (slept && !SleepService.running() && Prefs.prefs(this).getLong("sleep_ended_at", 0) > createdAt) { finish(); return; }
        SleepService.listen(this);
        changed();
    }

    @Override protected void onPause() {
        super.onPause();
        SleepService.unlisten(this);
        h.removeCallbacks(hide);
        h.removeCallbacks(clock);
    }

    @Override protected void onDestroy() {
        shown = false;
        try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** The service started, changed or stopped: keep the TV awake only while the sound plays. */
    @Override public void changed() {
        if (SleepService.running()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (panel.getVisibility() == View.VISIBLE) fill();
    }

    private void fill() {
        if (SleepService.running()) {
            what.setText(SleepService.label(SleepService.playing()));
            long m = (SleepService.msLeft() + 59_999) / 60_000;
            left.setText(String.format(Locale.US, "%d:%02d left · fades out over the last 5 min · Back stops", m / 60, m % 60));
            change.setVisibility(SleepService.sounds(this).size() > 1 ? View.VISIBLE : View.GONE);
            stop.setText("Stop");
        } else {
            stop.setText("Home");
            what.setText("Sleep timer ended");
            left.setText("The sound has stopped · Back returns home");
            change.setVisibility(View.GONE);
        }
    }

    private void showPanel() {
        fill();
        panel.setVisibility(View.VISIBLE);
        panel.requestLayout();
        if (getCurrentFocus() != change || change.getVisibility() != View.VISIBLE) stop.requestFocus();
        h.removeCallbacks(hide);
        h.postDelayed(hide, PANEL_MS);
        h.removeCallbacks(clock);
        h.postDelayed(clock, 1000);
    }

    private final Runnable hide = () -> panel.setVisibility(View.GONE);
    private final Runnable clock = new Runnable() {
        @Override public void run() { if (panel.getVisibility() == View.VISIBLE) { fill(); h.postDelayed(this, 1000); } }
    };

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) { SleepService.stop(this); finish(); }
            return true;
        }
        boolean volume = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (volume) return super.dispatchKeyEvent(e);     // the TV's own volume still works
        if (panel.getVisibility() != View.VISIBLE) {      // the first key only wakes the panel
            if (e.getAction() == KeyEvent.ACTION_DOWN) showPanel();
            return true;
        }
        h.removeCallbacks(hide);
        h.postDelayed(hide, PANEL_MS);
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {   // two buttons: move between them
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                TextView to = k == KeyEvent.KEYCODE_DPAD_RIGHT ? change : stop;
                if (to.getVisibility() == View.VISIBLE) to.requestFocus();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                View f = getCurrentFocus();
                if (f == change && change.getVisibility() == View.VISIBLE) changeSound();
                else if (f == stop) { SleepService.stop(this); finish(); }
            }
            return true;
        }
        return true;                                        // nothing else does anything here
    }
}
