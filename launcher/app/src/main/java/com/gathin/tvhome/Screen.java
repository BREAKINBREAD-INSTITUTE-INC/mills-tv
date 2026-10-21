package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Base for every view behind the command center: the current wallpaper under a 62% dim (D2:
 * panels use the glass tint over the dim, no frost), breadcrumb top-left, small clock top-right,
 * wordmark bottom-left, foot hints bottom-right. Back returns to the command center it came from.
 */
abstract class Screen extends Activity {

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(ThemeManager.wrap(base)); }

    protected Ui.T t;
    protected FrameLayout root;
    protected FrameLayout body;
    protected final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout footRow;
    private View crumbView;

    /** Swap the breadcrumb when a screen moves between its own pages (Settings). */
    protected void setCrumb(String... parts) {
        int i = root.indexOfChild(crumbView);
        android.view.ViewGroup.LayoutParams lp = crumbView.getLayoutParams();
        root.removeView(crumbView);
        crumbView = Ui.crumb(this, t, parts);
        root.addView(crumbView, i, lp);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = Ui.T.of(this);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackgroundResource(Wallpapers.drawableFor(Prefs.wallpaper(this), t.light));
        View dim = new View(this);
        dim.setBackgroundColor(t.dimmer);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        crumbView = Ui.crumb(this, t, crumb());
        root.addView(crumbView, Ui.at(this, Gravity.TOP | Gravity.START, 64, 38, 0, 0));
        root.addView(Ui.smallClock(this, t), Ui.at(this, Gravity.TOP | Gravity.END, 0, 36, 64, 0));
        root.addView(Ui.mark(this, t), Ui.at(this, Gravity.BOTTOM | Gravity.START, 64, 0, 0, 33));
        footRow = Ui.hbox(this);
        root.addView(footRow, Ui.at(this, Gravity.BOTTOM | Gravity.END, 0, 0, 64, 33));

        body = new FrameLayout(this);
        body.setClipChildren(false);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -1);
        bp.leftMargin = Ui.dp(this, 64); bp.rightMargin = Ui.dp(this, 64);
        bp.topMargin = Ui.dp(this, 84); bp.bottomMargin = Ui.dp(this, 62);
        root.addView(body, bp);
        setContentView(root);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ThemeManager.isLight(this) != t.light) { recreate(); return; }
        refresh();
        main.removeCallbacks(tick);
        main.postDelayed(tick, 30_000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); main.postDelayed(this, 30_000L); }
    };

    @Override public void onBackPressed() { finish(); overridePendingTransition(0, 0); }

    protected void foot(String... keyThenLabel) {
        footRow.removeAllViews();
        footRow.addView(Ui.foot(this, t, keyThenLabel));
    }

    /** Page title (Headline 28sp 300) with an optional summary line under it. */
    protected LinearLayout title(String title, View summary) {
        LinearLayout box = Ui.vbox(this);
        box.addView(Ui.text(this, title, 28, t.text, 300));
        if (summary != null) box.addView(summary, Ui.lpTop(this, -2, -2, 6));
        return box;
    }

    /** A dot + word, the one way status is shown. */
    protected LinearLayout dotWord(String state, String word, float sp) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.dot(this, t.state(state), sp * 0.55f));
        TextView w = Ui.text(this, word, sp, t.stateText(state), 500);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2); lp.leftMargin = Ui.dp(this, 6);
        row.addView(w, lp);
        return row;
    }

    /** A static glass panel (no frost over the dim, D2). */
    protected LinearLayout glass(float padDp) {
        LinearLayout p = Ui.vbox(this);
        p.setBackground(Ui.round(this, t.glass, Ui.R_CARD, t.glassEdge));
        int pd = Ui.dp(this, padDp);
        p.setPadding(pd, pd, pd, pd);
        return p;
    }

    /** "Concept · sample figures" style tag for anything not wired to real data yet. */
    protected TextView sampleTag(String s) {
        TextView v = Ui.text(this, s.toUpperCase(java.util.Locale.US), 7.5f, t.goldText, 600);
        v.setLetterSpacing(0.14f);
        return v;
    }

    protected abstract String[] crumb();
    protected abstract void build();
    protected void refresh() {}
}
