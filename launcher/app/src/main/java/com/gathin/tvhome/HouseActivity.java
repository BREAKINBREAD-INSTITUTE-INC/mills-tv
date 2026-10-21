package com.gathin.tvhome;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * House: "what's happening at home?" The indoor thermostat stays honestly empty until the Nest
 * is linked over Matter; the outdoor reading is real (Open-Meteo). The monthly housing budget
 * is the concept's breakdown, labelled as sample figures until it's wired to a real sheet:
 * one gold ramp, bars scaled to the biggest cost, amounts on the right (design rules §5).
 */
public class HouseActivity extends Screen {

    private LinearLayout indoor, outdoor;

    // Concept figures (mills-tv-house-budget). Shown with a SAMPLE tag; never presented as real.
    private static final String[] ITEMS = {"Rent", "Electric", "Insurance", "Internet", "Water", "Gas", "Other"};
    private static final int[] AMOUNTS = {1500, 200, 100, 100, 50, 50, 100};
    private static final int BUDGET = 2500;

    @Override protected String[] crumb() { return new String[]{"HOME", "HOUSE"}; }

    @Override
    protected void build() {
        LinearLayout col = Ui.vbox(this);
        col.addView(title("House", null));

        LinearLayout top = Ui.hbox(this);
        indoor = Plate.of(this, t, Plate.Kind.TILE);
        indoor.setOrientation(LinearLayout.VERTICAL);
        indoor.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        top.addView(indoor, new LinearLayout.LayoutParams(0, Ui.dp(this, 122), 1.2f));
        outdoor = Plate.of(this, t, Plate.Kind.TILE);
        outdoor.setOrientation(LinearLayout.VERTICAL);
        outdoor.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, Ui.dp(this, 122), 1f);
        op.leftMargin = Ui.dp(this, 14);
        top.addView(outdoor, op);
        col.addView(top, Ui.lpTop(this, -1, -2, 10));

        col.addView(budget(), Ui.lpTop(this, -1, -2, 12));
        body.addView(col, new FrameLayout.LayoutParams(-1, -2));
        foot("◀ ▶", "move", "BACK", "command center");
        indoor.requestFocus();       // entering House: focus starts on the temperature (rules §4)
    }

    @Override
    protected void refresh() {
        new Thread(() -> {
            Live.Weather w = Live.fetchWeatherBlocking(getApplicationContext());
            Live.fetchHouseBlocking(getApplicationContext());
            main.post(() -> fill(w));
        }, "mills-house").start();
        fill(Live.weather());
    }

    private void fill(Live.Weather w) {
        indoor.removeAllViews();
        Live.House h = Live.house();
        indoor.addView(Ui.eyebrow(this, t, "Indoor"));
        LinearLayout big = Ui.hbox(this);
        big.setGravity(Gravity.BOTTOM);
        boolean live = h.fresh() && h.indoorF != null;
        big.addView(Ui.text(this, live ? h.indoorF + "°" : "—", 32, live ? t.text : t.dim, 300));
        if (live) {
            TextView act = Ui.text(this, "  currently " + (h.running == null ? "idle" : h.running) + " · " + h.targets(), 11, t.dim, 300);
            act.setPadding(0, 0, 0, Ui.dp(this, 5));
            big.addView(act);
        }
        indoor.addView(big, Ui.lpTop(this, -2, -2, 6));
        LinearLayout chips = Ui.hbox(this);
        chips.addView(Ui.chip(this, t, h.state(), h.chip()));
        if (live && h.humidity != null) {
            LinearLayout.LayoutParams cp = Ui.lp(-2, Ui.dp(this, 20)); cp.leftMargin = Ui.dp(this, 6);
            chips.addView(Ui.chip(this, t, Ui.Status.IDLE, h.humidity + "% humidity"), cp);
        }
        indoor.addView(chips, Ui.lpTop(this, -2, Ui.dp(this, 20), 8));
        indoor.addView(Ui.text(this, live ? "Read over Matter by the bridge on Hub · controls stay on your phone"
                : "Shows here once the Nest is linked over Matter. Controls stay on your phone.",
                9, t.dim, 300), Ui.lpTop(this, -1, -2, 8));

        outdoor.removeAllViews();
        outdoor.addView(Ui.eyebrow(this, t, "Outdoor"));
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.BOTTOM);
        row.addView(Ui.text(this, w.has() ? w.tempF + "°" : "—", 30, t.text, 300));
        if (w.has()) {
            TextView sky = Ui.text(this, "  " + w.sky(), 14, t.dim, 300);
            sky.setPadding(0, 0, 0, Ui.dp(this, 6));
            row.addView(sky);
        }
        outdoor.addView(row, Ui.lpTop(this, -2, -2, 5));
        if (w.riseMin >= 0) {
            outdoor.addView(Ui.kv(this, t, "Sunrise · sunset", hm(w.riseMin) + " · " + hm(w.setMin), 0, 10), Ui.lpTop(this, -1, -2, 5));
        }
        outdoor.addView(Ui.text(this, w.has() ? "Open-Meteo · updated " + Live.ago(w.at) : "Weather service not answering",
                8.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 3));
    }

    private View budget() {
        LinearLayout card = glass(16);
        LinearLayout head = Ui.hbox(this);
        head.setGravity(Gravity.BOTTOM);
        LinearLayout left = Ui.vbox(this);
        String month = new java.text.SimpleDateFormat("MMMM", Locale.US).format(new java.util.Date());
        left.addView(Ui.eyebrow(this, t, "Monthly housing · " + month));
        int total = 0, max = 0;
        for (int a : AMOUNTS) { total += a; max = Math.max(max, a); }
        left.addView(Ui.text(this, money(total), 23, t.text, 500), Ui.lpTop(this, -2, -2, 6));
        head.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout right = Ui.vbox(this);
        right.setGravity(Gravity.END);
        right.addView(Ui.single(sampleTag("Sample figures")));
        // sample figures can't be "good": grey, never a green dot for numbers that aren't real
        right.addView(Ui.chip(this, t, Ui.Status.IDLE, "sample · " + money(BUDGET - total) + " left"), Ui.lpTop(this, -2, Ui.dp(this, 20), 6));
        head.addView(right);
        card.addView(head, Ui.lp(-1, -2));

        for (int i = 0; i < ITEMS.length; i++) {
            LinearLayout r = Ui.hbox(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView l = Ui.text(this, ITEMS[i], 10.5f, t.text, 400);
            r.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 80), -2));
            FrameLayout track = new FrameLayout(this);
            track.setBackground(Ui.pill(this, t.light ? 0x140B0F14 : 0xFF141C25));
            View fill = new View(this);
            // one gold ramp, light to dark, biggest cost longest (rules §2)
            float k = AMOUNTS[i] / (float) max;
            int gold = blend(0xFFF0C25A, 0xFFC98A2E, i / (float) (ITEMS.length - 1));
            fill.setBackground(Ui.pill(this, gold));
            track.addView(fill, new FrameLayout.LayoutParams(0, -1));
            final View f = fill; final float kk = k;
            track.post(() -> { f.getLayoutParams().width = Math.max(Ui.dp(this, 6), Math.round(track.getWidth() * kk)); f.requestLayout(); });
            r.addView(track, new LinearLayout.LayoutParams(0, Ui.dp(this, 8), 1f));
            TextView amt = Ui.text(this, money(AMOUNTS[i]), 10.5f, t.dim, 400);
            amt.setGravity(Gravity.END);
            r.addView(amt, new LinearLayout.LayoutParams(Ui.dp(this, 62), -2));
            card.addView(r, Ui.lpTop(this, -1, -2, i == 0 ? 12 : 6));
        }
        return card;
    }

    private static int blend(int a, int b, float k) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * k);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * k);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static String money(int v) { return String.format(Locale.US, "$%,d", v); }

    private static String hm(int min) {
        int h = min / 60, m = min % 60;
        return String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "am" : "pm");
    }
}
