package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * MILLS TV — Theater, the second home page (Settings > Layout > Home page). The wallpaper full
 * bleed under a gentle bottom scrim; clock and short date top-left; four flat marks top-right
 * (network, all apps, settings, layout) that get a soft round plate only while focused; one row
 * of frosted app tiles; and Continue Watching from the TV's own Watch Next rows when readable,
 * else from our own media-session history (Watched), or one honest dim line when it is empty. No command center here. Focus animates transforms
 * and alpha only; rows never wrap.
 */
public class TheaterActivity extends Activity implements AppAdapter.TileListener {

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(ThemeManager.wrap(base)); }

    private static final float TILE_W = 100, TILE_H = 80, TILE_GAP = 14;
    private static final float CARD_W = 196, CARD_H = 110, CARD_GAP = 16;
    private static final float TILE_FOCUS = 1.08f, CARD_FOCUS = 1.05f;

    private Ui.T t;
    private FrameLayout root;
    private TextClock clock, date;
    private LinearLayout corner, tileRow, cardRow;
    private HorizontalScrollView tileScroll, cardScroll;
    private TextView cwLabel, cwLine;
    private AppAdapter apps;
    private final List<View> tiles = new ArrayList<>(), cards = new ArrayList<>(), marks = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>();
    private final List<FrostLayout> glass = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DoorbellBanner doorbell = new DoorbellBanner(this);
    private String drawnWallpaper;
    private static int lastTile = 0, lastCard = 0, lastMark = 1;
    private static final int ROW_TILES = 0, ROW_CARDS = 1, ROW_MARKS = 2;
    private static int focusRow = ROW_TILES;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = Ui.T.of(this);
        focusRow = ROW_TILES;   // a fresh Theater (the Home button) lands on the tiles, like the calm home
        root = new FrameLayout(this);
        root.setClipChildren(false);
        setContentView(root);
        build();
        root.postDelayed(() -> Watchdog.markStable(this), Watchdog.STABLE_AFTER_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Settings changed the home page back to Calm: step down to the calm home underneath.
        if (!Prefs.theater(this)) { finish(); overridePendingTransition(0, 0); return; }
        if (ThemeManager.isLight(this) != t.light) { recreate(); return; }
        if (!wallpaperKey().equals(drawnWallpaper)) bindWallpaper();
        View aur = root.findViewWithTag("aurora");
        if (aur instanceof AuroraView) ((AuroraView) aur).setPaused(false);
        View vid = root.findViewWithTag("video");
        if (vid instanceof VideoBackdrop) ((VideoBackdrop) vid).setPaused(false);
        styleClock();
        bindTiles();
        bindMarks();
        loadWatchNext();
        main.removeCallbacks(netTick);
        main.postDelayed(netTick, 15_000L);
        doorbell.start(t);
        // The window would hand focus to the first mark on its own; hold it until restoreFocus.
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.post(this::restoreFocus);
    }

    @Override
    protected void onPause() {
        super.onPause();
        View a = root.findViewWithTag("aurora");
        if (a instanceof AuroraView) ((AuroraView) a).setPaused(true);
        View vid = root.findViewWithTag("video");
        if (vid instanceof VideoBackdrop) ((VideoBackdrop) vid).setPaused(true);
        main.removeCallbacks(netTick);
        doorbell.stop();
    }

    /** Home: Back goes nowhere. */
    @Override public void onBackPressed() {}

    // ---- frame -----------------------------------------------------------------------------------

    private void build() {
        // gentle scrim: clear for the top half, then down to ~78% black at the bottom edge
        View scrim = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0x1F000000, 0x80000000, 0xC7000000});
        scrim.setBackground(g);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout clockGroup = Ui.vbox(this);
        clock = new TextClock(this);
        clock.setTypeface(Ui.outfit(this));
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.01f);
        clockGroup.addView(clock, Ui.lp(-2, -2));
        date = new TextClock(this);
        date.setTypeface(Ui.outfit(this));
        date.setIncludeFontPadding(false);
        date.setFormat12Hour("EEE d MMM");
        date.setFormat24Hour("EEE d MMM");
        clockGroup.addView(date, Ui.lpTop(this, -2, -2, 6));
        root.addView(clockGroup, Ui.at(this, Gravity.TOP | Gravity.START, Ui.MARGIN, Ui.MARGIN_TOP, 0, 0));

        corner = Ui.hbox(this);
        corner.setGravity(Gravity.CENTER_VERTICAL);
        corner.setClipChildren(false);
        root.addView(corner, Ui.at(this, Gravity.TOP | Gravity.END, 0, 42, 56, 0));

        // middle: one row of frosted tiles; the name appears under the focused one only
        tileScroll = row();
        tileRow = (LinearLayout) tileScroll.getChildAt(0);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tp.topMargin = Ui.dp(this, 168);
        root.addView(tileScroll, tp);
        tileScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> { for (FrostLayout f : glass) f.refreshSource(); });

        // bottom: Continue Watching
        LinearLayout bottom = Ui.vbox(this);
        bottom.setClipChildren(false);
        cwLabel = Ui.text(this, getString(R.string.continue_watching), 14, 0xF2FFFFFF, 500);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.leftMargin = Ui.dp(this, Ui.MARGIN);
        bottom.addView(cwLabel, lp);
        cardScroll = row();
        cardRow = (LinearLayout) cardScroll.getChildAt(0);
        bottom.addView(cardScroll, Ui.lpTop(this, -1, -2, 12));
        cwLine = Ui.text(this, "", 13, 0x99FFFFFF, 300);
        LinearLayout.LayoutParams cl = Ui.lpTop(this, -2, -2, 10);
        cl.leftMargin = Ui.dp(this, Ui.MARGIN);
        bottom.addView(cwLine, cl);
        cwLine.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.bottomMargin = Ui.dp(this, 34);
        root.addView(bottom, bp);

        apps = new AppAdapter(this, this, 6, true);
    }

    /** A horizontal row that scrolls to keep focus in view, padded to the safe margins. */
    private HorizontalScrollView row() {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setFadingEdgeLength(Ui.dp(this, 40));
        s.setHorizontalFadingEdgeEnabled(true);
        s.setSmoothScrollingEnabled(!Motion.reduced(this));
        LinearLayout in = Ui.hbox(this);
        in.setClipChildren(false);
        in.setClipToPadding(false);
        // room above and below for the focus scale
        in.setPadding(Ui.dp(this, Ui.MARGIN), Ui.dp(this, 6), Ui.dp(this, Ui.MARGIN), Ui.dp(this, 4));
        s.addView(in, new FrameLayout.LayoutParams(-2, -2));
        return s;
    }

    /** The wallpaper id, plus the clip it is playing (Rotate videos moves on without a new id). */
    private String wallpaperKey() {
        String wp = Prefs.wallpaper(this);
        java.io.File f = VideoBackgrounds.fileFor(this, wp);
        return f == null ? wp : wp + "|" + f.getName();
    }

    private void bindWallpaper() {
        String wp = Prefs.wallpaper(this);
        drawnWallpaper = wallpaperKey();
        root.setBackgroundResource(Wallpapers.drawableFor(wp, t.light));
        View old = root.findViewWithTag("aurora");
        if (old != null) root.removeView(old);
        View oldVideo = root.findViewWithTag("video");
        if (oldVideo != null) root.removeView(oldVideo);
        int motion = Wallpapers.motion(wp);
        AuroraView a = null;
        if (motion != AuroraView.NONE) {
            a = new AuroraView(this);
            a.setTag("aurora");
            a.setStyle(motion, t.light);
            a.setScrim(false);   // Theater's own scrim keeps the text readable
            root.addView(a, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        if (Wallpapers.isVideo(wp)) {   // the clip over the Nebula, which comes back if it fails
            final AuroraView nebula = a;
            VideoBackdrop v = new VideoBackdrop(this);
            v.setTag("video");
            v.setTopScrimOnly();   // Theater's own scrim keeps the bottom readable; footage needs the top too
            v.setOnFail(() -> { if (nebula != null) nebula.setVisibility(View.VISIBLE); });
            root.addView(v, a == null ? 0 : 1, new FrameLayout.LayoutParams(-1, -1));
            v.setClip(VideoBackgrounds.fileFor(this, wp));
            if (a != null && !v.failed()) a.setVisibility(View.GONE);
        }
    }

    private void styleClock() {
        boolean large = Prefs.largeType(this);
        clock.setTextColor(t.text);
        clock.setFontVariationSettings("'wght' 300");
        clock.setTextSize(large ? 46 : 40);
        String f = Prefs.clock24h(this) ? "H:mm" : "h:mm";
        clock.setFormat12Hour(f);
        clock.setFormat24Hour(f);
        date.setTextColor(t.light ? 0xB80B0F14 : 0xCCF2F4F7);
        date.setFontVariationSettings("'wght' 300");
        date.setTextSize(large ? 17 : 15);
        date.setVisibility(Prefs.showDate(this) ? View.VISIBLE : View.GONE);
    }

    // ---- top-right marks -------------------------------------------------------------------------

    private final Runnable netTick = new Runnable() {
        @Override public void run() { bindNetMark(); main.postDelayed(this, 15_000L); }
    };

    private int markInk() { return t.light ? 0xE60B0F14 : 0xF2FFFFFF; }

    private void bindMarks() {
        if (!marks.isEmpty()) { bindNetMark(); return; }
        int ink = markInk();
        int px = Ui.dp(this, 20);
        Drawable grid = getDrawable(R.drawable.ic_grid), gear = getDrawable(R.drawable.ic_gear);
        Drawable[] icons = {null, grid, gear, Glyphs.corner(this, Glyphs.Corner.SLIDERS, px, ink)};
        String[] names = {getString(R.string.wifi), "All apps", getString(R.string.settings), "Layout"};
        View.OnClickListener[] ok = {
                v -> open(new Intent(this, NetworkActivity.class)),
                v -> openSettings("apps"),
                v -> openSettings("overview"),
                v -> openSettings("layout"),
        };
        for (int i = 0; i < icons.length; i++) {
            final int idx = i;
            FrameLayout m = new FrameLayout(this);
            m.setFocusable(true);
            m.setClickable(true);
            m.setContentDescription(names[i]);
            View plate = new View(this);
            GradientDrawable o = new GradientDrawable();
            o.setShape(GradientDrawable.OVAL);
            o.setColor(t.light ? 0x260B0F14 : 0x33FFFFFF);
            plate.setBackground(o);
            plate.setAlpha(0f);
            m.addView(plate, new FrameLayout.LayoutParams(-1, -1));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icons[i]);
            if (icons[i] != null && i != 3) iv.setColorFilter(ink);   // the XML marks ship grey
            m.addView(iv, new FrameLayout.LayoutParams(px, px, Gravity.CENTER));
            m.setOnClickListener(ok[i]);
            m.setOnFocusChangeListener((v, has) -> {
                plate.animate().alpha(has ? 1f : 0f).setDuration(Motion.ms(this, 160)).start();
                if (has) { lastMark = idx; focusRow = ROW_MARKS; }
            });
            m.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusTile(lastTile); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && idx == 0) return true;            // never wrap
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && idx == icons.length - 1) return true;
                return false;
            });
            int s = Ui.dp(this, 38);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.leftMargin = i == 0 ? 0 : Ui.dp(this, 10);
            corner.addView(m, lp);
            marks.add(m);
        }
        bindNetMark();
    }

    /** Live from Live.net: Wi-Fi, Ethernet, or the faded struck-through mark when offline. */
    private void bindNetMark() {
        if (marks.isEmpty()) return;
        Live.Net n = Live.net(this);
        Glyphs.Corner k = n.ethernet ? Glyphs.Corner.ETHERNET : n.wifi && n.online ? Glyphs.Corner.WIFI : Glyphs.Corner.WIFI_OFF;
        FrameLayout m = (FrameLayout) marks.get(0);
        ImageView iv = (ImageView) m.getChildAt(1);
        if (k.equals(iv.getTag())) return;
        iv.setTag(k);
        iv.setImageDrawable(Glyphs.corner(this, k, Ui.dp(this, 20), markInk()));
        m.setContentDescription(k == Glyphs.Corner.WIFI_OFF ? getString(R.string.offline)
                : k == Glyphs.Corner.ETHERNET ? getString(R.string.ethernet)
                : getString(R.string.wifi) + (n.ssid.isEmpty() ? "" : " · " + n.ssid));
    }

    // ---- app tiles -------------------------------------------------------------------------------

    private void bindTiles() {
        apps.setTokens(t);
        apps.reload();
        tileRow.removeAllViews();
        tiles.clear(); names.clear(); glass.clear();
        Wallpapers.Wp meta = Wallpapers.get(Prefs.wallpaper(this));
        int n = apps.getItemCount();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            AppAdapter.Entry e = apps.entryAt(i);
            LinearLayout col = Ui.vbox(this);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setClipChildren(false);

            FrameLayout tile = new FrameLayout(this);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setContentDescription(e.label);
            final float r = Ui.dp(this, 16);
            tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            FrostLayout fl = new FrostLayout(this);
            fl.setRadius(r);
            // lighter than the dock: Theater's tiles are the "light grey" plates
            fl.setGlass(t.light, t.light ? 0.42f : Math.max(0.16f, meta.dockAlpha * 2.2f), t.opacity);
            tile.addView(fl, new FrameLayout.LayoutParams(-1, -1));
            glass.add(fl);
            View lift = new View(this);    // focus brightens the plate; alpha only
            lift.setBackground(Ui.round(this, t.light ? 0x59FFFFFF : 0x24FFFFFF, 16, t.light ? 0xE6FFFFFF : 0x8CFFFFFF));
            lift.setAlpha(0f);
            tile.addView(lift, new FrameLayout.LayoutParams(-1, -1));
            ImageView iv = new ImageView(this);
            int cut = t.light ? 0xE6FFFFFF : 0xD90B0F14;
            iv.setImageDrawable(Glyphs.forApp(this, e.label, e.pkg, 0.86f, t.text, cut));
            tile.addView(iv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            col.addView(tile, new LinearLayout.LayoutParams(Ui.dp(this, TILE_W), Ui.dp(this, TILE_H)));

            TextView name = Ui.single(Ui.text(this, e.label, 13, t.text, 400));
            name.setGravity(Gravity.CENTER);
            name.setMaxWidth(Ui.dp(this, TILE_W + TILE_GAP));
            name.setAlpha(0f);
            if (!t.light) name.setShadowLayer(6, 0, 1, 0x80000000);
            col.addView(name, Ui.lpTop(this, -2, -2, 10));
            names.add(name);

            tile.setOnClickListener(v -> apps.launch(apps.entryAt(idx)));
            tile.setOnFocusChangeListener((v, has) -> {
                focusScale(v, has, TILE_FOCUS);
                lift.animate().alpha(has ? 1f : 0f).setDuration(Motion.ms(this, 180)).start();
                if (!has) clearError(idx);
                name.animate().alpha(has ? 1f : 0f).setDuration(Motion.ms(this, 180)).start();
                if (has) { lastTile = idx; focusRow = ROW_TILES; }
            });
            tile.setOnKeyListener((v, key, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { focusMark(lastMark); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (!cards.isEmpty()) focusCard(lastCard); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && idx == 0) return true;             // never wrap
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && idx == n - 1) return true;
                return false;
            });
            LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
            lp.leftMargin = i == 0 ? 0 : Ui.dp(this, TILE_GAP);
            tileRow.addView(col, lp);
            tiles.add(tile);
        }
        FrostMaker.get(this, Prefs.wallpaper(this), t.light, b -> { for (FrostLayout f : glass) f.setFrost(b); });
    }

    private void focusScale(View v, boolean has, float to) {
        float s = has ? to : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(Motion.ms(this, 180))
                .setInterpolator(new OvershootInterpolator(has ? 0.6f : 0f)).start();
    }

    /** OK failed: the real reason sits in the name slot, dim, for 3 s (as on the calm home). */
    @Override
    public void onLaunchError(String message) {
        if (lastTile < 0 || lastTile >= names.size()) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); return; }
        final int idx = lastTile;
        TextView n = names.get(idx);
        n.setText(message);
        n.setTextColor(t.dim);
        n.setMaxWidth(Ui.dp(this, 260));
        n.setAlpha(1f);
        main.removeCallbacks(clearErrorLater);
        clearErrorLater = () -> clearError(idx);
        main.postDelayed(clearErrorLater, 3000);
    }

    private Runnable clearErrorLater = () -> {};

    private void clearError(int idx) {
        if (idx < 0 || idx >= names.size()) return;
        TextView n = names.get(idx);
        n.setText(apps.labelAt(idx));
        n.setTextColor(t.text);
        n.setMaxWidth(Ui.dp(this, TILE_W + TILE_GAP));
        View tile = idx < tiles.size() ? tiles.get(idx) : null;
        if (tile != null && !tile.isFocused()) n.setAlpha(0f);
    }

    @Override public void onTileFocused(int position, View card) {}
    @Override public boolean onTileVerticalKey(int position, int keyCode) { return false; }

    // ---- Continue Watching -----------------------------------------------------------------------

    private void loadWatchNext() {
        WatchNext.load(this, r -> {
            if (isFinishing() || isDestroyed()) return;
            bindCards(layoutCheck() ? WatchNext.layoutCheck(getPackageName())
                    : r.state == WatchNext.Result.OK ? r : ownHistory());
        });
    }

    /**
     * The TV's Watch Next wins when it returns rows. Otherwise our own history from media sessions
     * (MediaWatcher → Watched), kept on this TV only. Honest when empty: nothing yet, or the
     * listener grant is missing so nothing can be seen.
     */
    private WatchNext.Result ownHistory() {
        MediaWatcher.flush();   // a title still playing in the background gets its latest position
        WatchNext.Result r = new WatchNext.Result();
        r.items.addAll(Watched.shown(this));
        r.state = !r.items.isEmpty() ? WatchNext.Result.OK
                : MediaWatcher.granted(this) ? WatchNext.Result.EMPTY : WatchNext.Result.REFUSED;
        return r;
    }

    /**
     * Debug builds only: "--ez theater_layout_check true" on MainActivity swaps in four labelled
     * in-memory cards (the bundled wallpapers as posters) so spacing can be checked on a TV whose
     * Watch Next is unreadable. Never stored, never in a release build.
     */
    static final String EXTRA_LAYOUT_CHECK = "theater_layout_check";

    private boolean layoutCheck() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra(EXTRA_LAYOUT_CHECK, false);
    }

    private void bindCards(WatchNext.Result r) {
        View focused = getCurrentFocus();
        boolean hadCardFocus = focused != null && cards.contains(focused);
        cardRow.removeAllViews();
        cards.clear();
        if (r.state != WatchNext.Result.OK) {
            cardScroll.setVisibility(View.GONE);
            cwLine.setText(r.state == WatchNext.Result.EMPTY ? R.string.cw_empty : R.string.cw_no_access);
            cwLine.setVisibility(View.VISIBLE);
            if (hadCardFocus) focusTile(lastTile);
            return;
        }
        cwLine.setVisibility(View.GONE);
        cardScroll.setVisibility(View.VISIBLE);
        int n = r.items.size();
        int posterPx = Ui.dp(this, CARD_W);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final WatchNext.Item it = r.items.get(i);
            LinearLayout col = Ui.vbox(this);
            col.setClipChildren(false);

            FrameLayout card = new FrameLayout(this);
            card.setFocusable(true);
            card.setClickable(true);
            card.setContentDescription(it.title);
            final float rad = Ui.dp(this, 10);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
            });
            card.setClipToOutline(true);
            card.setBackground(Ui.round(this, 0x33FFFFFF, 10, 0));   // the plain plate until (or unless) the poster lands
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            if (it.progress >= 0f) {
                FrameLayout track = new FrameLayout(this);
                track.setBackground(Ui.pill(this, 0x47FFFFFF));
                View fill = new View(this);
                fill.setBackground(Ui.pill(this, 0xF2FFFFFF));
                int trackW = Ui.dp(this, CARD_W - 20);
                track.addView(fill, new FrameLayout.LayoutParams(Math.max(Ui.dp(this, 3), Math.round(trackW * it.progress)), -1));
                FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(trackW, Ui.dp(this, 3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                tp.bottomMargin = Ui.dp(this, 8);
                card.addView(track, tp);
            }
            View ring = new View(this);    // focus edge; alpha only
            ring.setBackground(Ui.round(this, 0, 10, 0));
            ((GradientDrawable) ring.getBackground()).setStroke(Ui.dp(this, 1.5f), 0xF2FFFFFF);
            ring.setAlpha(0f);
            card.addView(ring, new FrameLayout.LayoutParams(-1, -1));
            col.addView(card, new LinearLayout.LayoutParams(Ui.dp(this, CARD_W), Ui.dp(this, CARD_H)));

            TextView title = Ui.single(Ui.text(this, it.title, 13, 0xFFFFFFFF, 500));
            title.setMaxWidth(Ui.dp(this, CARD_W));
            col.addView(title, Ui.lpTop(this, -2, -2, 9));
            if (it.subtitle != null) {
                TextView sub = Ui.single(Ui.text(this, it.subtitle, 11, 0x99FFFFFF, 300));
                sub.setMaxWidth(Ui.dp(this, CARD_W));
                col.addView(sub, Ui.lpTop(this, -2, -2, 4));
            }
            WatchNext.poster(this, it.poster, posterPx, poster::setImageBitmap);

            card.setOnClickListener(v -> {
                if (Watched.resume(this, it)) { overridePendingTransition(0, 0); return; }
                Intent in = it.intent(this);
                if (in == null) { Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show(); return; }
                open(in);
            });
            card.setOnFocusChangeListener((v, has) -> {
                focusScale(v, has, CARD_FOCUS);
                ring.animate().alpha(has ? 1f : 0f).setDuration(Motion.ms(this, 180)).start();
                if (has) { lastCard = idx; focusRow = ROW_CARDS; }
            });
            card.setOnKeyListener((v, key, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { focusTile(lastTile); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && idx == 0) return true;             // never wrap
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && idx == n - 1) return true;
                return false;
            });
            LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
            lp.leftMargin = i == 0 ? 0 : Ui.dp(this, CARD_GAP);
            cardRow.addView(col, lp);
            cards.add(card);
        }
        if (hadCardFocus || focusRow == ROW_CARDS) focusCard(lastCard);
    }

    // ---- focus -----------------------------------------------------------------------------------

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && getCurrentFocus() == null) restoreFocus();
    }

    /** Land on the remembered row and item. Nothing else may take focus before this runs. */
    private void restoreFocus() {
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (focusRow == ROW_MARKS) focusMark(lastMark);
        else if (focusRow == ROW_CARDS && !cards.isEmpty()) focusCard(lastCard);
        else focusTile(lastTile);
    }

    private void focusTile(int i) {
        if (tiles.isEmpty()) { focusMark(2); return; }
        tiles.get(Math.max(0, Math.min(i, tiles.size() - 1))).requestFocus();
    }

    private void focusCard(int i) {
        if (cards.isEmpty()) { focusTile(lastTile); return; }
        cards.get(Math.max(0, Math.min(i, cards.size() - 1))).requestFocus();
    }

    private void focusMark(int i) {
        if (marks.isEmpty()) return;
        marks.get(Math.max(0, Math.min(i, marks.size() - 1))).requestFocus();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); overridePendingTransition(0, 0); }
        catch (ActivityNotFoundException | SecurityException e) { Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show(); }
    }

    private void openSettings(String tab) {
        Intent i = new Intent(this, MillsSettingsActivity.class);
        i.putExtra(MillsSettingsActivity.EXTRA_TAB, tab);
        open(i);
    }
}
