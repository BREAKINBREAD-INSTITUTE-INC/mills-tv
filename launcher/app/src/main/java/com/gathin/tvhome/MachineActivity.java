package com.gathin.tvhome;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One machine from the AI Team's Machines row: a Spark or NAS. What the hub checked on the
 * left (state, role, what it runs, the tailnet, the fixed facts); on the right who uses it and
 * what stands in for it, or for NAS what's kept there. Read-only: nothing here restarts,
 * switches or deletes anything. When the hub stops answering it keeps the last reading, greyed.
 */
public class MachineActivity extends Screen {

    static final String EXTRA = "machine";

    private String name;
    private LinearLayout left, right;

    @Override
    protected String[] crumb() {
        name = getIntent().getStringExtra(EXTRA);
        if (name == null) name = "Machine";
        return new String[]{"HOME", "AI TEAM", name.toUpperCase(Locale.US)};
    }

    @Override
    protected void build() {
        LinearLayout cols = Ui.hbox(this);
        cols.setClipChildren(false);
        left = Ui.vbox(this);
        cols.addView(left, new LinearLayout.LayoutParams(Ui.dp(this, 352), -1));
        right = Ui.vbox(this);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1f);
        rp.leftMargin = Ui.dp(this, 28);
        cols.addView(right, rp);
        body.addView(cols, new FrameLayout.LayoutParams(-1, -1));
        foot("BACK", "ai team");
        render(Live.mission());
    }

    @Override
    protected void refresh() { Live.fetchMission(this, this::render); }

    @Override protected boolean readOnly() { return true; }

    private void render(Live.Mission m) {
        Live.Machine x = m.machine(name);
        boolean fresh = m.ok && !m.stale();
        left.removeAllViews();
        right.removeAllViews();

        left.addView(Ui.single(Ui.text(this, name, 22, t.text, 300)));
        if (x == null) {
            left.addView(dotWord(Ui.Status.IDLE, m.at > 0 ? "Not in the hub's list" : "No answer from the hub", 10.5f),
                    Ui.lpTop(this, -2, -2, 6));
            return;
        }
        left.addView(dotWord(fresh ? x.state() : Ui.Status.IDLE, x.why.isEmpty() ? "No reading" : x.why, 10.5f),
                Ui.lpTop(this, -2, -2, 6));
        TextView role = Ui.text(this, x.role, 11, t.light ? 0xBF0B0F14 : 0xBFF2F4F7, 300);
        role.setMaxLines(2);
        left.addView(role, Ui.lpTop(this, -1, -2, 8));
        left.addView(Ui.text(this, fresh ? "checked by " + m.host + " · " + Live.ago(m.at)
                : m.at > 0 ? "hub not answering · last reading " + Live.ago(m.at) : "hub not connected", 8.5f, t.dim, 300),
                Ui.lpTop(this, -1, -2, 5));

        LinearLayout facts = Ui.vbox(this);
        left.addView(facts, Ui.lpTop(this, -1, -2, 14));
        if (x.nas()) nasLeft(facts, x, fresh); else sparkLeft(facts, x, fresh);

        if (x.nas()) nasRight(x); else sparkRight(x, m, fresh);
    }

    // ---- a Spark --------------------------------------------------------------------------------

    private void sparkLeft(LinearLayout facts, Live.Machine x, boolean fresh) {
        fact(facts, "Model", x.model.isEmpty() ? "—" : x.model, 0, 0);
        fact(facts, "Engine", x.engine.isEmpty() ? "—" : x.engine, 0, 5);
        String tn; int tc;
        if (x.online == null) { tn = "not reported"; tc = t.dim; }
        else if (x.online) { tn = "online now"; tc = fresh ? t.stateText(Ui.Status.OK) : t.dim; }
        else { tn = x.lastSeen > 0 ? "offline · last seen " + when(x.lastSeen) : "never seen"; tc = fresh ? t.stateText(x.lastSeen > 0 ? Ui.Status.BAD : Ui.Status.IDLE) : t.dim; }
        fact(facts, "Tailnet", tn, tc, 5);
        if (!x.speed.isEmpty()) fact(facts, "Last speed", x.speed + (x.speedAt.isEmpty() ? "" : " · " + x.speedAt), 0, 5);
        for (String[] f : x.facts) {
            boolean pending = f[1].contains("pending") || f[1].contains("behind");
            fact(facts, f[0], f[1], pending ? t.orangeText : 0, 5);
        }
    }

    private void sparkRight(Live.Machine x, Live.Mission m, boolean fresh) {
        right.addView(Ui.eyebrow(this, t, "Agents on " + x.name), Ui.lpTop(this, -1, -2, 8));
        LinearLayout list = glass(14);
        if (x.agents.isEmpty()) {
            list.addView(Ui.text(this, "No agent runs on " + x.name + " today.", 10, t.dim, 300));
        } else {
            for (int i = 0; i < x.agents.size(); i++) {
                String an = x.agents.get(i);
                Live.Agent a = m.find(an);
                LinearLayout r = Ui.hbox(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                String st = a != null && fresh ? a.state() : Ui.Status.IDLE;
                r.addView(Ui.dot(this, t.state(st), 5.5f));
                TextView nm = Ui.text(this, an, 12, t.text, 600);
                LinearLayout.LayoutParams np = Ui.lp(-2, -2); np.leftMargin = Ui.dp(this, 9);
                r.addView(nm, np);
                TextView why = Ui.single(Ui.text(this, a != null ? a.label() : "not in the roster", 9.5f, t.stateText(st), 500));
                why.setGravity(Gravity.END);
                LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f); wp.leftMargin = Ui.dp(this, 12);
                r.addView(why, wp);
                list.addView(r, i == 0 ? Ui.lp(-1, -2) : Ui.lpTop(this, -1, -2, 9));
                if (a != null && !a.note.isEmpty())
                    list.addView(Ui.single(Ui.text(this, a.note, 9, t.dim, 300)), Ui.lpTop(this, -1, -2, 3));
            }
        }
        right.addView(list, Ui.lpTop(this, -1, -2, 8));

        right.addView(Ui.eyebrow(this, t, "Stand-in"), Ui.lpTop(this, -1, -2, 16));
        LinearLayout si = glass(14);
        boolean none = x.standIn.isEmpty() || x.standIn.toLowerCase(Locale.US).startsWith("no stand-in");
        TextView s = Ui.text(this, x.standIn.isEmpty() ? "Not recorded" : x.standIn, 11, none ? t.orangeText : t.text, 400);
        s.setLineSpacing(0, 1.2f);
        si.addView(s);
        si.addView(Ui.text(this, "from the Spark evaluation, Oct 22 · switching is by hand today", 8.5f, t.dim, 300),
                Ui.lpTop(this, -1, -2, 6));
        right.addView(si, Ui.lpTop(this, -1, -2, 8));
    }

    // ---- NAS -----------------------------------------------------------------------------

    private void nasLeft(LinearLayout facts, Live.Machine x, boolean fresh) {
        double[] sp = x.space();
        fact(facts, "Free", sp == null ? "not mounted on HUB" : fmt(sp[0]) + " TB of " + fmt(sp[1]) + " TB", 0, 0);
        if (sp != null) {
            float used = (float) Math.max(0, Math.min(1, 1 - sp[0] / sp[1]));
            LinearLayout meter = Ui.hbox(this);
            meter.setBackground(Ui.pill(this, t.light ? 0x1F0B0F14 : 0x24F2F4F7));
            View u = new View(this);
            u.setBackground(Ui.pill(this, fresh ? t.state(x.state()) : t.dim));
            meter.addView(u, new LinearLayout.LayoutParams(0, -1, used));
            meter.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1 - used));
            facts.addView(meter, Ui.lpTop(this, -1, Ui.dp(this, 5), 6));
            facts.addView(Ui.text(this, Math.round(used * 100) + "% used", 8.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 4));
        }
        fact(facts, "Address", x.ip.isEmpty() ? "—" : x.ip, 0, 8);
        fact(facts, "Box", x.engine.isEmpty() ? "—" : x.engine, 0, 5);
        if (!x.note.isEmpty()) {   // the one thing JJ must not forget about this box
            LinearLayout warn = Ui.chip(this, t, Ui.Status.WARN, x.note);
            facts.addView(warn, Ui.lpTop(this, -2, Ui.dp(this, 22), 14));
        }
    }

    private void nasRight(Live.Machine x) {
        right.addView(Ui.eyebrow(this, t, "Kept on " + x.name), Ui.lpTop(this, -1, -2, 8));
        LinearLayout list = glass(14);
        if (x.stores.isEmpty()) {
            list.addView(Ui.text(this, "The hub can't see inside " + x.name + " right now.", 10, t.dim, 300));
        }
        for (int i = 0; i < x.stores.size(); i++) {
            Live.Stored sd = x.stores.get(i);
            LinearLayout r = Ui.hbox(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            String st = sd.present ? Ui.Status.OK : Ui.Status.IDLE;
            r.addView(Ui.dot(this, t.state(st), 5.5f));
            TextView nm = Ui.single(Ui.text(this, sd.name, 12, t.text, 500));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f); np.leftMargin = Ui.dp(this, 9);
            r.addView(nm, np);
            r.addView(Ui.text(this, detail(sd), 10, sd.present ? t.text : t.dim, 500));
            list.addView(r, i == 0 ? Ui.lp(-1, -2) : Ui.lpTop(this, -1, -2, 11));
            if (sd.present && sd.updated > 0)
                list.addView(Ui.text(this, "last change " + when(sd.updated), 8.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 3));
        }
        right.addView(list, Ui.lpTop(this, -1, -2, 8));
        right.addView(Ui.text(this, "Counted by HUB from the archive's own index and folder listings · sizes beyond the Blink archive aren't measured",
                8.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 8));
    }

    private static String detail(Live.Stored sd) {
        if (!sd.present) return "not found";
        if (sd.clips >= 0) return String.format(Locale.US, "%,d clips", sd.clips) + (sd.bytes >= 0 ? " · " + size(sd.bytes) : "");
        if (sd.items >= 0) return sd.items == 1 ? "1 item" : sd.items + " items";
        return "present";
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void fact(LinearLayout facts, String k, String v, int color, float topDp) {
        LinearLayout row = Ui.kv(this, t, k, v, color, 10);
        facts.addView(row, topDp == 0 ? Ui.lp(-1, -2) : Ui.lpTop(this, -1, -2, topDp));
    }

    private static String fmt(double tb) { return String.format(Locale.US, "%.1f", tb); }

    private static String size(long b) {
        double gb = b / 1e9;
        return gb >= 1000 ? String.format(Locale.US, "%.1f TB", gb / 1000) : gb >= 1 ? String.format(Locale.US, "%.1f GB", gb)
                : String.format(Locale.US, "%.0f MB", b / 1e6);
    }

    /** "12 min ago" within a day, then the date ("Oct 21, 3:40 PM"). */
    private static String when(long at) {
        if (System.currentTimeMillis() - at < 24 * 3600_000L) return Live.ago(at);
        return new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(at));
    }
}
