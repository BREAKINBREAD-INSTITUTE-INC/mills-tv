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
 * A read-only screen (nothing to focus: readOnly() true) gets the breadcrumb's ‹ as a focused back
 * button, so the remote always shows where it is and OK goes back too.
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
        if (readOnly()) body.post(this::offerBack);
    }

    /** True for a screen with nothing to focus (a detail page): see offerBack. */
    protected boolean readOnly() { return false; }

    /** Nothing focusable in the body: the ‹ in the breadcrumb becomes the (focused) way back. */
    private void offerBack() {
        java.util.ArrayList<View> f = new java.util.ArrayList<>();
        body.addFocusables(f, View.FOCUS_DOWN);
        if (!f.isEmpty() || !(crumbView instanceof android.view.ViewGroup)) return;   // something to focus after all
        View back = ((android.view.ViewGroup) crumbView).getChildAt(0);
        if (back == null) return;
        // the ring turns gold and fills faintly when focused (a state list: no listener to miss)
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable(), off = new android.graphics.drawable.GradientDrawable();
        on.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        on.setStroke(Ui.dp(this, 1.5f), t.gold);
        on.setColor((t.gold & 0x00FFFFFF) | 0x33000000);
        off.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        off.setStroke(Math.max(1, Ui.dp(this, 0.5f)), t.glassEdge);
        android.graphics.drawable.StateListDrawable ring = new android.graphics.drawable.StateListDrawable();
        ring.addState(new int[]{android.R.attr.state_focused}, on);
        ring.addState(new int[]{}, off);
        back.setBackground(ring);
        back.setFocusable(true);
        back.setFocusableInTouchMode(true);
        back.setClickable(true);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> onBackPressed());
        back.setOnFocusChangeListener((v, has) -> {
            float sc = has ? 1.25f : 1f;
            v.animate().scaleX(sc).scaleY(sc).setDuration(Motion.ms(this, 160)).start();
        });
        back.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ThemeManager.isLight(this) != t.light || (Prefs.look(this) == Prefs.LOOK_GLASS) != t.glassLook) { recreate(); return; }
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
