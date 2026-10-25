package com.gathin.tvhome;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * House: "what's happening at home?" The thermostat panel is live: the Nest, read and changed
 * over Matter by the bridge on Hub (~/Scripts/tv/nest-bridge). ▲ ▼ on the target move it a
 * degree; presses gather for 1.5 s and only the final value is sent. A change counts as done only
 * when the bridge has read it back from the Nest ("Set to 77°F ✓"); anything else says why. The
 * bridge enforces the safe range (68-82°F), the heat/cool gap and the rate limit; the TV mirrors
 * them so a press past a limit is refused here, not sent. Mode chips need OK twice.
 * The outdoor reading is real (Open-Meteo). The monthly housing budget is JJ's own bills, read on
 * Hub (/api/budget, private: only Hub and this TV may ask): rent as fixed, the others as their
 * latest real bill, a two-monthly bill halved and saying so. Budget bars per design rules §5.
 */
public class HouseActivity extends Screen {

    private static final long DEBOUNCE_MS = 1500, ARM_MS = 4000;

    private LinearLayout thermo, targets, chips, outdoor;
    private TextView indoorBig, stateLine, status, lastLine;
    private LinearLayout stateChip;
    private final List<Plate> targetPlates = new ArrayList<>();
    private final List<Plate> chipPlates = new ArrayList<>();
    private final List<String> targetKeys = new ArrayList<>();   // "heat" / "cool", left to right
    private final List<String> chipKeys = new ArrayList<>();
    private String shownMode;                                     // modeKey the chips were painted for

    // a change in progress: the value on screen, waiting out the debounce or with the bridge
    private String pendingWhich;
    private int pendingValue;
    private boolean inFlight, resend;
    private String armedMode;
    private long armedAt;

    private final Runnable send = this::sendPending;

    private LinearLayout budgetCard;

    @Override protected String[] crumb() { return new String[]{"HOME", "HOUSE"}; }

    @Override
    protected void build() {
        LinearLayout col = Ui.vbox(this);
        col.setClipChildren(false);
        col.addView(title("House", null));

        LinearLayout row = Ui.hbox(this);
        row.setClipChildren(false);
        thermo = glass(18);
        thermo.setClipChildren(false);
        buildThermo();
        row.addView(thermo, new LinearLayout.LayoutParams(0, -1, 1.25f));

        LinearLayout right = Ui.vbox(this);
        right.setClipChildren(false);
        Plate out = Plate.of(this, t, Plate.Kind.TILE);
        outdoor = out;
        out.setOrientation(LinearLayout.VERTICAL);
        out.setPadding(Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 13));
        out.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { focusModeOrTarget(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        });
        out.onFocus((p, has) -> { if (has) foot("◀", "thermostat", "BACK", "command center"); });
        right.addView(out, new LinearLayout.LayoutParams(-1, Ui.dp(this, 118)));
        right.addView(budget(), Ui.lpTop(this, -1, -2, 12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.leftMargin = Ui.dp(this, 14);
        row.addView(right, rp);
        col.addView(row, Ui.lpTop(this, -1, -2, 10));

        body.addView(col, new FrameLayout.LayoutParams(-1, -2));
        foot("▲ ▼", "target", "▶", "mode", "BACK", "command center");
        fill(Live.weather());
        // entering House: focus starts on the temperature (rules §4)
        thermo.post(() -> { if (!targetPlates.isEmpty()) targetPlates.get(0).requestFocus(); else focusModeOrTarget(); });
    }

    // ---- the thermostat panel ---------------------------------------------------------------------

    private void buildThermo() {
        LinearLayout head = Ui.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.eyebrow(this, t, "Living room · Nest"), new LinearLayout.LayoutParams(0, -2, 1f));
        stateChip = Ui.hbox(this);
        head.addView(stateChip, new LinearLayout.LayoutParams(-2, Ui.dp(this, 20)));
        thermo.addView(head, Ui.lp(-1, -2));

        LinearLayout big = Ui.hbox(this);
        big.setClipChildren(false);
        big.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout now = Ui.vbox(this);
        indoorBig = Ui.text(this, "—", 54, t.text, 300);
        now.addView(indoorBig);
        now.addView(Ui.text(this, "indoor now", 9.5f, t.dim, 300));
        big.addView(now, new LinearLayout.LayoutParams(Ui.dp(this, 130), -2));
        targets = Ui.hbox(this);
        targets.setClipChildren(false);
        big.addView(targets, new LinearLayout.LayoutParams(0, -2, 1f));
        thermo.addView(big, Ui.lpTop(this, -1, -2, 8));

        LinearLayout modeRow = Ui.hbox(this);
        modeRow.setClipChildren(false);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.addView(Ui.text(this, "Mode", 10, t.dim, 300), new LinearLayout.LayoutParams(Ui.dp(this, 46), -2));
        chips = Ui.hbox(this);
        chips.setClipChildren(false);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.addView(chips, new LinearLayout.LayoutParams(0, Ui.dp(this, 28), 1f));
        thermo.addView(modeRow, Ui.lpTop(this, -1, -2, 14));

        stateLine = Ui.single(Ui.text(this, "", 10, t.dim, 400));
        thermo.addView(stateLine, Ui.lpTop(this, -1, -2, 12));
        status = Ui.single(Ui.text(this, "", 13, t.dim, 500));
        thermo.addView(status, Ui.lpTop(this, -1, -2, 10));
        lastLine = Ui.single(Ui.text(this, "", 9, t.dim, 300));
        thermo.addView(lastLine, Ui.lpTop(this, -1, -2, 8));
        thermo.addView(Ui.text(this, "Changed over Matter by the bridge on Hub · safe range 68–82°F · Google Home works as before",
                8.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 6));
    }

    /** One target: "COOL TO" over the setpoint; ▲ ▼ move it (the foot says so). */
    private Plate targetPlate(String which) {
        Plate p = Plate.of(this, t, Plate.Kind.TILE);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        p.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        p.addView(Ui.eyebrow(this, t, "heat".equals(which) ? "Heat to" : "Cool to"));
        TextView v = Ui.text(this, "—", 36, t.text, 400);
        v.setGravity(Gravity.CENTER);
        p.addView(v, Ui.lpTop(this, -2, -2, 2));
        p.setTag(v);
        p.setContentDescription("heat".equals(which) ? "Heating target" : "Cooling target");
        p.onFocus((pl, has) -> { if (has) foot("▲ ▼", "target", "▶", "mode", "BACK", "command center"); });
        p.setOnKeyListener((view, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int i = targetPlates.indexOf(p);
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:   press(which, +1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: press(which, -1); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (i > 0) targetPlates.get(i - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (i + 1 < targetPlates.size()) targetPlates.get(i + 1).requestFocus(); else focusModeOrTarget();
                    return true;
                default: return false;
            }
        });
        return p;
    }

    private Plate modeChip(String key) {
        Plate c = Plate.of(this, t, Plate.Kind.BUTTON);
        c.setGravity(Gravity.CENTER);
        c.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout in = Ui.hbox(this);
        in.setGravity(Gravity.CENTER_VERTICAL);
        View d = Ui.dot(this, t.gold, 5);
        in.addView(d);
        TextView w = Ui.text(this, label(key), 10, t.text, 500);
        LinearLayout.LayoutParams wp = Ui.lp(-2, -2); wp.leftMargin = Ui.dp(this, 5);
        in.addView(w, wp);
        c.addView(in);
        c.setTag(new View[]{d, w});
        c.setOnClickListener(v -> chooseMode(key));
        c.onFocus((pl, has) -> {
            if (has) foot("◀ ▶", "move", "OK", "choose", "▲", "target", "BACK", "command center");
            else if (key.equals(armedMode)) { armedMode = null; if (!inFlight) say("", Ui.Status.IDLE); }
        });
        c.setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int i = chipPlates.indexOf(c);
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (i > 0) chipPlates.get(i - 1).requestFocus();
                    else if (!targetPlates.isEmpty()) targetPlates.get(targetPlates.size() - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (i + 1 < chipPlates.size()) chipPlates.get(i + 1).requestFocus(); else outdoor.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (!targetPlates.isEmpty()) targetPlates.get(0).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: return true;
                default: return false;
            }
        });
        return c;
    }

    /** The current mode's chip, else the first chip, else a target, else the outdoor tile. */
    private void focusModeOrTarget() {
        for (int i = 0; i < chipKeys.size(); i++) if (chipKeys.get(i).equals(shownMode)) { chipPlates.get(i).requestFocus(); return; }
        if (!chipPlates.isEmpty()) chipPlates.get(0).requestFocus();
        else if (!targetPlates.isEmpty()) targetPlates.get(0).requestFocus();
        else outdoor.requestFocus();
    }

    /** Which setpoints the mode uses: Cool → cool, Heat → heat, Auto → both, Off → none. */
    private static List<String> keysFor(Live.House h) {
        List<String> k = new ArrayList<>();
        if (!h.canControl() || h.modeKey == null) return k;
        if ("Heat".equals(h.modeKey) || "Auto".equals(h.modeKey)) k.add("heat");
        if ("Cool".equals(h.modeKey) || "Auto".equals(h.modeKey)) k.add("cool");
        return k;
    }

    private void fillThermo(Live.House h) {
        boolean live = h.fresh() && h.indoorF != null;
        indoorBig.setText(live ? h.indoorF + "°" : "—");
        indoorBig.setTextColor(live ? t.text : t.dim);
        stateChip.removeAllViews();
        stateChip.addView(Ui.chip(this, t, h.fresh() ? Ui.Status.OK : (h.bridge ? Ui.Status.WARN : Ui.Status.BAD), h.chip()));

        // targets: rebuilt only when the mode's setpoints change, and never under a change in progress
        List<String> keys = keysFor(h);
        if (!keys.equals(targetKeys) && pendingWhich == null && !inFlight) {
            boolean hadFocus = targets.hasFocus();
            targets.removeAllViews();
            targetPlates.clear();
            targetKeys.clear();
            targetKeys.addAll(keys);
            for (int i = 0; i < keys.size(); i++) {
                Plate p = targetPlate(keys.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 92));
                lp.leftMargin = Ui.dp(this, i == 0 ? 6 : 12);
                targets.addView(p, lp);
                targetPlates.add(p);
            }
            if (keys.isEmpty() && h.canControl()) {
                targets.addView(Ui.text(this, "Off".equals(h.modeKey) ? "The Nest is off.\nPick a mode to set a target." : "No target in this mode",
                        10.5f, t.dim, 300), new LinearLayout.LayoutParams(-1, -2));
            }
            if (hadFocus) targets.post(this::focusModeOrTarget);
        }
        for (int i = 0; i < targetPlates.size(); i++) {
            String which = targetKeys.get(i);
            TextView v = (TextView) targetPlates.get(i).getTag();
            if (which.equals(pendingWhich)) { v.setText(pendingValue + "°"); v.setTextColor(t.goldText); continue; }
            Integer cur = current(h, which);
            v.setText(cur == null ? "—" : cur + "°");
            v.setTextColor(t.text);
        }

        // mode chips: only the modes the Nest accepts; the current one gold
        List<String> modes = h.canControl() ? h.modes : Collections.emptyList();
        if (!modes.equals(chipKeys)) {
            boolean hadFocus = chips.hasFocus();
            chips.removeAllViews();
            chipPlates.clear();
            chipKeys.clear();
            chipKeys.addAll(modes);
            for (int i = 0; i < chipKeys.size(); i++) {
                Plate c = modeChip(chipKeys.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
                lp.leftMargin = Ui.dp(this, i == 0 ? 0 : 8);
                chips.addView(c, lp);
                chipPlates.add(c);
            }
            if (chipKeys.isEmpty()) chips.addView(Ui.text(this, h.mode == null ? "—" : h.mode, 10.5f, t.text, 500));
            if (hadFocus) chips.post(this::focusModeOrTarget);
        }
        shownMode = h.modeKey;
        for (int i = 0; i < chipPlates.size(); i++) paintChip(chipPlates.get(i), chipKeys.get(i).equals(shownMode));

        // running state: only what the Nest says. The 4th gen doesn't share it over Matter.
        if (!live) stateLine.setText("");
        else if (h.runningExposed) stateLine.setText("Now " + (h.running == null ? "idle" : h.running));
        else stateLine.setText("Cooling / heating / idle: the Nest doesn't share this over Matter");
        stateLine.setTextColor(h.runningExposed && "heating".equals(h.running) ? t.orangeText
                : h.runningExposed && "cooling".equals(h.running) ? t.tealText : t.dim);

        lastLine.setText(h.lastWhat == null ? (h.canControl() ? "No changes from the TV or Hub yet" : "")
                : "Last change · " + h.lastWhat + " · " + Live.ago(h.lastAt));
        if (!h.canControl() && !inFlight && pendingWhich == null) {
            say(!h.bridge ? "Nest bridge not answering · controls stay on your phone"
                    : !h.controls ? "This bridge can't change the Nest yet" : "Nest not answering · can't change it now", Ui.Status.IDLE);
        }
    }

    private void paintChip(Plate c, boolean on) {
        View[] v = (View[]) c.getTag();
        v[0].setVisibility(on ? View.VISIBLE : View.GONE);
        TextView w = (TextView) v[1];
        w.setTextColor(on ? t.goldText : t.dim);
        Ui.weigh(w, on ? 600 : 400);
        ((LinearLayout.LayoutParams) w.getLayoutParams()).leftMargin = on ? Ui.dp(this, 5) : 0;
    }

    private static Integer current(Live.House h, String which) { return "heat".equals(which) ? h.heatF : h.coolF; }

    /** The confirmation line: green when the Nest confirmed, red when it didn't, grey/orange while waiting. */
    private void say(String text, String state) {
        status.setText(text);
        status.setTextColor(Ui.Status.IDLE.equals(state) ? t.dim : t.stateText(state));
    }

    // ---- changing the setpoint --------------------------------------------------------------------

    private void press(String which, int delta) {
        Live.House h = Live.house();
        if (!h.canControl()) { say("Nest not answering · can't change it now", Ui.Status.BAD); return; }
        if (pendingWhich != null && !pendingWhich.equals(which)) {   // the other target first, then this one
            main.removeCallbacks(send);
            sendPending();
            return;
        }
        Integer cur = current(h, which);
        if (cur == null) { say("No " + which + " setpoint from the Nest", Ui.Status.BAD); return; }
        int base = which.equals(pendingWhich) ? pendingValue : cur;
        int lo = "heat".equals(which) ? h.heatMin : h.coolMin, hi = "heat".equals(which) ? h.heatMax : h.coolMax;
        int nv = Math.max(lo, Math.min(hi, base + delta));
        if (nv == base) {
            say((delta > 0 ? hi + "°F is the top" : lo + "°F is the bottom") + " of the safe range", Ui.Status.WARN);
            return;
        }
        if ("Auto".equals(h.modeKey) && h.heatF != null && h.coolF != null) {
            int heat = "heat".equals(which) ? nv : h.heatF, cool = "cool".equals(which) ? nv : h.coolF;
            if (cool - heat < h.gapF) { say("Heat and cool stay " + h.gapF + "° apart", Ui.Status.WARN); return; }
        }
        pendingWhich = which;
        pendingValue = nv;
        fillThermo(h);
        if (!inFlight) say(nv == cur ? "Back to " + cur + "°F" : "Sets to " + nv + "°F in a moment…", Ui.Status.IDLE);
        main.removeCallbacks(send);
        main.postDelayed(send, DEBOUNCE_MS);
    }

    private void sendPending() {
        if (pendingWhich == null) return;
        if (inFlight) { resend = true; return; }
        final String which = pendingWhich;
        final int value = pendingValue;
        Integer cur = current(Live.house(), which);
        if (cur != null && cur == value) {           // ▲ then ▼: nothing to send
            pendingWhich = null;
            say("No change · still " + value + "°F", Ui.Status.IDLE);
            fillThermo(Live.house());
            return;
        }
        inFlight = true;
        say("Setting " + value + "°F…", Ui.Status.WARN);
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            JSONObject b = new JSONObject();
            try { b.put("f", value); } catch (Exception ignored) {}
            Live.HouseChange r = Live.changeHouseBlocking(app, which, b);
            main.post(() -> done(which, value, r));
        }, "mills-nest-set").start();
    }

    private void done(String which, int value, Live.HouseChange r) {
        inFlight = false;
        boolean newer = resend || !(which.equals(pendingWhich) && pendingValue == value);
        resend = false;
        if (r.ok && r.confirmedF != null && r.confirmedF == value) {
            if (!newer) pendingWhich = null;
            say("Set to " + value + "°F ✓", Ui.Status.OK);
        } else if (r.code == 429 && r.retryAfterSec > 0 && r.retryAfterSec <= 10) {
            say("Waiting " + r.retryAfterSec + " s · the bridge takes one change every 3 s", Ui.Status.WARN);
            main.removeCallbacks(send);
            main.postDelayed(send, r.retryAfterSec * 1000L);
            return;
        } else {
            if (!newer) pendingWhich = null;
            String why = r.error == null ? "no answer" : r.error;
            say(r.confirmedF != null ? "Not set · the Nest says " + r.confirmedF + "°F (" + why + ")" : "Not set · " + why, Ui.Status.BAD);
            refreshHouse();
        }
        fillThermo(Live.house());
        if (newer && pendingWhich != null) { main.removeCallbacks(send); main.postDelayed(send, DEBOUNCE_MS); }
    }

    // ---- changing the mode (OK twice: a stray press shouldn't switch the house off) ----------------

    private void chooseMode(String key) {
        Live.House h = Live.house();
        if (!h.canControl()) { say("Nest not answering · can't change it now", Ui.Status.BAD); return; }
        if (inFlight || pendingWhich != null) { say("One moment · still setting the temperature", Ui.Status.WARN); return; }
        if (key.equals(h.modeKey)) { say("Already " + label(key), Ui.Status.IDLE); return; }
        long now = System.currentTimeMillis();
        if (!key.equals(armedMode) || now - armedAt > ARM_MS) {
            armedMode = key;
            armedAt = now;
            say("Press OK again to switch to " + label(key), Ui.Status.WARN);
            return;
        }
        armedMode = null;
        inFlight = true;
        say("Switching to " + label(key) + "…", Ui.Status.WARN);
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            JSONObject b = new JSONObject();
            try { b.put("mode", key); } catch (Exception ignored) {}
            Live.HouseChange r = Live.changeHouseBlocking(app, "mode", b);
            main.post(() -> {
                inFlight = false;
                if (r.ok && key.equals(r.confirmedMode)) say("Mode " + label(key) + " ✓", Ui.Status.OK);
                else { say("Mode not changed · " + (r.error == null ? "no answer" : r.error), Ui.Status.BAD); refreshHouse(); }
                fillThermo(Live.house());
            });
        }, "mills-nest-mode").start();
    }

    private static String label(String key) { return "Auto".equals(key) ? "Heat · Cool" : key; }

    @Override
    protected void onPause() {
        super.onPause();
        // leaving with a press still waiting out the debounce: send it, it's what was asked for
        if (pendingWhich != null && !inFlight) { main.removeCallbacks(send); sendPending(); }
    }

    // ---- reading ----------------------------------------------------------------------------------

    @Override
    protected void refresh() {
        new Thread(() -> {
            Live.Weather w = Live.fetchWeatherBlocking(getApplicationContext());
            Live.fetchHouseBlocking(getApplicationContext());
            Live.Budget b = Live.fetchBudgetBlocking(getApplicationContext());
            main.post(() -> { fill(w); fillBudget(b); });
        }, "mills-house").start();
        fill(Live.weather());
    }

    private void refreshHouse() {
        new Thread(() -> { Live.fetchHouseBlocking(getApplicationContext()); main.post(() -> fillThermo(Live.house())); }, "mills-house").start();
    }

    private void fill(Live.Weather w) {
        fillThermo(Live.house());

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
        outdoor.addView(row, Ui.lpTop(this, -2, -2, 4));
        if (w.riseMin >= 0) {
            outdoor.addView(Ui.kv(this, t, "Sunrise · sunset", hm(w.riseMin) + " · " + hm(w.setMin), 0, 10), Ui.lpTop(this, -1, -2, 4));
        }
        outdoor.addView(Ui.text(this, w.has() ? "Open-Meteo · updated " + Live.ago(w.at) : "Weather service not answering",
                8.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 3));
    }

    private View budget() {
        budgetCard = glass(14);
        fillBudget(Live.budget());
        return budgetCard;
    }

    /** Budget bars (.brow): label · track · amount, one gold ramp, scaled to the largest bill. */
    private void fillBudget(Live.Budget b) {
        LinearLayout card = budgetCard;
        card.removeAllViews();
        LinearLayout head = Ui.hbox(this);
        head.setGravity(Gravity.BOTTOM);
        LinearLayout left = Ui.vbox(this);
        String month = new java.text.SimpleDateFormat("MMMM", Locale.US).format(new java.util.Date());
        left.addView(Ui.eyebrow(this, t, "Monthly housing · " + month));
        boolean has = !b.items.isEmpty();
        left.addView(Ui.text(this, has ? money(b.total) : "—", 20, t.text, 500), Ui.lpTop(this, -2, -2, 4));
        head.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout right = Ui.vbox(this);
        right.setGravity(Gravity.END);
        // bills on file, not a plan: grey, never a green dot for "on budget" that nothing proves
        String chip = !has ? (b.at == 0 ? "Hub not answering" : b.why.isEmpty() ? "No bills on file" : b.why)
                : b.ok ? "from your bills" : "last reading " + Live.ago(b.at);
        right.addView(Ui.chip(this, t, Ui.Status.IDLE, chip), Ui.lpTop(this, -2, Ui.dp(this, 20), 0));
        head.addView(right);
        card.addView(head, Ui.lp(-1, -2));

        double max = 0;
        for (double a : b.amounts) max = Math.max(max, a);
        int n = b.items.size();
        for (int i = 0; i < n; i++) {
            LinearLayout r = Ui.hbox(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout lab = Ui.vbox(this);
            lab.addView(Ui.single(Ui.text(this, b.items.get(i)[0], 10, t.text, 400)));
            lab.addView(Ui.single(Ui.text(this, b.items.get(i)[1], 7.5f, t.dim, 300)));
            r.addView(lab, new LinearLayout.LayoutParams(Ui.dp(this, 132), -2));
            FrameLayout track = new FrameLayout(this);
            track.setBackground(Ui.pill(this, t.light ? 0x140B0F14 : 0xFF141C25));
            View fill = new View(this);
            // one gold ramp, light to dark, biggest cost longest (rules §2)
            float k = max > 0 ? (float) (b.amounts.get(i) / max) : 0;
            int gold = blend(0xFFF0C25A, 0xFFC98A2E, n > 1 ? i / (float) (n - 1) : 0);
            fill.setBackground(Ui.pill(this, gold));
            track.addView(fill, new FrameLayout.LayoutParams(0, -1));
            final View f = fill; final float kk = k;
            track.post(() -> { f.getLayoutParams().width = Math.max(Ui.dp(this, 6), Math.round(track.getWidth() * kk)); f.requestLayout(); });
            r.addView(track, new LinearLayout.LayoutParams(0, Ui.dp(this, 7), 1f));
            TextView amt = Ui.text(this, money(b.amounts.get(i)), 10, t.dim, 400);
            amt.setGravity(Gravity.END);
            r.addView(amt, new LinearLayout.LayoutParams(Ui.dp(this, 72), -2));
            card.addView(r, Ui.lpTop(this, -1, -2, i == 0 ? 9 : 4));
        }
        if (has) card.addView(Ui.text(this, "Bills on Hub · updated " + new java.text.SimpleDateFormat("MMM d", Locale.US).format(new java.util.Date(b.updated))
                + " · one-time setup charges left out", 8f, t.dim, 300), Ui.lpTop(this, -1, -2, 8));
    }

    private static int blend(int a, int b, float k) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * k);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * k);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static String money(double v) { return String.format(Locale.US, "$%,.2f", v); }

    private static String hm(int min) {
        int h = min / 60, m = min % 60;
        return String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "am" : "pm");
    }
}
