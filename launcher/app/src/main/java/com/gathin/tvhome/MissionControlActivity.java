package com.gathin.tvhome;

import android.content.Intent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Mission Control · AI Team (blueprint A4). Every agent Mission Control reports, 4 across,
 * nothing hidden. States come straight from Hub's /api/status; when it stops answering the
 * tiles keep their last reading, greyed, with its age, never a green dot that can't be proven.
 */
public class MissionControlActivity extends Screen {

    private LinearLayout summary;
    private TextView feed;
    private GridLayout grid;
    private static String lastAgent;

    @Override protected String[] crumb() { return new String[]{"HOME", "MISSION CONTROL", "AI TEAM"}; }

    @Override
    protected void build() {
        summary = Ui.hbox(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(title("AI Team", summary), new FrameLayout.LayoutParams(-2, -2));

        feed = Ui.text(this, "", 9, t.dim, 300);
        body.addView(feed, Ui.at(this, Gravity.TOP | Gravity.END, 0, 16, 0, 0));

        grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setClipChildren(false);
        // Ten agents is three rows: they're sized to fit above the wordmark, and past that it scrolls
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        scroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, -1);
        gp.topMargin = Ui.dp(this, 72);
        body.addView(scroll, gp);
        foot("OK", "open agent", "BACK", "command center");
        render(Live.mission());
    }

    @Override
    protected void refresh() { Live.fetchMission(this, this::render); }

    private void render(Live.Mission m) {
        boolean fresh = m.ok && !m.stale();
        summary.removeAllViews();
        if (m.agents.isEmpty()) {
            summary.addView(dotWord(Ui.Status.IDLE, m.at > 0 ? "No agents reported" : "Not connected to Mission Control", 10));
        } else {
            addCount(Ui.Status.OK, m.count(Ui.Status.OK) + " up", fresh);
            addCount(Ui.Status.WARN, m.count(Ui.Status.WARN) + " partial", fresh);
            if (m.count(Ui.Status.BAD) > 0) addCount(Ui.Status.BAD, m.count(Ui.Status.BAD) + " down", fresh);
            addCount(Ui.Status.IDLE, m.count(Ui.Status.IDLE) + " not built", fresh);
        }
        feed.setText(fresh ? "Mission Control · " + m.host + " · updated " + Live.ago(m.at)
                : m.at > 0 ? "Not answering · last reading " + Live.ago(m.at) : "Mission Control not connected");

        View had = getCurrentFocus();
        String keep = had != null && had.getTag() instanceof String ? (String) had.getTag() : lastAgent;
        grid.removeAllViews();
        int w = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 128) - Ui.dp(this, 36)) / 4;
        int rows = Math.max(1, (m.agents.size() + 3) / 4);
        int room = getResources().getDisplayMetrics().heightPixels - Ui.dp(this, 84 + 62 + 72 + 8);
        int h = Math.max(Ui.dp(this, 90), Math.min(Ui.dp(this, 140), room / rows - Ui.dp(this, 12)));
        boolean compact = h < Ui.dp(this, 130);
        Plate focusMe = null;
        for (int i = 0; i < m.agents.size(); i++) {
            final Live.Agent a = m.agents.get(i);
            Plate p = tile(a, fresh, m, compact);
            final int col = i % 4, n = m.agents.size();
            final int idx = i;
            p.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) return true;            // never wrap
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && (col == 3 || idx == n - 1)) return true;
                return false;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = h;
            lp.rightMargin = col < 3 ? Ui.dp(this, 12) : 0;
            lp.bottomMargin = Ui.dp(this, 12);
            grid.addView(p, lp);
            if (focusMe == null || a.name.equals(keep)) focusMe = p;
        }
        if (focusMe != null) focusMe.requestFocus();
    }

    private void addCount(String state, String word, boolean fresh) {
        LinearLayout d = dotWord(fresh ? state : Ui.Status.IDLE, word, 10);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.rightMargin = Ui.dp(this, 15);
        summary.addView(d, lp);
    }

    /** compact: three rows on screen, so the role is one line and the age lives in the header only. */
    private Plate tile(final Live.Agent a, boolean fresh, Live.Mission m, boolean compact) {
        Plate p = Plate.of(this, t, Plate.Kind.TILE);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(Ui.dp(this, 16), Ui.dp(this, compact ? 11 : 15), Ui.dp(this, 15), Ui.dp(this, compact ? 10 : 14));
        p.setTag(a.name);
        p.onFocus((pl, has) -> { if (has) lastAgent = a.name; });
        p.setOnClickListener(v -> {
            Intent i = new Intent(this, AgentActivity.class);
            i.putExtra(AgentActivity.EXTRA_NAME, a.name);
            startActivity(i);
            overridePendingTransition(0, 0);
        });

        LinearLayout top = Ui.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        boolean planned = Ui.Status.IDLE.equals(a.state());
        TextView nm = Ui.single(Ui.text(this, a.name, 18, planned ? (t.light ? 0xC70B0F14 : 0xC7F2F4F7) : t.text, 600));
        top.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(dotWord(fresh ? a.state() : Ui.Status.IDLE, a.word(), 9.5f));
        p.addView(top, Ui.lp(-1, -2));

        // the hub's reason, in the state's colour: why it's orange or grey, not just that it is
        TextView why = Ui.single(Ui.text(this, a.label(), 9.5f, fresh ? t.stateText(a.state()) : t.dim, 500));
        p.addView(why, Ui.lpTop(this, -1, -2, 3));

        TextView role = Ui.text(this, a.note, 10, t.light ? 0xB30B0F14 : 0xB3F2F4F7, 300);
        role.setMaxLines(compact ? 1 : 2);
        role.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.addView(role, Ui.lpTop(this, -1, -2, 4));

        View spacer = new View(this);
        p.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        p.addView(Ui.single(Ui.text(this, hostLabel(a.host), 9, t.dim, 400)));
        if (!compact) p.addView(Ui.single(Ui.text(this, fresh ? "reported " + Live.ago(m.at) : "last reading " + Live.ago(m.at),
                8.5f, (t.dim & 0x00FFFFFF) | 0xB3000000, 300)), Ui.lpTop(this, -1, -2, 2));
        return p;
    }

    static String hostLabel(String host) {
        if (host == null || host.isEmpty() || host.equals("—")) return "no machine assigned";
        // Exactly as Mission Control reports it: ".10" is on the agents' own network, not this LAN.
        return host.startsWith(".") ? "host " + host : host;
    }
}
