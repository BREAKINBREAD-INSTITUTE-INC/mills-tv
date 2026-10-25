package com.gathin.tvhome;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One agent (blueprint A4b): the reading Mission Control reports for it, the five controls, and
 * the health of the feed itself. The controls fire nothing until Hub serves the control API
 * (today it answers 501): they render at 45% with "needs connection", and pressing one says
 * so; nothing on this page claims to have acted. "Open full Mission Control" works: it opens
 * Hub's own dashboard in the TV's browser.
 */
public class AgentActivity extends Screen {

    static final String EXTRA_NAME = "agent";
    private String name;
    private LinearLayout hero, side;
    private TextView note;
    private boolean controls;          // the control API answered
    private Plate[] buttons = new Plate[5];

    @Override
    protected String[] crumb() {
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (name == null) name = "AGENT";
        return new String[]{"MISSION CONTROL", "AI TEAM", name.toUpperCase(java.util.Locale.US)};
    }

    @Override
    protected void build() {
        LinearLayout cols = Ui.hbox(this);
        hero = Ui.vbox(this);
        hero.setClipChildren(false);
        cols.addView(hero, new LinearLayout.LayoutParams(0, -1, 1f));
        side = Ui.vbox(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 320), -2);
        sp.leftMargin = Ui.dp(this, 24);
        cols.addView(side, sp);
        cols.setClipChildren(false);
        body.addView(cols, new FrameLayout.LayoutParams(-1, -1));
        foot("◀ ▶", "controls", "BACK", "roster");
        render(Live.mission());
        Live.probeControls(this, ok -> { controls = ok; render(Live.mission()); });
    }

    @Override
    protected void refresh() { Live.fetchMission(this, this::render); }

    private void render(Live.Mission m) {
        Live.Agent a = m.find(name);
        boolean fresh = m.ok && !m.stale();
        int focused = -1;
        for (int i = 0; i < buttons.length; i++) if (buttons[i] != null && buttons[i].isFocused()) focused = i;
        hero.removeAllViews();
        side.removeAllViews();

        TextView nm = Ui.text(this, name, 56, t.text, 600);
        nm.setLetterSpacing(-0.01f);
        hero.addView(nm);
        hero.addView(Ui.text(this, a != null ? a.note : "not in Mission Control's roster", 13,
                t.light ? 0xBF0B0F14 : 0xBFF2F4F7, 300), Ui.lpTop(this, -2, -2, 6));

        LinearLayout big = Ui.hbox(this);
        big.setGravity(Gravity.CENTER_VERTICAL);
        String state = a != null && fresh ? a.state() : Ui.Status.IDLE;
        big.addView(Ui.dot(this, t.state(state), 8));
        TextView bl = Ui.text(this, a != null ? a.label() : "Unknown", 20, t.text, 300);
        LinearLayout.LayoutParams blp = Ui.lp(-2, -2); blp.leftMargin = Ui.dp(this, 8);
        big.addView(bl, blp);
        hero.addView(big, Ui.lpTop(this, -2, -2, 14));
        // on its own line: beside a long reason ("Up · machine and model answering") it wrapped in two
        TextView em = Ui.text(this, fresh ? "as reported by Mission Control" : "last reading " + Live.ago(m.at),
                11, fresh ? t.goldText : t.dim, 400);
        LinearLayout.LayoutParams emp = Ui.lpTop(this, -2, -2, 3);
        emp.leftMargin = Ui.dp(this, 16);
        hero.addView(em, emp);

        // facts, 3 across
        GridLayout facts = new GridLayout(this);
        facts.setColumnCount(3);
        // Only what is true of this agent: its machine, the model that serves it (its own model key on
        // the hub, never the dashboard host's load or Ollama list), that model server's check, cloud.
        fact(facts, "MACHINE", a == null ? "—" : !a.machineLabel.isEmpty() ? a.machineLabel : MissionControlActivity.hostLabel(a.host));
        fact(facts, "SERVED BY", a == null || a.servedBy.isEmpty() ? "—" : a.servedBy);
        fact(facts, "MODEL SERVER", a == null || a.servedWhy.isEmpty() ? "not checked" : a.servedWhy);
        fact(facts, "LAST UPDATE", m.at > 0 ? Live.ago(m.at) : "never");
        fact(facts, "CLOUD", a != null && "off".equals(a.cloud) ? "Local only (off by design)" : "not reported");
        fact(facts, "USAGE TODAY", "not reported");
        hero.addView(facts, Ui.lpTop(this, -2, -2, 10));

        // the five controls
        LinearLayout ctrls = Ui.vbox(this);
        ctrls.setClipChildren(false);
        LinearLayout row1 = Ui.hbox(this), row2 = Ui.hbox(this);
        row1.setClipChildren(false); row2.setClipChildren(false);
        ctrls.addView(row1, Ui.lp(-2, -2));   // wrap, or it shrinks to the shorter second row
        ctrls.addView(row2, Ui.lpTop(this, -2, -2, 12));
        String[] labels = {"Approve & take live", "Pause", "Restart", "View logs", "Open full Mission Control"};
        boolean browser = getPackageManager().resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + Prefs.hub(this) + ":8777/")), 0) != null;
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean works = i == 4 ? browser : controls;
            Plate b = Plate.of(this, t, Plate.Kind.BUTTON);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(Ui.dp(this, 15), 0, Ui.dp(this, 15), 0);
            b.addView(Ui.text(this, labels[i], 11.5f, t.text, 500));
            b.setDisconnected(!works);
            b.setOnClickListener(v -> press(idx));
            b.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && (idx == 0 || idx == 3)) return true;      // never wrap
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && (idx == 2 || idx == 4)) return true;
                if (key == KeyEvent.KEYCODE_DPAD_UP && idx < 3) return true;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && idx >= 3) return true;
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 36));
            if (i != 0 && i != 3) lp.leftMargin = Ui.dp(this, 8);
            (i < 3 ? row1 : row2).addView(b, lp);
            buttons[i] = b;
        }
        if (!controls) {
            TextView nc = Ui.text(this, "CONTROLS NEED CONNECTION", 7.5f, t.goldText, 600);
            nc.setLetterSpacing(0.14f);
            hero.addView(nc, Ui.lpTop(this, -2, -2, 16));
        }
        hero.addView(ctrls, Ui.lpTop(this, -2, -2, controls ? 26 : 8));

        note = Ui.text(this, controls
                ? "Pause and Restart ask for a second OK."
                : "These controls send nothing yet (Hub's control API answers 501); approvals happen on your phone."
                  + (browser ? "" : " No browser on this TV: open " + Prefs.hub(this) + ":8777 on your phone."),
                9, t.light ? 0xE60B0F14 : 0xBF9AA6B2, 300);   // dark on Daylight: the dim grey sank into the photo
        note.setMaxWidth(Ui.dp(this, 500));
        note.setLineSpacing(0, 1.3f);
        hero.addView(note, Ui.lpTop(this, -2, -2, 10));

        // side: recent activity + health
        LinearLayout act = glass(16);
        act.addView(panelHead("RECENT ACTIVITY", controls ? "" : "not connected"));
        act.addView(Ui.text(this, "Mission Control doesn't publish an activity log for agents yet, so "
                + "there is nothing true to show here.", 9.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 9));
        side.addView(act, Ui.lp(-1, -2));

        LinearLayout health = glass(16);
        health.addView(panelHead("HEALTH", "last reading"));
        healthRow(health, "Mission Control API", fresh ? "connected · " + Live.ago(m.at) : m.at > 0 ? "stale · " + Live.ago(m.at) : "not connected", fresh);
        // the hub's reason in the hub's colour: green up, orange not 100%, red down, grey not built
        health.addView(Ui.kv(this, t, "Reported state", a != null ? a.label() : "not listed",
                fresh && a != null ? t.stateText(a.state()) : t.dim, 10), Ui.lpTop(this, -1, -2, 8));
        if (a != null && !a.detail.isEmpty()) {   // the latest tests, from agents.json on the hub, whole
            health.addView(Ui.text(this, "Latest tests", 10, t.dim, 300), Ui.lpTop(this, -1, -2, 8));
            TextView d = Ui.text(this, a.detail, 10, t.text, 500);
            d.setLineSpacing(0, 1.2f);
            health.addView(d, Ui.lpTop(this, -1, -2, 2));
        }
        healthRow(health, "Model server", a == null || a.servedWhy.isEmpty() ? "not checked" : a.servedWhy,
                fresh && a != null && Ui.Status.OK.equals(a.servedState));
        healthRow(health, "Agent controls", controls ? "available" : "not available (501)", controls);
        side.addView(health, Ui.lpTop(this, -1, -2, 12));

        int f = focused >= 0 ? focused : 4;
        buttons[f].requestFocus();
    }

    private void press(int idx) {
        if (idx == 4) {
            String url = "http://" + Prefs.hub(this) + ":8777/";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().resolveActivity(i, 0) == null) {
                note.setText("No web browser is installed on this TV. Open " + url + " on your phone or computer.");
                note.setTextColor(t.goldText);
                return;
            }
            try { startActivity(i); }
            catch (Exception e) { note.setText("Couldn't open a browser on this TV for " + url + "."); }
            return;
        }
        if (!controls) {
            note.setText("Needs connection: Hub doesn't serve agent controls yet, so nothing was sent.");
            note.setTextColor(t.goldText);
        }
    }

    private void fact(GridLayout g, String k, String v) {
        LinearLayout box = Ui.vbox(this);
        TextView kk = Ui.text(this, k, 7, t.dim, 600);
        kk.setLetterSpacing(0.16f);
        box.addView(kk);
        // two lines, never cut: "gpt-oss-120b · CORE (stand-in DYNAMO)" is longer than a column
        TextView vv = Ui.text(this, v, 12, t.text, 400);
        vv.setMaxLines(2);
        vv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(vv, Ui.lpTop(this, -1, -2, 3));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = Ui.dp(this, 150);           // three across fit beside the side panels (hero is ~488dp)
        lp.rightMargin = Ui.dp(this, 12);
        lp.bottomMargin = Ui.dp(this, 10);
        g.addView(box, lp);
    }

    private LinearLayout panelHead(String title, String right) {
        LinearLayout row = Ui.hbox(this);
        TextView a = Ui.text(this, title, 7, t.dim, 600);
        a.setLetterSpacing(0.16f);
        row.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(Ui.text(this, right, 7.5f, (t.dim & 0x00FFFFFF) | 0x99000000, 400));
        return row;
    }

    private void healthRow(LinearLayout p, String k, String v, boolean ok) {
        p.addView(Ui.kv(this, t, k, v, ok ? t.tealText : t.dim, 10), Ui.lpTop(this, -1, -2, 8));
    }
}
