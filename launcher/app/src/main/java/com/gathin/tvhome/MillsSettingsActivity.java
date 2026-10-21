package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * MILLS TV settings (blueprint A5 / B4). Two panes: option rows on the left, and on the right the
 * real Home layout inflated at 48% showing the COMMITTED state. Focus alone changes nothing; OK
 * applies (the teal check moves, the preview re-inflates), Back leaves without applying.
 * Pages: overview · Background · Layout · Typography · Focus · Apps on Home · System.
 * Everything reads and writes the one Prefs store the home uses.
 */
public class MillsSettingsActivity extends Screen {

    static final String EXTRA_TAB = "tab";       // overview | background | layout | typography | focus | apps | system
    private static final String EXTRA_FOCUS = "focus_tag";

    private String page = "overview";
    private String returnTag;                     // overview row to land on when Back leaves a page
    private LinearLayout titleBox, left, right;
    private ScrollView leftScroll, full;
    private HomeBinder preview, previewAlt;
    private DreamScene dreamPreview;
    private final Deque<List<String>> undo = new ArrayDeque<>();
    private int liftedSlot = -1;                  // apps editor: the slot picked up, or -1
    private List<String> beforeLift;
    private int selectedSlot = 0;

    @Override protected String[] crumb() { return new String[]{"HOME", "SETTINGS"}; }

    @Override
    protected void build() {
        String tab = getIntent().getStringExtra(EXTRA_TAB);
        if (tab != null) page = tab;
        body.setClipChildren(true);      // the scrolling panes must stay inside the body

        titleBox = Ui.vbox(this);
        body.addView(titleBox, new FrameLayout.LayoutParams(-1, -2));

        leftScroll = new ScrollView(this);
        leftScroll.setVerticalScrollBarEnabled(false);
        leftScroll.setClipToPadding(false);
        leftScroll.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        left = Ui.vbox(this);
        leftScroll.addView(left);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 336), -1);
        lp.topMargin = Ui.dp(this, 62);
        body.addView(leftScroll, lp);

        right = Ui.vbox(this);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(Ui.dp(this, 464), -2);
        rp.leftMargin = Ui.dp(this, 368);
        rp.topMargin = Ui.dp(this, 66);
        body.addView(right, rp);

        full = new ScrollView(this);
        full.setVerticalScrollBarEnabled(false);
        full.setClipToPadding(false);
        full.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, -1);
        fp.topMargin = Ui.dp(this, 56);
        body.addView(full, fp);

        render(getIntent().getStringExtra(EXTRA_FOCUS));
    }

    /**
     * Coming back (e.g. from Preview now): restart the screensaver preview where focus was.
     * Otherwise read the status line and strip for the live preview, so it never sits on
     * "Checking…" / "not connected" when Settings was opened from Theater (which reads neither).
     */
    @Override
    protected void refresh() {
        if (!"screensaver".equals(page)) {
            final HomeBinder pv = preview;
            if (pv != null) HomeStatus.refresh(this, pv.status, pv.statusDot, pv.t,
                    () -> { if (pv == preview) StatusStrip.bind(this, pv.t, pv.strip); });
            return;
        }
        View f = getCurrentFocus();
        render(f != null && f.getTag() instanceof String ? (String) f.getTag() : null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dreamPreview != null) dreamPreview.stop();
    }

    @Override
    public void onBackPressed() {
        if (liftedSlot >= 0) { dropBack(); return; }
        if (!"overview".equals(page)) {
            String back = "cat:" + page;
            page = "overview";
            undo.clear();
            render(back);
            return;
        }
        super.onBackPressed();
    }

    // ---- page frame -------------------------------------------------------------------------------

    private void render(String focusTag) {
        if (dreamPreview != null) { dreamPreview.stop(); dreamPreview = null; }
        boolean apps = "apps".equals(page);
        leftScroll.setVisibility(apps ? View.GONE : View.VISIBLE);
        right.setVisibility(apps ? View.GONE : View.VISIBLE);
        full.setVisibility(apps ? View.VISIBLE : View.GONE);
        left.removeAllViews();
        right.removeAllViews();
        titleBox.removeAllViews();
        switch (page) {
            case "background": pageBackground(); break;
            case "layout":     pageLayout(); break;
            case "typography": pageTypography(); break;
            case "focus":      pageFocus(); break;
            case "glass":      pageGlass(); break;
            case "screensaver": pageScreensaver(); break;
            case "apps":       pageApps(); break;
            case "system":     pageSystem(); break;
            case "sleep":      pageSleep(); break;
            default:           page = "overview"; pageOverview(); break;
        }
        if ("screensaver".equals(page)) addDreamPreview();
        else if (!apps && !"system".equals(page) && !"sleep".equals(page)) addPreview();
        foot(apps ? new String[]{"OK", liftedSlot >= 0 ? "drop" : "pick up · add", "BACK", liftedSlot >= 0 ? "cancel move" : "settings"}
                : "overview".equals(page) ? new String[]{"OK", "open group", "BACK", "home"}
                : new String[]{"OK", "apply", "BACK", "settings"});
        final String tag = focusTag;
        body.post(() -> focus(tag));
    }

    private void focus(String tag) {
        View v = tag == null ? null : body.findViewWithTag(tag);
        if (v == null || !v.isFocusable()) v = firstFocusable(apps() ? full : left);
        if (v != null) v.requestFocus();
    }

    private boolean apps() { return "apps".equals(page); }

    private View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable() && c.getVisibility() == View.VISIBLE) return c;
            if (c instanceof ViewGroup) { View f = firstFocusable((ViewGroup) c); if (f != null) return f; }
        }
        return null;
    }

    private void heading(String title, String sub) {
        titleBox.addView(Ui.single(Ui.text(this, title, 28, t.text, 300)));
        TextView s = Ui.text(this, sub, 11, t.light ? 0x990B0F14 : 0x99F2F4F7, 300);
        titleBox.addView(s, Ui.lpTop(this, -2, -2, 4));
    }

    /** Commit happened: re-render (keeping focus), and recreate if the theme itself changed. */
    private void committed(String tag) {
        if (ThemeManager.isLight(this) != t.light) {
            Intent i = getIntent();
            i.putExtra(EXTRA_TAB, page);
            i.putExtra(EXTRA_FOCUS, tag);
            finish();
            overridePendingTransition(0, 0);
            startActivity(i);
            overridePendingTransition(0, 0);
            return;
        }
        render(tag);
    }

    // ---- rows (B4) --------------------------------------------------------------------------------

    private void group(String heading) {
        TextView g = Ui.groupHeading(this, t, heading);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.topMargin = Ui.dp(this, left.getChildCount() == 0 ? 0 : 10);
        lp.bottomMargin = Ui.dp(this, 4);
        lp.leftMargin = Ui.dp(this, 12 + 13 + 9);
        left.addView(g, lp);
    }

    private Plate row(String tag, String label, String summary, boolean applied, boolean category, View.OnClickListener ok) {
        Plate p = Plate.of(this, t, Plate.Kind.ROW);
        p.setTag(tag);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        TextView ck = Ui.text(this, applied ? "✓" : "", 11, t.teal, 700);
        ck.setGravity(Gravity.CENTER);
        if (!category) p.addView(ck, new LinearLayout.LayoutParams(Ui.dp(this, 13), -2));
        TextView l = Ui.single(Ui.text(this, label, 13, t.text, 400));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -2, 1f);
        ll.leftMargin = category ? 0 : Ui.dp(this, 9);
        p.addView(l, ll);
        if (summary != null) {
            TextView s = Ui.single(Ui.text(this, summary, 10, t.dim, 300));
            s.setMaxWidth(Ui.dp(this, 190));
            p.addView(s);
        }
        if (category) {
            TextView ch = Ui.text(this, "  ›", 11, t.dim, 300);
            p.addView(ch);
        }
        p.setOnClickListener(ok);
        p.setOnKeyListener((v, key, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT));   // no wrap, no sideways jumps
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 36));
        lp.bottomMargin = Ui.dp(this, 3);
        left.addView(p, lp);
        return p;
    }

    private interface Get { int get(); }
    private interface Put { void put(int v); }

    /**
     * One option group: rows, the teal check on the applied one, and a pending line while focus
     * rests on a row that isn't applied ("LIGHT FOCUSED · OK APPLIES"). Inactive rows are visible,
     * dimmed, skipped by the D-pad, and say why.
     */
    private void options(String key, String heading, String[] labels, String[] sums, String[] inactive, Get get, Put put) {
        group(heading);
        final int cur = get.get();
        final TextView pending = Ui.text(this, "", 7.5f, t.goldText, 600);
        pending.setLetterSpacing(0.12f);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final String tag = key + ":" + i;
            String why = inactive != null ? inactive[i] : null;
            Plate p = row(tag, labels[i], why != null ? why : sums != null ? sums[i] : null, i == cur, false, v -> {
                if (idx == get.get()) return;                // applying the current value writes nothing
                put.put(idx);
                committed(tag);
            });
            if (why != null) p.setInactive(true);
            p.onFocus((pl, has) -> {
                if (!has) { pending.setVisibility(View.GONE); return; }
                if (idx != cur) {
                    pending.setText("●  " + labels[idx].toUpperCase(Locale.US) + " FOCUSED · OK APPLIES");
                    pending.setVisibility(View.VISIBLE);
                } else pending.setVisibility(View.GONE);
            });
        }
        pending.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.leftMargin = Ui.dp(this, 12);
        lp.topMargin = Ui.dp(this, 2);
        lp.bottomMargin = Ui.dp(this, 2);
        left.addView(pending, lp);
    }

    // ---- live preview (B4: the real Home ViewGroup at 0.4833) -------------------------------------

    private void addPreview() {
        boolean layout = "layout".equals(page);
        right.addView(pvLabel("HOME · " + committedSummary(), "● live"));
        preview = previewInto(right, layout ? 352 : 464, null, 0f);
        if (layout) {
            boolean isList = Prefs.arrangement(this) == Prefs.ARRANGE_LIST;
            right.addView(pvLabel("IF " + (isList ? "DOCK" : "LIST") + " IS APPLIED", "preview only · not committed"), Ui.lpTop(this, -1, -2, 12));
            float listTop = Prefs.strip(this) != Prefs.STRIP_OFF ? 0.42f : 0.29f;   // where the list starts on Home
            previewAlt = previewInto(right, 352, isList ? Prefs.ARRANGE_DOCK : Prefs.ARRANGE_LIST, isList ? 0.62f : listTop);
            ((View) previewAlt.root.getParent()).setAlpha(0.72f);
            return;
        }
        TextView note = Ui.text(this, page.equals("overview")
                ? "The real Home layout at 48%, not a picture. Always the committed state: exactly what Back returns you to."
                : "Focus alone changes nothing. OK applies and the preview re-inflates; Back leaves with what's here.",
                9, (t.dim & 0x00FFFFFF) | 0xBF000000, 300);
        note.setLineSpacing(0, 1.3f);
        right.addView(note, Ui.lpTop(this, -1, -2, 8));
    }

    private String committedSummary() {
        String mode = ThemeManager.mode(this) == ThemeManager.AUTO ? "AUTO" : ThemeManager.mode(this) == ThemeManager.LIGHT ? "LIGHT" : "DARK";
        return Wallpapers.label(Prefs.wallpaper(this)).toUpperCase(Locale.US) + " · " + mode + " · "
                + (Prefs.arrangement(this) == Prefs.ARRANGE_LIST ? "LIST" : "DOCK")
                + (Prefs.theater(this) ? " · HOME IS THEATER" : "");
    }

    private LinearLayout pvLabel(String l, String r) {
        LinearLayout row = Ui.hbox(this);
        TextView a = Ui.text(this, l, 7.5f, t.dim, 600);
        a.setLetterSpacing(0.16f);
        row.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = Ui.text(this, r, 7.5f, r.startsWith("●") ? t.tealText : (t.dim & 0x00FFFFFF) | 0x99000000, 600);
        b.setLetterSpacing(0.16f);
        row.addView(b);
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2);
        lp.bottomMargin = Ui.dp(this, 7);
        row.setLayoutParams(lp);
        return row;
    }

    /** cropTop > 0 makes a short strip of the home starting that far down (0..1 of its height). */
    private HomeBinder previewInto(LinearLayout parent, float widthDp, Integer arrangement, float cropTop) {
        FrameLayout box = new FrameLayout(this);
        final float r = Ui.dp(this, Ui.R_PV);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        box.setClipToOutline(true);
        box.setForeground(Ui.round(this, 0, Ui.R_PV, t.glassEdge));
        int w = Ui.dp(this, widthDp), h = cropTop > 0 ? Ui.dp(this, 84) : Math.round(w * 9f / 16f);
        parent.addView(box, new LinearLayout.LayoutParams(w, h));
        View home = LayoutInflater.from(this).inflate(R.layout.activity_main, box, false);
        int fw = getResources().getDisplayMetrics().widthPixels, fh = getResources().getDisplayMetrics().heightPixels;
        box.addView(home, new FrameLayout.LayoutParams(fw, fh));
        float s = w / (float) fw;               // 0.4833 on the 1920 panel
        home.setPivotX(0);
        home.setPivotY(0);
        home.setScaleX(s);
        home.setScaleY(s);
        if (cropTop > 0) home.setTranslationY(-fh * s * cropTop);
        home.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ((ViewGroup) home).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        HomeBinder b = new HomeBinder(this, home, true, null);
        b.forceArrangement = arrangement;
        b.bind();
        return b;
    }

    // ---- overview (A5a) ---------------------------------------------------------------------------

    private void pageOverview() {
        setCrumb("HOME", "SETTINGS");
        heading("Settings", "Changes apply with OK and show on the right as they land.");
        String mode = ThemeManager.mode(this) == ThemeManager.AUTO ? "Auto" : ThemeManager.mode(this) == ThemeManager.LIGHT ? "Light" : "Dark";
        cat("background", "Background", Wallpapers.label(Prefs.wallpaper(this)) + " · " + mode);
        int tr = Prefs.transparency(this);
        cat("glass", "Transparency", tr + "% · " + glassName(tr));
        cat("layout", "Layout", (Prefs.theater(this) ? "Theater · " : "") + (Prefs.arrangement(this) == Prefs.ARRANGE_LIST ? "List" : "Dock") + " · "
                + new String[]{"icons", "names", "icons + names"}[Prefs.show(this)]);
        cat("typography", "Typography", "Outfit · " + (Prefs.largeType(this) ? "large" : "standard"));
        cat("focus", "Focus", new String[]{"Gold", "Teal", "White"}[Prefs.accent(this)] + " underline · scale "
                + (Prefs.softFocus(this) ? "1.10" : "1.18"));
        cat("screensaver", "Screensaver", new String[]{"Clock", "Clock + status", "Wallpaper", "Minimal"}[Prefs.ssStyle(this)]
                + " · " + (getPackageName().equals(saverPackage()) ? "on" : "not selected"));
        cat("apps", "Apps on Home", appsOnHome() + " apps");
        cat("sleep", "Sleep", SleepService.running() ? "playing · " + SleepService.label(SleepService.playing())
                : SleepService.sounds(this).size() + " sounds · timer");
        cat("system", "System", "Android " + Build.VERSION.RELEASE + " · " + Live.gb(Live.storage().free) + " free");
    }

    private void cat(String key, String label, String summary) {
        row("cat:" + key, label, summary, false, true, v -> { page = key; undo.clear(); render(null); });
    }

    // ---- background (A5b) -------------------------------------------------------------------------

    private void pageBackground() {
        setCrumb("SETTINGS", "BACKGROUND");
        heading("Background", "Focus to look · OK to apply · Back cancels");
        group("WALLPAPER");
        String cur = Wallpapers.get(Prefs.wallpaper(this)).id;
        final String keep = Wallpapers.label(cur).toUpperCase(Locale.US);
        int tw = (Ui.dp(this, 336) - Ui.dp(this, 12) - Ui.dp(this, 7) * 3) / 4;
        GridLayout g = thumbGrid();
        TextView pending = pendingLine();
        for (int i = 0; i < Wallpapers.ALL.length; i++) {
            final Wallpapers.Wp w = Wallpapers.ALL[i];
            thumbTile(g, "wp:" + w.id, w.label, w.id.equals(cur), i % 4, i == Wallpapers.ALL.length - 1, tw, pending, keep, w.id)
                    .setImageDrawable(thumb(w));
        }
        left.addView(g, Ui.lp(-1, -2));
        LinearLayout.LayoutParams pl = Ui.lp(-2, -2); pl.leftMargin = Ui.dp(this, 12);
        left.addView(pending, pl);

        // Video: every clip in the backgrounds folder (listed fresh, so new clips need no rebuild), then Rotate videos
        group("VIDEO");
        List<VideoBackgrounds.Clip> clips = VideoBackgrounds.list(this);
        if (clips.isEmpty()) {
            Plate none = row("video:none", "No clips yet", "add .mp4 files to Android/data/" + getPackageName() + "/files/backgrounds",
                    false, false, v -> {});
            none.setInactive(true);
        } else {
            GridLayout vg = thumbGrid();
            TextView vpending = pendingLine();
            int n = clips.size() + 1;
            for (int i = 0; i < n; i++) {
                boolean rot = i == clips.size();
                VideoBackgrounds.Clip k = rot ? null : clips.get(i);
                String id = rot ? VideoBackgrounds.ROTATE : k.id;
                ImageView iv = thumbTile(vg, "wp:" + id, rot ? "Rotate videos" : k.label, id.equals(cur), i % 4, i == n - 1, tw, vpending, keep, id);
                java.io.File f = rot ? VideoBackgrounds.fileFor(this, VideoBackgrounds.ROTATE) : k.file;
                VideoBackgrounds.frame(f, 192, 108, b -> { if (b != null) iv.setImageBitmap(b); });
                if (rot) {   // a quiet loop mark over the current clip's frame
                    TextView mark = Ui.text(this, "⟳", 13, 0xE6FFFFFF, 400);
                    mark.setShadowLayer(4, 0, 1, 0x99000000);
                    ((FrameLayout) iv.getParent()).addView(mark, Ui.at(this, Gravity.TOP | Gravity.START, 6, 3, 0, 0));
                }
            }
            left.addView(vg, Ui.lp(-1, -2));
            LinearLayout.LayoutParams vl = Ui.lp(-2, -2); vl.leftMargin = Ui.dp(this, 12);
            left.addView(vpending, vl);
        }

        boolean hasLight = Wallpapers.hasLight(cur);
        String noLight = hasLight ? null : "no light version for " + Wallpapers.label(cur);
        String autoSum = "follows sunset · now " + (ThemeManager.isLight(this) ? "Light" : "Dark");
        int m = ThemeManager.mode(this);
        options("mode", "MODE", new String[]{"Dark", "Light", "Auto"},
                new String[]{null, null, autoSum}, new String[]{null, noLight, null},
                () -> m == ThemeManager.DARK ? 0 : m == ThemeManager.LIGHT ? 1 : 2,
                v -> ThemeManager.setMode(this, v == 0 ? ThemeManager.DARK : v == 1 ? ThemeManager.LIGHT : ThemeManager.AUTO));
        options("rot", "ROTATION", new String[]{"Off", "Each open", "Every 15 min", "Every hour"}, null, null,
                () -> Prefs.rotation(this), v -> { Prefs.setRotation(this, v); Prefs.setRotatedAt(this, System.currentTimeMillis()); });
        options("motion", "MOTION", new String[]{"Full", "Reduced", "Off"},
                new String[]{"drift + pulse + entrances", "focus only", "nothing moves"}, null,
                () -> Prefs.motion(this), v -> Prefs.setMotion(this, v));
    }

    private GridLayout thumbGrid() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(4);
        g.setPadding(Ui.dp(this, 12), 0, 0, 0);
        return g;
    }

    private TextView pendingLine() {
        TextView pending = Ui.text(this, "", 7.5f, t.goldText, 600);
        pending.setLetterSpacing(0.12f);
        pending.setVisibility(View.GONE);
        return pending;
    }

    /** One picture choice in a 4-wide grid; OK applies wallpaper id. Returns its image to fill. */
    private ImageView thumbTile(GridLayout g, String tag, String label, boolean applied, int col, boolean last, int tw,
                                TextView pending, String keep, String id) {
        Plate p = Plate.of(this, t, Plate.Kind.THUMB);
        p.setTag(tag);
        FrameLayout fr = new FrameLayout(this);
        fr.setBackgroundColor(0xFF0B0F14);   // a video frame lands a moment later
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fr.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView nm = Ui.text(this, label, 8, 0xFFFFFFFF, 500);
        nm.setShadowLayer(4, 0, 1, 0x99000000);
        fr.addView(nm, Ui.at(this, Gravity.BOTTOM | Gravity.START, 6, 0, 0, 5));
        if (applied) {
            TextView ck = Ui.text(this, "✓", 8, t.ink, 700);
            ck.setGravity(Gravity.CENTER);
            ck.setBackground(oval(t.teal));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.dp(this, 13), Ui.dp(this, 13), Gravity.TOP | Gravity.END);
            cp.topMargin = Ui.dp(this, 5); cp.rightMargin = Ui.dp(this, 5);
            fr.addView(ck, cp);
        }
        final float rr = Ui.dp(this, Ui.R_THUMB);
        fr.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rr); }
        });
        fr.setClipToOutline(true);
        int pad = Ui.dp(this, 2);
        p.setPadding(pad, pad, pad, pad);
        p.addView(fr, new LinearLayout.LayoutParams(-1, -1));
        p.underlineAt(8, 17);
        p.setOnClickListener(v -> {
            if (applied) return;
            Prefs.setWallpaper(this, id);
            committed(tag);
        });
        p.onFocus((pl, has) -> {
            if (!has || applied) { pending.setVisibility(View.GONE); return; }
            pending.setText("●  " + label.toUpperCase(Locale.US) + " FOCUSED · OK APPLIES · BACK KEEPS " + keep);
            pending.setVisibility(View.VISIBLE);
        });
        p.setOnKeyListener((v, key, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                && ((key == KeyEvent.KEYCODE_DPAD_LEFT && col == 0)
                || (key == KeyEvent.KEYCODE_DPAD_RIGHT && (col == 3 || last))));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = tw; lp.height = Ui.dp(this, 46);
        lp.rightMargin = col < 3 ? Ui.dp(this, 7) : 0;
        lp.bottomMargin = Ui.dp(this, 7);
        g.addView(p, lp);
        return iv;
    }

    private Drawable thumb(Wallpapers.Wp w) {
        int res = w.dark;
        // Thumbs show the dark version. Aurora's base takes its colours from values-night, so
        // resolve it in a night context even while Settings itself is light.
        android.content.res.Configuration cfg = new android.content.res.Configuration(getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        android.content.Context night = createConfigurationContext(cfg);
        if (w.motion != AuroraView.NONE) {   // a still frame of the moving backdrop over its base
            try { return new BitmapDrawable(getResources(), AuroraView.thumb(night, w.motion, res, false, 192, 108)); }
            catch (Exception ignored) {}
        }
        if (!w.hasLight()) {   // these never render light, so don't preview their light colours
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 8;
                Bitmap b = BitmapFactory.decodeResource(night.getResources(), res, o);
                if (b != null) return new BitmapDrawable(getResources(), b);
            } catch (Exception ignored) {}
            return night.getDrawable(res);
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            Bitmap b = BitmapFactory.decodeResource(getResources(), res, o);
            if (b != null) return new BitmapDrawable(getResources(), b);
        } catch (Exception ignored) {}
        return getDrawable(res);
    }

    // ---- sleep sounds ------------------------------------------------------------------------------

    private void pageSleep() {
        setCrumb("SETTINGS", "SLEEP");
        heading("Sleep", "A calm sound on a timer, with the screen black · Back stops it");
        java.util.List<java.io.File> sounds = SleepService.sounds(this);
        if (sounds.isEmpty()) {
            group("SOUND");
            Plate none = row("sleep:none", "No sounds yet", "add .m4a files to Android/data/" + getPackageName() + "/files/sleep",
                    false, false, v -> {});
            none.setInactive(true);
            return;
        }
        String[] names = new String[sounds.size()];
        for (int i = 0; i < names.length; i++) names[i] = SleepService.label(sounds.get(i));
        options("sleepsnd", "SOUND", names, null, null,
                () -> Math.min(Prefs.prefs(this).getInt("sleep_sound", 0), names.length - 1),
                v -> Prefs.prefs(this).edit().putInt("sleep_sound", v).apply());
        options("sleeptimer", "TIMER", SleepService.TIMER_LABELS,
                new String[]{null, null, null, null, "up to 10 hours"}, null,
                () -> Prefs.prefs(this).getInt("sleep_timer", 1),
                v -> Prefs.prefs(this).edit().putInt("sleep_timer", v).apply());
        group("START");
        row("sleep:start", SleepService.running() ? "Back to the sleep screen" : "Start sleep",
                "fades in · fades out over the last 5 min", false, false, v -> {
                    android.content.Intent i = new android.content.Intent(this, SleepActivity.class)
                            .putExtra(SleepActivity.EXTRA_SOUND, Math.min(Prefs.prefs(this).getInt("sleep_sound", 0), names.length - 1))
                            .putExtra(SleepActivity.EXTRA_TIMER, Prefs.prefs(this).getInt("sleep_timer", 1));
                    startActivity(i);
                });
    }

    // ---- layout (A5c) -----------------------------------------------------------------------------

    private void pageLayout() {
        setCrumb("SETTINGS", "LAYOUT");
        heading("Layout", "How apps sit on the Home screen");
        options("page", "HOME PAGE", new String[]{"Calm", "Theater"},
                new String[]{"clock, status, one frosted dock", "tiles + Continue Watching"}, null,
                () -> Prefs.homePage(this), v -> Prefs.setHomePage(this, v));
        group("CONTINUE WATCHING");
        int saved = Watched.count(this), shown = Watched.shown(this).size();
        // Saved includes titles Theater doesn't show (under 2 min in, finished, or a month old)
        row("cw:clear", "Clear Continue Watching",
                saved == 0 ? "nothing saved · stays on this TV" : saved + " saved · " + shown + " shown",
                false, false, v -> {
                    if (Watched.count(this) == 0) return;
                    Watched.clear(this);
                    committed("cw:clear");
                });
        boolean list = Prefs.arrangement(this) == Prefs.ARRANGE_LIST;
        options("arr", "ARRANGEMENT", new String[]{"Dock", "List"},
                new String[]{"one frosted row, centered", "vertical, left, names shown"}, null,
                () -> Prefs.arrangement(this), v -> Prefs.setArrangement(this, v));
        String listWhy = list ? "List always shows names" : null;
        options("show", "SHOW", new String[]{"Icons", "Names", "Both"},
                new String[]{"name appears under focus", null, null},
                new String[]{listWhy, listWhy, listWhy},
                () -> Prefs.show(this), v -> Prefs.setShow(this, v));
        options("strip", "STATUS STRIP", new String[]{"Off", "One tight line", "Status chips", "Ultra minimal"},
                new String[]{"the calm home", "names + dots · degrees · one-line network", "a pill per agent", "smallest footprint"}, null,
                () -> Prefs.strip(this), v -> Prefs.setStrip(this, v));
        options("dock", "DOCK SIZE", new String[]{"Standard", "Large"},
                new String[]{"176 px row", "220 px row"}, new String[]{null, list ? "not available with List" : null},
                () -> Prefs.dockSize(this) == 2 ? 1 : 0, v -> Prefs.setDockSize(this, v == 1 ? 2 : 1));
    }

    // ---- typography / focus ----------------------------------------------------------------------

    private void pageTypography() {
        setCrumb("SETTINGS", "TYPOGRAPHY");
        heading("Typography", "Outfit everywhere · sizes from the locked scale");
        options("tsize", "SIZE", new String[]{"Standard", "Large"},
                new String[]{"clock 80 px", "clock 92 px · +15%"}, null,
                () -> Prefs.largeType(this) ? 1 : 0, v -> Prefs.setLargeType(this, v == 1));
        options("clock", "CLOCK", new String[]{"12-hour", "24-hour"}, new String[]{"9:41", "21:41"}, null,
                () -> Prefs.clock24h(this) ? 1 : 0, v -> Prefs.setClock24h(this, v == 1));
        options("date", "DATE", new String[]{"Show", "Hide"}, new String[]{"under the clock", null}, null,
                () -> Prefs.showDate(this) ? 0 : 1, v -> Prefs.setShowDate(this, v == 0));
    }

    private void pageFocus() {
        setCrumb("SETTINGS", "FOCUS");
        heading("Focus", "How the remote's cursor looks");
        options("accent", "ACCENT", new String[]{"Gold", "Teal", "White"},
                new String[]{"the MILLS default", null, null}, null,
                () -> Prefs.accent(this), v -> Prefs.setAccent(this, v));
        options("scale", "SCALE", new String[]{"1.18", "1.10"}, new String[]{"standard", "softer"}, null,
                () -> Prefs.softFocus(this) ? 1 : 0, v -> Prefs.setSoftFocus(this, v == 1));
        options("fname", "APP NAME", new String[]{"Under the dock", "Hidden"},
                new String[]{"slides to the focused glyph", null}, null,
                () -> Prefs.nameUnderFocus(this) ? 0 : 1, v -> Prefs.setNameUnderFocus(this, v == 0));
    }

    // ---- transparency -----------------------------------------------------------------------------

    private static String glassName(int tr) {
        if (tr <= 10) return "solid";
        if (tr <= 50) return "frosted";
        if (tr <= 85) return "clear";
        return "glass only";
    }

    private void pageGlass() {
        setCrumb("SETTINGS", "TRANSPARENCY");
        heading("Transparency", "How much of the wallpaper shows through the dock, cards and panels");
        group("AMOUNT");
        final int tr = Prefs.transparency(this);
        Plate p = Plate.of(this, t, Plate.Kind.ROW);
        p.setTag("glass:slider");
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        p.addView(Ui.text(this, "◀", 9, t.dim, 400));
        LinearLayout.LayoutParams lp1 = Ui.lp(-2, -2); lp1.leftMargin = Ui.dp(this, 8);
        p.addView(Ui.text(this, "Transparency", 13, t.text, 400), lp1);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(Ui.pill(this, t.light ? 0x260B0F14 : 0x33FFFFFF));
        View fill = new View(this);
        fill.setBackground(Ui.pill(this, t.gold));
        int tw = Ui.dp(this, 120);
        track.addView(fill, new FrameLayout.LayoutParams(Math.max(Ui.dp(this, 4), tw * tr / 100), -1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(tw, Ui.dp(this, 4));
        tl.leftMargin = Ui.dp(this, 16);
        View spacer = new View(this);
        p.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        p.addView(track, tl);
        TextView val = Ui.text(this, tr + "%", 11, t.text, 500);
        val.setGravity(Gravity.END);
        p.addView(val, new LinearLayout.LayoutParams(Ui.dp(this, 40), -2));
        p.addView(Ui.text(this, " ▶", 9, t.dim, 400));
        p.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : key == KeyEvent.KEYCODE_DPAD_LEFT ? -10 : 0;
            if (step == 0) return false;
            int next = Math.max(0, Math.min(100, Prefs.transparency(this) + step));
            if (next != Prefs.transparency(this)) { Prefs.setTransparency(this, next); render("glass:slider"); }
            return true;                                    // lands as it happens, like the dock editor
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 36));
        rl.bottomMargin = Ui.dp(this, 3);
        left.addView(p, rl);
        TextView hint = Ui.text(this, "◀ ▶ changes it live · 0% solid · 100% glass only", 8.5f, t.dim, 300);
        LinearLayout.LayoutParams hl = Ui.lp(-2, -2); hl.leftMargin = Ui.dp(this, 12); hl.bottomMargin = Ui.dp(this, 4);
        left.addView(hint, hl);
        final int[] presets = {0, 40, 70, 100};
        int cur = -1;
        for (int i = 0; i < presets.length; i++) if (presets[i] == tr) cur = i;
        final int curF = cur;
        options("gp", "PRESETS", new String[]{"Solid", "Frosted", "Clear", "Glass only"},
                new String[]{"0% · panels stay solid", "40% · the MILLS default", "70% · more wallpaper", "100% · frost and tint off"}, null,
                () -> curF, v -> Prefs.setTransparency(this, presets[v]));
    }

    // ---- screensaver --------------------------------------------------------------------------------

    private String saverPackage() {
        String s = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        if (s == null || s.isEmpty()) return "";
        String first = s.split(",")[0];
        int slash = first.indexOf('/');
        return slash > 0 ? first.substring(0, slash) : first;
    }

    private static String duration(long ms) {
        if (ms <= 0 || ms >= Integer.MAX_VALUE) return "never";
        long min = ms / 60000;
        if (min < 60) return min + " min";
        return (min / 60) + " h" + (min % 60 > 0 ? " " + (min % 60) + " min" : "");
    }

    private void pageScreensaver() {
        setCrumb("SETTINGS", "SCREENSAVER");
        heading("Screensaver", "What the TV shows when it's left alone");
        options("ss_style", "STYLE", new String[]{"Clock", "Clock + status", "Wallpaper", "Minimal"},
                new String[]{"clock and date over a slow glow", "adds weather, AI team, inside temp", "your wallpaper, dimmed", "time only"}, null,
                () -> Prefs.ssStyle(this), v -> Prefs.setSsStyle(this, v));
        options("ss_size", "CLOCK SIZE", new String[]{"Small", "Medium", "Large"}, null, null,
                () -> Prefs.ssSize(this), v -> Prefs.setSsSize(this, v));
        options("ss_date", "DATE", new String[]{"Show", "Hide"}, null, null,
                () -> Prefs.ssDate(this) ? 0 : 1, v -> Prefs.setSsDate(this, v == 0));
        options("ss_mark", "WORDMARK", new String[]{"Show", "Hide"}, new String[]{"MILLS OS TV under the clock", null}, null,
                () -> Prefs.ssMark(this) ? 0 : 1, v -> Prefs.setSsMark(this, v == 0));
        options("ss_glow", "GLOW", new String[]{"Gold & teal", "Gold", "Off"}, new String[]{"drifts between the two", null, "black only"}, null,
                () -> Prefs.ssGlow(this), v -> Prefs.setSsGlow(this, v));
        options("ss_bright", "BRIGHTNESS", new String[]{"100%", "70%", "40%"}, null, null,
                () -> Prefs.ssBright(this), v -> Prefs.setSsBright(this, v));
        options("ss_dim", "DIM AFTER 20 MIN", new String[]{"On", "Off"}, new String[]{"drops to 40% later in the night", null}, null,
                () -> Prefs.ssDimLater(this) ? 0 : 1, v -> Prefs.setSsDimLater(this, v == 0));
        options("ss_move", "MOVE EVERY", new String[]{"1 minute", "5 minutes"}, new String[]{"best against burn-in", null}, null,
                () -> Prefs.ssMove(this), v -> Prefs.setSsMove(this, v));

        group("THE TV");
        boolean ours = getPackageName().equals(saverPackage());
        boolean enabled = "1".equals(Settings.Secure.getString(getContentResolver(), "screensaver_enabled"));
        info("Screensaver", !enabled ? "off" : ours ? "MILLS clock · on" : "another app");
        long offMs = Settings.System.getLong(getContentResolver(), "screen_off_timeout", -1);
        info("Starts after", duration(offMs));
        long sleepMs = Settings.Secure.getLong(getContentResolver(), "sleep_timeout", -1);
        info("TV sleeps after", duration(sleepMs));
        row("ss:preview", "Preview now", "any key ends it", false, false, v -> {
            startActivity(new Intent(this, DreamPreviewActivity.class));
            overridePendingTransition(0, 0);
        });
        row("ss:android", ours && enabled ? "Change timing" : "Use the MILLS clock", "opens Android's screensaver settings", false, false, v -> {
            Intent i = new Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().resolveActivity(i, 0) == null) i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception e) { toastNote("Android's screensaver settings aren't available"); }
        });
    }

    private void addDreamPreview() {
        right.addView(pvLabel("SCREENSAVER · AS SET", "● live"));
        FrameLayout box = new FrameLayout(this);
        final float r = Ui.dp(this, Ui.R_PV);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        box.setClipToOutline(true);
        box.setForeground(Ui.round(this, 0, Ui.R_PV, t.glassEdge));
        int w = Ui.dp(this, 464), h = Math.round(w * 9f / 16f);
        right.addView(box, new LinearLayout.LayoutParams(w, h));
        FrameLayout stage = new FrameLayout(this);
        int fw = getResources().getDisplayMetrics().widthPixels, fh = getResources().getDisplayMetrics().heightPixels;
        box.addView(stage, new FrameLayout.LayoutParams(fw, fh));
        float s = w / (float) fw;
        stage.setPivotX(0); stage.setPivotY(0);
        stage.setScaleX(s); stage.setScaleY(s);
        dreamPreview = new DreamScene(this, stage, true);
        dreamPreview.build();
        dreamPreview.start();
        TextView note = Ui.text(this, "The real screensaver scene at 48%. It moves every few seconds here so you can see it drift; "
                + "on the TV it moves on the schedule you pick.", 9, (t.dim & 0x00FFFFFF) | 0xBF000000, 300);
        note.setLineSpacing(0, 1.3f);
        right.addView(note, Ui.lpTop(this, -1, -2, 8));
    }

    // ---- system ------------------------------------------------------------------------------------

    private void pageSystem() {
        setCrumb("SETTINGS", "SYSTEM");
        heading("System", "This TV, and the way back");
        group("THIS TV");
        info("Android", Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT);
        Live.Storage st = Live.storage();
        info("Storage", Live.gb(st.free) + " free of " + Live.gb(st.total));
        ResolveInfo home = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        boolean weAreHome = home != null && home.activityInfo != null && getPackageName().equals(home.activityInfo.packageName);
        info("Default home", weAreHome ? "MILLS TV" : home != null && home.activityInfo != null ? home.activityInfo.packageName : "not set");
        String saver = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        info("Screensaver", saver != null && saver.contains(getPackageName()) ? "MILLS clock" : saver == null || saver.isEmpty() ? "none" : "another app");
        String ver = "—";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        info("MILLS OS TV", "version " + ver);

        group("HOME HUB");
        row("sys:hub", "Home hub", Prefs.hub(this) + " · Mission Control, Nest, Blink", false, false, v -> editHub());

        group("THE WAY BACK");
        row("sys:original", "Original", "stock home picker", false, false, v -> {
            if (!Watchdog.restoreStockHome(this)) toastNote("Couldn't open the Home picker on this TV");
        });
        row("sys:android", "Android TV settings", "the TV's own menus", false, false, v -> {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { toastNote("Android settings aren't available"); }
        });

        right.addView(pvLabel("ABOUT", ""));
        LinearLayout about = glass(18);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("MILLS  OS TV");
        sb.setSpan(new android.text.style.ForegroundColorSpan(t.text), 0, 5, 0);
        TextView wm = Ui.text(this, sb, 22, t.text, 300);
        wm.setLetterSpacing(0.12f);
        about.addView(wm);
        View bar = new View(this);
        bar.setBackground(Ui.pill(this, t.gold));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 3));
        bp.topMargin = Ui.dp(this, 6);
        about.addView(bar, bp);
        about.addView(Ui.text(this, "Original opens Android's Home picker so the factory home can take over again. "
                + "The crash watchdog does the same by itself after three crashes in five minutes. "
                + "Apps turned off during the cleanup stay installed: turning them back on is one adb command each.",
                9.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 12));
        right.addView(about, Ui.lp(-1, -2));
    }

    /** The one address every bridge lives on; another house types its own here. */
    private void editHub() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setText(Prefs.hub(this));
        in.setSelectAllOnFocus(true);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Home hub address")
                .setMessage("The computer running the MILLS bridges (Mission Control :8777, Nest :8778, Blink :8779).")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String h = in.getText().toString().trim();
                    if (!h.matches("[A-Za-z0-9.\\-]{1,253}")) { toastNote("That isn't an address"); return; }
                    Prefs.setHub(this, h);
                    page = "system"; render("sys:hub");
                })
                .setNeutralButton("Default", (d, w) -> { Prefs.setHub(this, ""); page = "system"; render("sys:hub"); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void info(String k, String v) {
        LinearLayout r = Ui.hbox(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 12 + 13 + 9), 0, Ui.dp(this, 12), 0);
        r.addView(Ui.text(this, k, 13, t.text, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(Ui.single(Ui.text(this, v, 10, t.dim, 300)));
        left.addView(r, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));
    }

    private void toastNote(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    // ---- apps on home (A5d) --------------------------------------------------------------------

    private static final class App { String pkg, label; }

    private List<App> installed() {
        PackageManager pm = getPackageManager();
        List<App> all = new ArrayList<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        java.util.Set<String> seen = new java.util.HashSet<>(Prefs.hidden(this));   // hidden apps never show
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            all.add(a);
        }
        Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return all;
    }

    private String labelOf(List<App> all, String pkg) {
        for (App a : all) if (a.pkg.equals(pkg)) return a.label;
        return null;
    }

    /** Favourites the dock really shows: launchable apps and inputs (Apps on Home counts the same way). */
    private int appsOnHome() {
        int n = 0;
        for (String f : Prefs.favourites(this)) {
            if (f.startsWith("input:")) { n++; continue; }
            // installed, enabled and launchable, as installed() lists them (a turned-off app doesn't count)
            if (getPackageManager().getLeanbackLaunchIntentForPackage(f) != null && !Prefs.hidden(this).contains(f)) n++;
        }
        return n;
    }

    private void pageApps() {
        setCrumb("SETTINGS", "APPS ON HOME");
        final List<String> favs = new ArrayList<>(Prefs.favourites(this));
        final List<App> all = installed();
        // only installed apps count as slots; uninstalled favourites are skipped by the dock too
        List<String> onHome = new ArrayList<>();
        for (String f : favs) if (labelOf(all, f) != null || f.startsWith("input:")) onHome.add(f);
        titleBox.addView(Ui.single(Ui.text(this, "Apps on Home", 28, t.text, 300)));
        titleBox.addView(Ui.text(this, onHome.size() + " of " + Prefs.MAX_DOCK + " slots · order is the dock order",
                11, t.light ? 0x990B0F14 : 0x99F2F4F7, 300), Ui.lpTop(this, -2, -2, 4));

        LinearLayout page = Ui.vbox(this);
        page.setClipChildren(false);
        page.addView(sectionHead("ON HOME · IN ORDER", liftedSlot >= 0
                ? labelOf(all, onHome.get(liftedSlot)) + " picked up — ◀ ▶ moves it, OK drops it, Back puts it back"
                : "OK picks an app up to move it"));

        LinearLayout slots = Ui.hbox(this);
        slots.setClipChildren(false);
        int sw = (Ui.dp(this, 832) - Ui.dp(this, 8) * (Prefs.MAX_DOCK - 1)) / Prefs.MAX_DOCK;
        for (int i = 0; i < Prefs.MAX_DOCK; i++) {
            final int idx = i;
            Plate s = Plate.of(this, t, Plate.Kind.SLOT);
            s.setOrientation(LinearLayout.VERTICAL);
            s.setGravity(Gravity.CENTER);
            s.setTag("slot:" + i);
            TextView n = Ui.text(this, String.valueOf(i + 1), 7, liftedSlot == i ? t.goldText : t.dim, 600);
            if (i < onHome.size()) {
                String pkg = onHome.get(i);
                String lbl = labelOf(all, pkg);
                if (lbl == null) lbl = pkg;
                ImageView g = new ImageView(this);
                g.setImageDrawable(Glyphs.forApp(this, lbl, pkg, 0.62f, t.text, t.light ? 0xE6FFFFFF : 0xD90B0F14));
                s.addView(g, Ui.lp(-2, Ui.dp(this, 28)));
                g.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                TextView nm = Ui.single(Ui.text(this, liftedSlot == i ? "moving" : lbl, 7.5f, liftedSlot == i ? t.goldText : t.dim, 400));
                nm.setMaxWidth(sw - Ui.dp(this, 8));
                s.addView(nm, Ui.lpTop(this, -2, -2, 4));
                s.setOnClickListener(v -> {
                    if (liftedSlot == idx) { drop(); return; }
                    if (liftedSlot < 0) lift(idx);
                });
                s.onFocus((pl, has) -> { if (has && liftedSlot < 0) selectedSlot = idx; });
                if (liftedSlot == i) s.post(() -> s.setLifted(true));
            } else {
                s.setFocusable(false);
                s.setAlpha(0.5f);
                s.addView(Ui.text(this, i == onHome.size() ? "next free" : "empty", 7.5f, t.dim, 400));
            }
            s.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (liftedSlot >= 0) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT) { moveLifted(-1); return true; }
                    if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { moveLifted(+1); return true; }
                    return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && idx == 0) return true;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && idx >= onHome.size() - 1) return true;
                return false;
            });
            View idxV = n;
            FrameLayout cell = new FrameLayout(this);
            cell.setClipChildren(false);
            cell.addView(s, new FrameLayout.LayoutParams(-1, -1));
            cell.addView(idxV, Ui.at(this, Gravity.TOP | Gravity.START, 7, 5, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sw, Ui.dp(this, 64));
            if (i > 0) lp.leftMargin = Ui.dp(this, 8);
            slots.addView(cell, lp);
        }
        page.addView(slots, Ui.lpTop(this, -1, -2, 8));

        // Move / Remove / Add / Undo
        LinearLayout ctr = Ui.hbox(this);
        ctr.setGravity(Gravity.CENTER_VERTICAL);
        ctr.setClipChildren(false);
        Plate move = button("ctl:move", "◀ ▶  Move", false, v -> { if (selectedSlot < onHome.size()) lift(selectedSlot); });
        Plate remove = button("ctl:remove", "Remove", false, v -> removeAt(selectedSlot));
        Plate add = button("ctl:add", "Add from all apps", true, v -> {
            View first = full.findViewWithTag("all:0");
            if (first != null) first.requestFocus();
        });
        Plate und = button("ctl:undo", undo.isEmpty() ? "Undo" : "Undo · " + undo.size() + (undo.size() == 1 ? " change" : " changes") + " this session",
                false, v -> { if (!undo.isEmpty()) { Prefs.setFavourites(this, undo.pop()); render("ctl:undo"); } });
        if (undo.isEmpty()) und.setAlpha(0.6f);
        ctr.addView(move, btnLp(0)); ctr.addView(remove, btnLp(8)); ctr.addView(add, btnLp(8)); ctr.addView(und, btnLp(8));
        View sp = new View(this);
        ctr.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        android.text.SpannableStringBuilder sess = new android.text.SpannableStringBuilder("Changes land on the dock as they happen · no Save");
        sess.setSpan(new android.text.style.ForegroundColorSpan(t.text), sess.length() - 7, sess.length(), 0);
        ctr.addView(Ui.text(this, sess, 9, t.dim, 300));
        page.addView(ctr, Ui.lpTop(this, -1, -2, 16));

        // all apps A→Z, 5 columns
        page.addView(sectionHead("ALL APPS · A → Z", "OK adds the focused app to the next free slot · apps already on Home are dimmed"),
                Ui.lpTop(this, -1, -2, 20));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        int gw = (Ui.dp(this, 832) - Ui.dp(this, 8) * 4) / 5;
        for (int i = 0; i < all.size(); i++) {
            final App a = all.get(i);
            final boolean on = onHome.contains(a.pkg);
            final int col = i % 5, n = all.size(), idx = i;
            Plate p = Plate.of(this, t, Plate.Kind.APP);
            p.setTag("all:" + i);
            p.setGravity(Gravity.CENTER_VERTICAL);
            p.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 10), 0);
            ImageView g = new ImageView(this);
            g.setImageDrawable(Glyphs.forApp(this, a.label, a.pkg, 0.5f, t.text, t.light ? 0xE6FFFFFF : 0xD90B0F14));
            g.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            p.addView(g, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 22)));
            LinearLayout txt = Ui.vbox(this);
            txt.addView(Ui.single(Ui.text(this, a.label, 10.5f, t.text, 400)));
            final TextView tag = Ui.text(this, on ? "ON HOME" : "", 6.5f, t.dim, 600);
            tag.setLetterSpacing(0.14f);
            txt.addView(tag, Ui.lpTop(this, -2, -2, 2));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = Ui.dp(this, 9);
            p.addView(txt, tl);
            if (on) p.setAlpha(0.5f);
            final int free = onHome.size();
            p.onFocus((pl, has) -> {
                if (on) return;
                tag.setText(has ? (free < Prefs.MAX_DOCK ? "ADD TO HOME · SLOT " + (free + 1) : "HOME IS FULL") : "");
                tag.setTextColor(t.goldText);
            });
            p.setOnClickListener(v -> {
                if (on || free >= Prefs.MAX_DOCK) return;
                List<String> next = new ArrayList<>(onHome);
                next.add(a.pkg);
                save(next);
                render("all:" + idx);
            });
            p.setOnKeyListener((v, key, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                    && ((key == KeyEvent.KEYCODE_DPAD_LEFT && col == 0)
                    || (key == KeyEvent.KEYCODE_DPAD_RIGHT && (col == 4 || idx == n - 1))));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = gw; lp.height = Ui.dp(this, 44);
            lp.rightMargin = col < 4 ? Ui.dp(this, 8) : 0;
            lp.bottomMargin = Ui.dp(this, 8);
            grid.addView(p, lp);
        }
        page.addView(grid, Ui.lpTop(this, -1, -2, 8));
        full.removeAllViews();
        full.addView(page);
    }

    private LinearLayout sectionHead(String l, String r) {
        LinearLayout row = Ui.hbox(this);
        TextView a = Ui.text(this, l, 7.5f, t.dim, 600);
        a.setLetterSpacing(0.18f);
        row.addView(a);
        TextView b = Ui.text(this, "   " + r, 9, (t.dim & 0x00FFFFFF) | 0x99000000, 300);
        row.addView(b);
        return row;
    }

    private Plate button(String tag, String label, boolean primary, View.OnClickListener ok) {
        Plate b = Plate.of(this, t, primary ? Plate.Kind.PRIMARY : Plate.Kind.BUTTON);
        b.setTag(tag);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 15), 0, Ui.dp(this, 15), 0);
        b.addView(Ui.text(this, label, 11, primary ? t.onGold : t.text, primary ? 600 : 500));
        b.setOnClickListener(ok);
        return b;
    }

    private LinearLayout.LayoutParams btnLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 34));
        lp.leftMargin = Ui.dp(this, left);
        return lp;
    }

    private List<String> currentOnHome() {
        List<App> all = installed();
        List<String> on = new ArrayList<>();
        for (String f : Prefs.favourites(this)) if (labelOf(all, f) != null || f.startsWith("input:")) on.add(f);
        return on;
    }

    private void save(List<String> next) {
        undo.push(new ArrayList<>(Prefs.favourites(this)));
        persist(next);
    }

    /** Write the on-home order, keeping favourites that aren't installed right now at the end. */
    private void persist(List<String> onHome) {
        List<String> out = new ArrayList<>(onHome);
        List<String> installedNow = currentOnHome();
        for (String f : Prefs.favourites(this)) if (!out.contains(f) && !installedNow.contains(f)) out.add(f);
        Prefs.setFavourites(this, out);
    }

    private void lift(int idx) {
        beforeLift = new ArrayList<>(Prefs.favourites(this));
        liftedSlot = idx;
        render("slot:" + idx);
    }

    private void moveLifted(int dir) {
        List<String> on = currentOnHome();
        int j = liftedSlot + dir;
        if (j < 0 || j >= on.size()) return;      // rows never wrap
        Collections.swap(on, liftedSlot, j);
        persist(on);                              // lands on the dock as it happens
        liftedSlot = j;
        render("slot:" + j);
    }

    private void drop() {
        if (beforeLift != null && !beforeLift.equals(Prefs.favourites(this))) undo.push(beforeLift);
        int at = liftedSlot;
        liftedSlot = -1;
        beforeLift = null;
        selectedSlot = at;
        render("slot:" + at);
    }

    private void dropBack() {
        if (beforeLift != null) Prefs.setFavourites(this, beforeLift);
        int at = liftedSlot;
        liftedSlot = -1;
        beforeLift = null;
        render("slot:" + at);
    }

    private void removeAt(int idx) {
        List<String> on = currentOnHome();
        if (idx < 0 || idx >= on.size()) return;
        on.remove(idx);
        save(on);
        selectedSlot = Math.max(0, Math.min(idx, on.size() - 1));
        render("slot:" + selectedSlot);
    }

    private android.graphics.drawable.GradientDrawable oval(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }
}
