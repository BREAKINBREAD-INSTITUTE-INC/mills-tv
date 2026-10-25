package com.gathin.tvhome;

import android.content.Intent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Locale;

/**
 * Quick menu (JJ, Oct 25, after Lumen's control center): the clock on the left, and on the right
 * two-line pill buttons (what it is · what it's on now) and a row of round ones. Opened from the
 * sliders icon at Home's top right, or MENU on Home. OK on a button either opens a screen or
 * switches one of the TV's own looks (Look, Mode); nothing here changes the house, an agent or a
 * machine. Focus starts on Edit layout. BACK returns Home.
 */
public class QuickMenuActivity extends Screen {

    private GridLayout pills;
    private TextView lookValue, modeValue, houseValue, camsValue;

    @Override protected String[] crumb() { return new String[]{"HOME", "QUICK MENU"}; }

    @Override
    protected void build() {
        // left: the time, big, with today's date and the temperatures
        LinearLayout left = Ui.vbox(this);
        TextClock big = Ui.smallClock(this, t);
        big.setTextSize(64);
        left.addView(big);
        TextClock date = Ui.smallClock(this, t);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(15);
        left.addView(date, Ui.lpTop(this, -2, -2, 6));
        Live.Weather w = Live.weather();
        Live.House h = Live.house();
        String temps = (w.has() ? w.tempF + "° " + w.sky() : "outdoor reading unavailable")
                + (h.fresh() && h.indoorF != null ? " · " + h.indoorF + "° inside" : "");
        left.addView(Ui.text(this, temps, 12, t.dim, 300), Ui.lpTop(this, -2, -2, 8));
        body.addView(left, Ui.at(this, Gravity.TOP | Gravity.START, 0, 40, 0, 0));

        // right: the buttons, two across, then the round ones
        LinearLayout right = Ui.vbox(this);
        right.setClipChildren(false);
        pills = new GridLayout(this);
        pills.setColumnCount(2);
        pills.setClipChildren(false);
        right.addView(pills);

        pill("Edit layout", "AI Team cards", v -> open(new Intent(this, MissionControlActivity.class)
                .putExtra(MissionControlActivity.EXTRA_EDIT, true)));
        lookValue = pill("Look", "", v -> {
            Prefs.setLook(this, Prefs.look(this) == Prefs.LOOK_GLASS ? Prefs.LOOK_CLASSIC : Prefs.LOOK_GLASS);
            recreateKeeping("Look");
        });
        modeValue = pill("Mode", "", v -> {
            int m = ThemeManager.mode(this);   // Auto -> Dark -> Light -> Auto
            ThemeManager.setMode(this, m == ThemeManager.AUTO ? ThemeManager.DARK : m == ThemeManager.DARK ? ThemeManager.LIGHT : ThemeManager.AUTO);
            recreateKeeping("Mode");
        });
        houseValue = pill("Thermostat", "", v -> open(new Intent(this, HouseActivity.class)));
        camsValue = pill("Cameras", "", v -> open(new Intent(this, CamerasActivity.class)));
        pill("Sleep", SleepService.running() ? "playing · " + SleepService.label(SleepService.playing()) : "sounds · timer",
                v -> settings("sleep"));

        LinearLayout rounds = Ui.hbox(this);
        rounds.setClipChildren(false);
        round(rounds, 0, "Home", v -> { finish(); overridePendingTransition(0, 0); });
        round(rounds, R.drawable.ic_wall, "Background", v -> settings("background"));
        round(rounds, R.drawable.ic_grid, "Apps on Home", v -> settings("apps"));
        round(rounds, R.drawable.ic_gear, "Settings", v -> settings("overview"));
        right.addView(rounds, Ui.lpTop(this, -2, -2, 14));

        FrameLayout.LayoutParams rp = Ui.at(this, Gravity.TOP | Gravity.END, 0, 24, 0, 0);
        rp.width = Ui.dp(this, 200 * 2 + 12 + 8);   // two pills and the gap (the grid alone measures short)
        body.addView(right, rp);
        foot("◀ ▶ ▲ ▼", "choose", "OK", "open · switch", "BACK", "home");
        fillValues();

        String keep = getIntent().getStringExtra(EXTRA_FOCUS);
        pills.post(() -> {
            View f = keep == null ? null : body.findViewWithTag(keep);
            (f != null ? f : pills.getChildAt(0)).requestFocus();
        });
    }

    private static final String EXTRA_FOCUS = "quick_focus";

    @Override
    protected void refresh() {
        new Thread(() -> {
            Live.fetchHouseBlocking(getApplicationContext());
            Live.fetchCamsBlocking(getApplicationContext());
            main.post(this::fillValues);
        }, "mills-quick").start();
    }

    private void fillValues() {
        boolean glass = Prefs.look(this) == Prefs.LOOK_GLASS;
        lookValue.setText(glass ? (t.light ? "Glass · Daylight" : "Glass · Night") : "Classic");
        int m = ThemeManager.mode(this);
        String now = ThemeManager.isLight(this) ? "Light" : "Dark";
        modeValue.setText(m == ThemeManager.AUTO ? "Auto · now " + now
                : (m == ThemeManager.LIGHT ? "Light" : "Dark") + (m == ThemeManager.LIGHT && !t.light ? " · this background has no day version" : ""));
        Live.House h = Live.house();
        houseValue.setText(h.fresh() ? h.targets() : "Nest not answering");
        Live.Cams c = Live.cams();
        int armed = 0;
        for (Live.Cam x : c.cams) if (Boolean.TRUE.equals(x.armed)) armed++;
        camsValue.setText(c.cams.isEmpty() ? c.chip() : armed + " of " + c.cams.size() + " armed");
    }

    /** A two-line pill: the name, and what it's on now under it. Returns the second line. */
    private TextView pill(String name, String value, View.OnClickListener ok) {
        Plate p = Plate.of(this, t, Plate.Kind.BUTTON);
        p.setTag(name);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 18), 0);
        p.addView(Ui.single(Ui.text(this, name, 12, t.text, 600)));
        TextView v = Ui.single(Ui.text(this, value, 9.5f, t.dim, 400));
        p.addView(v, Ui.lpTop(this, -2, -2, 1));
        p.setOnClickListener(ok);
        final int i = pills.getChildCount();
        p.setOnKeyListener((vv, key, e) -> {      // never wrap off the grid's edges
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && i % 2 == 1) return true;
            return key == KeyEvent.KEYCODE_DPAD_UP && i < 2;
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = Ui.dp(this, 200);
        lp.height = Ui.dp(this, 54);
        lp.rightMargin = i % 2 == 0 ? Ui.dp(this, 12) : 0;
        lp.bottomMargin = Ui.dp(this, 12);
        pills.addView(p, lp);
        return v;
    }

    /** A round icon button (icon 0 = the house glyph, drawn as text). */
    private void round(LinearLayout row, int icon, String name, View.OnClickListener ok) {
        Plate p = Plate.of(this, t, Plate.Kind.BUTTON);
        p.setTag(name);
        p.setGravity(Gravity.CENTER);
        p.setContentDescription(name);
        final int normal = t.light ? 0xB80B0F14 : 0xC7F2F4F7;
        final ImageView iv = icon == 0 ? null : new ImageView(this);
        if (iv == null) {
            p.addView(Ui.text(this, "⌂", 17, t.text, 500));
        } else {
            iv.setImageResource(icon);
            iv.setColorFilter(normal);
            p.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        }
        p.onFocus((pl, has) -> {
            // Night Glass: the focused button is white, so its icon goes dark with it
            if (iv != null) iv.setColorFilter(has && t.inkOnFocus ? 0xFF0B0F14 : normal);
            foot(has ? new String[]{"OK", name.toLowerCase(Locale.US), "BACK", "home"}
                     : new String[]{"◀ ▶ ▲ ▼", "choose", "OK", "open · switch", "BACK", "home"});
        });
        p.setOnClickListener(ok);
        final boolean first = row.getChildCount() == 0;
        p.setOnKeyListener((vv, key, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                && (key == KeyEvent.KEYCODE_DPAD_DOWN || (first && key == KeyEvent.KEYCODE_DPAD_LEFT)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
        lp.rightMargin = Ui.dp(this, 12);
        row.addView(p, lp);
    }

    private void open(Intent i) {
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    private void settings(String tab) {
        open(new Intent(this, MillsSettingsActivity.class).putExtra(MillsSettingsActivity.EXTRA_TAB, tab));
    }

    /** Look or Mode changed: redraw this screen in it, focus back on the button pressed. */
    private void recreateKeeping(String tag) {
        Intent i = getIntent();
        i.putExtra(EXTRA_FOCUS, tag);
        finish();
        overridePendingTransition(0, 0);
        startActivity(i);
        overridePendingTransition(0, 0);
    }
}
