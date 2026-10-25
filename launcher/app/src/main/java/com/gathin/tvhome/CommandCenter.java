package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The command center (blueprint A3 / B3): five summary cards that rise when DOWN is pressed on
 * the dock. Each is the summary of one full view; OK opens it, UP or BACK returns home.
 * Every value is read live; anything that isn't connected yet says so on the card.
 */
final class CommandCenter {

    interface Host { void open(Intent i); void close(); }

    private final Context c;
    private final Host host;
    final FrameLayout layer;
    final LinearLayout panel;
    final Plate[] cards = new Plate[5];
    private Ui.T t;

    CommandCenter(Context c, FrameLayout layer, Host host) {
        this.c = c;
        this.layer = layer;
        this.host = host;
        this.panel = Ui.vbox(c);
        panel.setClipChildren(false);
    }

    /** (Re)build the cards for the current theme and the latest readings. */
    void build(Ui.T t) {
        this.t = t;
        layer.removeAllViews();
        panel.removeAllViews();

        // "↑ UP RETURNS HOME", top-right, dim
        LinearLayout hint = Ui.hbox(c);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        TextView arrow = Ui.text(c, "↑", 8, t.dim, 600);
        arrow.setGravity(Gravity.CENTER);
        arrow.setBackground(ring(t.dim));
        int s = Ui.dp(c, 17);
        hint.addView(arrow, new LinearLayout.LayoutParams(s, s));
        TextView ht = Ui.text(c, "UP RETURNS HOME", 9, t.dim, 600);
        ht.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams hp = Ui.lp(-2, -2); hp.leftMargin = Ui.dp(c, 7);
        hint.addView(ht, hp);
        layer.addView(hint, Ui.at(c, Gravity.TOP | Gravity.END, 0, 60, 64, 0));

        // title row
        LinearLayout ttl = Ui.hbox(c);
        ttl.setGravity(Gravity.BOTTOM);
        TextView h = Ui.text(c, "COMMAND CENTER", 9, t.dim, 600);
        h.setLetterSpacing(0.22f);
        ttl.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        ttl.addView(Ui.text(c, "OK opens the full view", 9, (t.dim & 0x00FFFFFF) | 0xB3000000, 400));
        panel.addView(ttl, Ui.lp(-1, -2));

        LinearLayout row = Ui.hbox(c);
        row.setClipChildren(false);
        String[] names = {"Mission Control", "House", "Cameras", "Network", "System"};
        Class<?>[] targets = {MissionControlActivity.class, HouseActivity.class, CamerasActivity.class, NetworkActivity.class, SystemActivity.class};
        for (int i = 0; i < 5; i++) {
            Plate p = Plate.of(c, t, Plate.Kind.CARD);
            p.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(c, 16);
            p.setPadding(pad, pad, pad, pad);
            p.setContentDescription(names[i]);
            final Class<?> target = targets[i];
            p.setOnClickListener(v -> host.open(new Intent(c, target)));
            final int idx = i;
            p.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { host.close(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;                        // nothing below
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && idx == 0) return true;            // never wrap
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && idx == 4) return true;
                return false;
            });
            p.setMinimumHeight(Ui.dp(c, 160));             // all five align: the row takes the tallest
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(c, 14);
            row.addView(p, lp);
            cards[i] = p;
        }
        panel.addView(row, Ui.lpTop(c, -1, -2, 13));

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        pl.leftMargin = Ui.dp(c, 64); pl.rightMargin = Ui.dp(c, 64); pl.bottomMargin = Ui.dp(c, 54);
        layer.addView(panel, pl);
        refresh();
    }

    /** The team line without its leading "N up" (the card's big number already says it). */
    private static String afterUp(String s) {
        String r = s.replaceFirst("^\\d+ up( · )?", "");
        return r.isEmpty() ? "all up" : r;
    }

    /** Fill the cards from the latest readings (cheap; network data comes from Live's cache). */
    void refresh() {
        if (t == null) return;
        Live.Mission m = Live.mission();
        boolean mc = m.ok && !m.stale();
        int up = m.countKind("up"), part = m.countKind("partial"), wait = m.countKind("waiting"), down = m.count(Ui.Status.BAD);
        String team = m.teamState();
        fill(cards[0], "Mission Control",
                mc ? up + " up" : m.at > 0 ? "Stale" : "—",
                mc ? afterUp(m.summary(false)) 
                   : m.at > 0 ? "last answer " + Live.ago(m.at) : "Hub's status feed isn't answering",
                team,
                !mc ? "Not connected" : down > 0 ? down + " down" : part > 0 ? "Not 100% · " + Live.ago(m.at) : wait > 0 ? "Waiting on you · " + Live.ago(m.at) : "Connected · " + Live.ago(m.at),
                new String[][]{
                        {"Hub load", m.loadPct >= 0 ? m.loadPct + "%" : "—", ""},
                        {"Models", m.models >= 0 ? m.models + " on " + (m.host.isEmpty() ? "Hub" : m.host) : "—", ""}});

        Live.Weather w = Live.weather();
        Live.House h = Live.house();
        if (h.fresh() && h.indoorF != null) {
            fill(cards[1], "House", h.indoorF + "°",
                    (h.running == null ? "" : h.running + " · ") + h.targets(),
                    h.state(), "Nest · " + Live.ago(h.at),
                    new String[][]{{"Humidity", h.humidity != null ? h.humidity + "%" : "—", ""},
                                   {"Outdoor", w.has() ? w.tempF + "° · " + w.sky() : "—", ""}});
        } else {
            fill(cards[1], "House",
                    w.has() ? w.tempF + "°" : "—",
                    w.has() ? "outdoor · " + w.sky() : "outdoor reading unavailable",
                    Ui.Status.IDLE, h.chip(),
                    new String[][]{{"Indoor", "not connected", ""}, {"Housing budget", "on House", ""}});
        }

        Live.Cams cm = Live.cams();
        boolean cf = cm.fresh();
        int armed = 0, low = 0;
        for (Live.Cam x : cm.cams) { if (Boolean.TRUE.equals(x.armed)) armed++; if ("low".equalsIgnoreCase(x.battery)) low++; }
        Live.CamEvent ring = cm.lastRing(), lastEv = cm.events.isEmpty() ? null : cm.events.get(0);
        fill(cards[2], "Cameras",
                cm.cams.isEmpty() ? "—" : String.valueOf(cm.cams.size()),
                cm.cams.isEmpty() ? cm.chip() : (armed > 0 ? "armed" : "disarmed") + (low > 0 ? " · " + low + " low battery" : ""),
                !cf ? Ui.Status.IDLE : cm.recentRing(10 * 60_000L) != null || low > 0 ? Ui.Status.WARN : Ui.Status.OK,
                cf ? (cm.recentRing(10 * 60_000L) != null ? "Doorbell just rang" : "Blink · " + Live.ago(cm.at)) : "Not connected",
                new String[][]{{"Doorbell", ring != null ? Live.ago(ring.at) : "—", ""},
                               {"Last event", lastEv != null ? lastEv.camera + " · " + Live.ago(lastEv.at).replace(" ago", "") : "—", ""}});

        Live.Net n = Live.net(c);
        String dns = Live.privateDnsSetting(c);
        fill(cards[3], "Network",
                n.validated ? "Online" : n.online ? "Limited" : "Offline",
                n.kind() + (n.ip.equals("—") ? "" : " · " + n.ip) + (n.linkMbps > 0 ? " · " + n.linkMbps + " Mbps" : ""),
                n.validated ? Ui.Status.OK : n.online ? Ui.Status.WARN : Ui.Status.BAD,
                n.validated ? "Internet reachable" : n.online ? "No internet" : "Disconnected",
                new String[][]{{"Private DNS", dns, dns.equals("off") || dns.equals("automatic") ? "" : "t"},
                               {"Hub", m.ok ? "online" : "not answering", m.ok ? "t" : ""}});

        Live.Storage st = Live.storage();
        long[] ram = Live.ram(c);
        boolean frozen = Live.updatesFrozen(c);
        fill(cards[4], "System",
                st.pct() + "%",
                Live.gb(st.free) + " free of " + Live.gb(st.total),
                frozen ? Ui.Status.WARN : Ui.Status.OK, frozen ? "Updates frozen" : "Updates on",   // as System shows it
                new String[][]{{"RAM", Live.gb(ram[0]) + " / " + Live.gb(ram[1]), ""}, {"Uptime", Live.uptime(), ""}});
    }

    private void fill(Plate p, String eyebrow, String value, String sub, String state, String chip, String[][] rows) {
        p.removeAllViews();
        p.addView(Ui.eyebrow(c, t, eyebrow));
        TextView v = Ui.single(Ui.text(c, value, 26, t.text, 600));
        p.addView(v, Ui.lpTop(c, -2, -2, 8));
        TextView sb = Ui.single(Ui.text(c, sub, 11, t.dim, 300));
        p.addView(sb, Ui.lpTop(c, -1, -2, 5));
        LinearLayout ch = Ui.chip(c, t, state, chip);
        p.addView(ch, Ui.lpTop(c, -2, Ui.dp(c, 20), 9));
        for (String[] r : rows) {
            int col = "t".equals(r[2]) ? t.tealText : "g".equals(r[2]) ? t.goldText : 0;
            p.addView(Ui.kv(c, t, r[0], r[1], col, 10), Ui.lpTop(c, -1, -2, 6));
        }
    }

    private android.graphics.drawable.GradientDrawable ring(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setStroke(Math.max(1, Ui.dp(c, 0.75f)), color);
        return g;
    }
}
