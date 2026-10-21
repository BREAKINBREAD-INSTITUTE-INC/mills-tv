package com.gathin.tvhome;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The compact status strip (the "status strip drafts"), opt-in from Settings > Layout; the calm
 * home keeps it off. Three cards: AI team (every agent, dot + name), House (the Nest, read over
 * Matter by the bridge on Hub), Network (one-line health). Three styles: A one tight line,
 * B status chips, C ultra minimal. Every value is live; anything not connected says so.
 */
final class StatusStrip {
    private StatusStrip() {}

    static void bind(Context c, Ui.T t, FrameLayout host) {
        int style = Prefs.strip(c);
        host.removeAllViews();
        if (style == Prefs.STRIP_OFF) { host.setVisibility(View.GONE); return; }
        host.setVisibility(View.VISIBLE);

        LinearLayout row = Ui.hbox(c);
        row.setBaselineAligned(false);
        Live.Mission m = Live.mission();
        boolean fresh = m.ok && !m.stale();

        // AI team
        LinearLayout ai = card(c, t, "AI team");
        Flow names = new Flow(c, Ui.dp(c, style == Prefs.STRIP_CHIPS ? 4 : 9), Ui.dp(c, 4));
        if (m.agents.isEmpty()) {
            names.addView(dotName(c, t, Ui.Status.IDLE, m.at > 0 ? "no agents reported" : "not connected", style));
        } else for (Live.Agent a : m.agents) {
            String st = fresh ? a.state() : Ui.Status.IDLE;
            names.addView(style == Prefs.STRIP_CHIPS ? Ui.chip(c, t, st, a.name) : dotName(c, t, st, a.name, style));
        }
        if (style == Prefs.STRIP_MINIMAL) names.singleLine = true;
        ai.addView(names, Ui.lpTop(c, -1, -2, style == Prefs.STRIP_MINIMAL ? 5 : 7));
        row.addView(ai, new LinearLayout.LayoutParams(0, -1, 1.35f));

        // House
        Live.House h = Live.house();
        LinearLayout house = card(c, t, style == Prefs.STRIP_MINIMAL ? "House" : "House · indoor");
        LinearLayout hv = Ui.hbox(c);
        hv.setGravity(Gravity.BOTTOM);
        hv.addView(Ui.text(c, h.fresh() && h.indoorF != null ? h.indoorF + "°" : "—", 20, h.fresh() ? t.text : t.dim, 300));
        String sub = h.fresh() ? (h.humidity != null ? (style == Prefs.STRIP_LINE ? h.humidity + "% humidity" : (style == Prefs.STRIP_CHIPS ? "· " : "") + h.humidity + "%")
                : h.running != null ? h.running : "") : h.chip();
        TextView sv = Ui.single(Ui.text(c, sub, 10, t.dim, 300));
        LinearLayout.LayoutParams sp = Ui.lp(-2, -2);
        sp.leftMargin = Ui.dp(c, 8);
        sp.bottomMargin = Ui.dp(c, 3);
        hv.addView(sv, sp);
        house.addView(hv, Ui.lpTop(c, -2, -2, 4));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -1, 1f);
        hp.leftMargin = Ui.dp(c, 6);
        row.addView(house, hp);

        // Network
        Live.Net n = Live.net(c);
        LinearLayout net = card(c, t, "Network");
        LinearLayout nv = Ui.hbox(c);
        nv.setGravity(Gravity.CENTER_VERTICAL);
        String ns = n.validated ? Ui.Status.OK : n.online ? Ui.Status.WARN : Ui.Status.BAD;
        nv.addView(Ui.dot(c, t.state(ns), 5));
        TextView nw = Ui.text(c, n.validated ? "All clear" : n.online ? "No internet" : "Offline", 15, t.text, 400);
        LinearLayout.LayoutParams np = Ui.lp(-2, -2);
        np.leftMargin = Ui.dp(c, 7);
        nv.addView(nw, np);
        if (style == Prefs.STRIP_CHIPS) nv.addView(Ui.text(c, "  · " + n.kind(), 10, t.dim, 300));
        net.addView(nv, Ui.lpTop(c, -2, -2, 6));
        LinearLayout.LayoutParams netp = new LinearLayout.LayoutParams(0, -1, 1f);
        netp.leftMargin = Ui.dp(c, 6);
        row.addView(net, netp);

        host.addView(row, new FrameLayout.LayoutParams(-1, -2));
    }

    private static LinearLayout card(Context c, Ui.T t, String eyebrow) {
        LinearLayout p = Ui.vbox(c);
        p.setBackground(Ui.round(c, t.panel, 10, t.light ? t.glassEdge : t.edge));
        p.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        p.addView(Ui.eyebrow(c, t, eyebrow));
        return p;
    }

    private static LinearLayout dotName(Context c, Ui.T t, String state, String name, int style) {
        LinearLayout l = Ui.hbox(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(Ui.dot(c, t.state(state), style == Prefs.STRIP_MINIMAL ? 4 : 5));
        TextView v = Ui.text(c, name, style == Prefs.STRIP_MINIMAL ? 7.5f : 8.5f, Ui.Status.IDLE.equals(state) ? t.dim : t.text, 600);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.leftMargin = Ui.dp(c, 4);
        l.addView(v, lp);
        return l;
    }

    /** A minimal wrapping row: children left to right, next line when the width runs out. */
    static final class Flow extends ViewGroup {
        final int hGap, vGap;
        boolean singleLine;
        Flow(Context c, int hGap, int vGap) { super(c); this.hGap = hGap; this.vGap = vGap; }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int max = MeasureSpec.getSize(wSpec), x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                int w = ch.getMeasuredWidth();
                if (x > 0 && x + w > max) {
                    if (singleLine) { ch.setVisibility(GONE); continue; }
                    x = 0; y += lineH + vGap; lineH = 0;
                }
                ch.setVisibility(VISIBLE);
                x += w + hGap;
                lineH = Math.max(lineH, ch.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + lineH);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > max) { x = 0; y += lineH + vGap; lineH = 0; }
                ch.layout(x, y, x + w, y + h);
                x += w + hGap;
                lineH = Math.max(lineH, h);
            }
        }
    }
}
