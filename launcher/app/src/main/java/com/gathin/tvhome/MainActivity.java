package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * MILLS TV — calm home (blueprint A1-A3). A graded wallpaper, clock + one quiet status line,
 * one frosted dock of monochrome glyphs, and a press-DOWN command center that opens the four
 * MILLS views. Focus animates transforms and alpha only; rows never wrap.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, CommandCenter.Host {

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(ThemeManager.wrap(base)); }

    private HomeBinder home;
    private CommandCenter cc;
    private View homeLayer, clockGroup, statusRow, topIcons, dockGroup, listGlass, dimmer;
    private FrameLayout ccLayer;
    private boolean ccOpen;
    private AnimatorSet bridge;
    private ObjectAnimator pulse;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DoorbellBanner doorbell = new DoorbellBanner(this);
    private static int lastFocused = 0;
    private static int lastCard = 0;
    private boolean layoutCheck;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Safety net: if a crash loop tripped the watchdog, do not try to draw the
        // home again. Send the user to the Home picker so the stock home can take over.
        if (Watchdog.inSafeMode(this)) {
            android.util.Log.w("MillsWatchdog", "safe mode at boot — opening Home picker instead of drawing");
            Watchdog.restoreStockHome(this);
            finish();
            return;
        }
        layoutCheck = getIntent().getBooleanExtra(TheaterActivity.EXTRA_LAYOUT_CHECK, false);
        setContentView(R.layout.activity_main);
        // Theater home: never let the calm home paint first; onResume hands over (see toTheater).
        if (Prefs.theater(this)) findViewById(R.id.root).setVisibility(View.INVISIBLE);
        View root = findViewById(R.id.root);
        homeLayer = findViewById(R.id.homeLayer);
        clockGroup = findViewById(R.id.clockGroup);
        statusRow = findViewById(R.id.statusRow);
        topIcons = findViewById(R.id.topIcons);
        dockGroup = findViewById(R.id.dockGroup);
        listGlass = findViewById(R.id.listGlass);
        dimmer = findViewById(R.id.dimmer);
        ccLayer = findViewById(R.id.commandCenter);

        home = new HomeBinder(this, root, false, this);
        cc = new CommandCenter(this, ccLayer, this);

        // Edge fade (B2): a cell more than half clipped by the dock edge drops to 45%.
        home.dock.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) { fadeEdges(); }
        });

        // If the home is still alive after a short settle, call this build healthy and
        // clear the crash window so one-off glitches never accumulate toward safe mode.
        root.postDelayed(() -> Watchdog.markStable(this), Watchdog.STABLE_AFTER_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (home == null) return;
        if (toTheater()) return;
        if (home.t != null && ThemeManager.isLight(this) != home.drawnLight) { recreate(); return; }
        rotateWallpaperIfDue();
        home.bind();
        AuroraView aurora = findViewById(R.id.aurora);
        if (aurora != null) aurora.setPaused(false);
        VideoBackdrop video = findViewById(R.id.video);
        if (video != null) video.setPaused(false);
        wireTopIcons();
        HomeStatus.refresh(this, home.status, home.statusDot, home.t, () -> {
            if (ccOpen) cc.refresh();
            StatusStrip.bind(this, home.t, home.strip);
            pulseIfLive();
        });
        if (ccOpen) { cc.build(home.t); focusCard(lastCard); }
        else home.root.post(() -> focusTile(lastFocused));
        home.dock.post(this::fadeEdges);
        doorbell.start(home.t);
        main.removeCallbacks(rotateTick);
        if (Prefs.rotation(this) >= Prefs.ROTATE_15) main.postDelayed(rotateTick, 60_000L);
    }

    /**
     * Settings > Layout > Home page = Theater: Home still lands here (this is the HOME activity),
     * then hands straight over to Theater with no transition. The calm root stays invisible
     * while Theater is the home, so pressing Home shows black for a frame, never the calm page.
     */
    private boolean toTheater() {
        View root = findViewById(R.id.root);
        if (!Prefs.theater(this)) { root.setVisibility(View.VISIBLE); return false; }
        root.setVisibility(View.INVISIBLE);
        rotateWallpaperIfDue();   // Background > Rotation applies to Theater too; it reads the pref on resume
        if (ccOpen) {   // no command center on Theater: put the cards away without the animation
            ccOpen = false;
            ccLayer.setVisibility(View.GONE);
            dimmer.setVisibility(View.GONE);
            ((ViewGroup) homeLayer).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        Intent i = new Intent(this, TheaterActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (layoutCheck) i.putExtra(TheaterActivity.EXTRA_LAYOUT_CHECK, true);   // debug builds only; Theater checks
        layoutCheck = false;
        startActivity(i);
        overridePendingTransition(0, 0);
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        AuroraView aurora = findViewById(R.id.aurora);
        if (aurora != null) aurora.setPaused(true);
        VideoBackdrop video = findViewById(R.id.video);
        if (video != null) video.setPaused(true);
        doorbell.stop();
        main.removeCallbacks(rotateTick);
        if (pulse != null) { pulse.cancel(); home.statusDot.setAlpha(1f); }
    }

    /** The one ambient movement on Home: the status dot breathes, only while the feed proves the team is live. */
    private void pulseIfLive() {
        Live.Mission m = Live.mission();
        boolean live = m.ok && !m.stale() && m.count(Ui.Status.OK) > 0;
        if (pulse != null) pulse.cancel();
        home.statusDot.setAlpha(1f);
        if (!live || !Motion.ambient(this)) return;
        pulse = ObjectAnimator.ofFloat(home.statusDot, View.ALPHA, 1f, 0.4f);
        pulse.setDuration(1200);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.start();
    }

    @Override
    public void onBackPressed() {
        if (ccOpen) { close(); return; }
        // Home: Back goes nowhere.
    }

    /** The remote's Home button always lands on the calm home, cards down. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        layoutCheck = intent.getBooleanExtra(TheaterActivity.EXTRA_LAYOUT_CHECK, false);
        if (ccOpen) close();
    }

    // ---- wallpaper rotation (Settings > Background > Rotation) ----------------------------------

    private final Runnable rotateTick = new Runnable() {
        @Override public void run() {
            if (rotateWallpaperIfDue()) home.bind();
            main.postDelayed(this, 60_000L);
        }
    };

    private boolean rotateWallpaperIfDue() {
        int r = Prefs.rotation(this);
        String cur = Prefs.wallpaper(this);
        if (r == Prefs.ROTATE_OFF && VideoBackgrounds.isRotate(cur)) r = Prefs.ROTATE_EACH_OPEN;   // Rotate videos with Rotation off: each visit
        if (r == Prefs.ROTATE_OFF) return false;
        long now = System.currentTimeMillis(), last = Prefs.rotatedAt(this);
        long every = r == Prefs.ROTATE_15 ? 15 * 60_000L : r == Prefs.ROTATE_60 ? 60 * 60_000L : 0;
        if (r != Prefs.ROTATE_EACH_OPEN && now - last < every) return false;
        if (r == Prefs.ROTATE_EACH_OPEN && now - last < 5_000L) return false;   // one turn per visit
        if (Wallpapers.isVideo(cur)) {   // a video background rotates through the video clips only
            if (!VideoBackgrounds.rotate(this, cur)) return false;
            Prefs.setRotatedAt(this, now);
            return true;
        }
        int i = Wallpapers.indexOf(cur);
        Prefs.setWallpaper(this, Wallpapers.ALL[(i + 1) % Wallpapers.ALL.length].id);
        Prefs.setRotatedAt(this, now);
        return true;
    }

    // ---- top-right icons ------------------------------------------------------------------------

    private void wireTopIcons() {
        View wall = findViewById(R.id.btnWall), apps = findViewById(R.id.btnApps), gear = findViewById(R.id.btnSettings);
        if (wall == null) return;
        wall.setOnClickListener(v -> openSettings("background"));
        apps.setOnClickListener(v -> openSettings("apps"));
        gear.setOnClickListener(v -> openSettings("overview"));
        View.OnKeyListener down = (v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusTile(lastFocused); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && v == gear) return true;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && v == wall) return true;
            return false;
        };
        wall.setOnKeyListener(down); apps.setOnKeyListener(down); gear.setOnKeyListener(down);
    }

    // ---- dock ------------------------------------------------------------------------------------

    private RecyclerView activeList() { return home.listMode() ? home.list : home.dock; }
    private AppAdapter activeAdapter() { return home.listMode() ? home.listAdapter : home.dockAdapter; }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        if (home.listMode()) return;
        home.dockName.removeCallbacks(clearError);
        home.dockName.setTextColor(home.t.text);
        home.dockName.setText(home.dockAdapter.labelAt(position));
        final View item = (View) card.getParent();
        android.graphics.Rect r = new android.graphics.Rect(0, 0, item.getWidth(), item.getHeight());
        home.dock.requestChildRectangleOnScreen(item, r, false);
        home.dockName.post(() -> alignName(item, true));
    }

    /** Slide the focused-app name so it sits under the focused glyph (160 ms). */
    private void alignName(View item, boolean animate) {
        View parent = (View) home.dockName.getParent();
        if (parent == null || item.getWidth() == 0) return;
        int[] it = new int[2]; item.getLocationOnScreen(it);
        int[] pp = new int[2]; parent.getLocationOnScreen(pp);
        float tileCenter = it[0] + item.getWidth() / 2f;
        float parentCenter = pp[0] + parent.getWidth() / 2f;
        float tx = tileCenter - parentCenter;
        long ms = animate ? Motion.ms(this, 160) : 0;
        if (ms == 0) home.dockName.setTranslationX(tx);
        else home.dockName.animate().translationX(tx).setDuration(ms).setInterpolator(Motion.FAST_OUT_SLOW_IN).start();
    }

    private void fadeEdges() {
        RecyclerView d = home.dock;
        int w = d.getWidth();
        for (int i = 0; i < d.getChildCount(); i++) {
            View ch = d.getChildAt(i);
            float left = Math.max(0, ch.getLeft()), right = Math.min(w, ch.getRight());
            float visible = ch.getWidth() > 0 ? (right - left) / ch.getWidth() : 1f;
            ch.setAlpha(visible < 0.5f ? 0.45f : 1f);
        }
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        boolean list = home.listMode();
        int n = activeAdapter().getItemCount();
        if (!list) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { open(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { View a = findViewById(R.id.btnApps); if (a != null) a.requestFocus(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position == 0) return true;          // never wrap
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && position == n - 1) return true;
        } else {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { open(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) { View a = findViewById(R.id.btnApps); if (a != null) a.requestFocus(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == n - 1) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
        }
        return false;
    }

    /** OK failed: the real reason goes in the name slot, dim, for 3 s. No spinner, no pretend. */
    @Override
    public void onLaunchError(String message) {
        if (home.listMode()) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); return; }
        home.dockName.removeCallbacks(clearError);
        home.dockName.setVisibility(View.VISIBLE);
        home.dockName.setText(message);
        home.dockName.setTextColor(home.t.dim);
        home.dockName.postDelayed(clearError, 3000);
    }

    private final Runnable clearError = () -> {
        home.dockName.setTextColor(home.t.text);
        home.dockName.setText(home.dockAdapter.labelAt(lastFocused));
        home.dockName.setVisibility(Prefs.nameUnderFocus(this) && Prefs.show(this) == Prefs.SHOW_ICONS ? View.VISIBLE : View.INVISIBLE);
    };

    private void focusTile(int position) {
        final RecyclerView rv = activeList();
        final AppAdapter ad = activeAdapter();
        if (ad.getItemCount() == 0) { View a = findViewById(R.id.btnSettings); if (a != null) a.requestFocus(); return; }
        final int pos = Math.max(0, Math.min(position, ad.getItemCount() - 1));
        rv.scrollToPosition(pos);
        rv.post(() -> {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
            View card = vh != null ? vh.itemView.findViewById(R.id.card) : null;
            if (card == null && rv.getChildCount() > 0) card = rv.getChildAt(0).findViewById(R.id.card);
            if (card != null) card.requestFocus();
        });
    }

    // ---- the bridge: command center -------------------------------------------------------------

    /** DOWN from the dock: one AnimatorSet, 320 ms FastOutSlowIn (blueprint A3). */
    public void open() {
        if (ccOpen) return;
        ccOpen = true;
        cc.build(home.t);
        dimmer.setBackgroundColor(home.t.light ? 0xA6E8EEF4 : 0xAD060C12);   // 68% dim
        dimmer.setVisibility(View.VISIBLE);
        ccLayer.setVisibility(View.VISIBLE);
        // Nothing behind the cards can take focus while they are up.
        ((ViewGroup) homeLayer).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        focusCard(lastCard);
        ccLayer.post(() -> {
            float rise = cc.panel.getHeight() + Ui.dp(this, 54);
            cc.panel.setTranslationY(rise);
            cc.panel.setAlpha(0f);
            clockGroup.setPivotX(0); clockGroup.setPivotY(0);
            View lower = home.listMode() ? listGlass : dockGroup;
            lower.setPivotX(lower.getWidth() / 2f); lower.setPivotY(0);
            animateBridge(true, rise);
        });
    }

    @Override
    public void close() {
        if (!ccOpen) return;
        ccOpen = false;
        float rise = cc.panel.getHeight() + Ui.dp(this, 54);
        ((ViewGroup) homeLayer).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        animateBridge(false, rise);
        focusTile(lastFocused);
    }

    private void animateBridge(final boolean in, float rise) {
        if (bridge != null) bridge.cancel();
        float lift = -Ui.dp(this, 42);   // 84 px
        View lower = home.listMode() ? listGlass : dockGroup;
        bridge = new AnimatorSet();
        bridge.playTogether(
                ObjectAnimator.ofFloat(dimmer, View.ALPHA, in ? 1f : 0f),
                ObjectAnimator.ofFloat(cc.panel, View.TRANSLATION_Y, in ? 0f : rise),
                ObjectAnimator.ofFloat(cc.panel, View.ALPHA, in ? 1f : 0f),
                ObjectAnimator.ofFloat(ccLayer.getChildAt(0), View.ALPHA, in ? 1f : 0f),
                ObjectAnimator.ofFloat(lower, View.TRANSLATION_Y, in ? lift : 0f),
                ObjectAnimator.ofFloat(lower, View.SCALE_X, in ? 0.98f : 1f),
                ObjectAnimator.ofFloat(lower, View.SCALE_Y, in ? 0.98f : 1f),
                ObjectAnimator.ofFloat(lower, View.ALPHA, in ? 0f : 1f),
                ObjectAnimator.ofFloat(topIcons, View.ALPHA, in ? 0f : 1f),
                ObjectAnimator.ofFloat(statusRow, View.ALPHA, in ? 0f : 1f),
                ObjectAnimator.ofFloat(home.strip, View.ALPHA, in ? 0f : 1f),
                ObjectAnimator.ofFloat(clockGroup, View.SCALE_X, in ? 0.8f : 1f),
                ObjectAnimator.ofFloat(clockGroup, View.SCALE_Y, in ? 0.8f : 1f),
                ObjectAnimator.ofFloat(clockGroup, View.ALPHA, in ? 0.55f : 1f));
        bridge.setDuration(Motion.ms(this, 320));
        bridge.setInterpolator(Motion.FAST_OUT_SLOW_IN);
        bridge.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (!in && !ccOpen) { ccLayer.setVisibility(View.GONE); dimmer.setVisibility(View.GONE); }
            }
        });
        bridge.start();
    }

    private void focusCard(int i) {
        int idx = Math.max(0, Math.min(cc.cards.length - 1, i));
        for (int k = 0; k < cc.cards.length; k++) {
            final int n = k;
            cc.cards[k].onFocus((p, has) -> { if (has) lastCard = n; });
        }
        cc.cards[idx].requestFocus();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    @Override
    public void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); overridePendingTransition(0, 0); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show(); }
    }

    private void openSettings(String tab) {
        Intent i = new Intent(this, MillsSettingsActivity.class);
        i.putExtra(MillsSettingsActivity.EXTRA_TAB, tab);
        open(i);
    }
}
