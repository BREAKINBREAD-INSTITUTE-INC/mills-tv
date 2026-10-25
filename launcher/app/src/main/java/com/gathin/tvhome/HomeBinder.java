package com.gathin.tvhome;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Binds activity_main to the committed prefs: wallpaper + frost, theme colours, clock format,
 * dock or list, glyphs, wordmark. The real home and the Settings live preview both go through
 * here, so the preview is the actual Home layout with the actual prefs (blueprint B4), not a
 * picture of it.
 */
final class HomeBinder {

    final Context ctx;
    final View root;
    final boolean preview;
    Ui.T t;
    final RecyclerView dock, list;
    final FrostLayout dockGlass, listGlass;
    final TextView dockName, status;
    final android.widget.FrameLayout mark, strip;
    final View statusDot, statusRow;
    final TextClock clock, date;
    final LinearLayout topIcons;
    final AppAdapter dockAdapter;
    final AppAdapter listAdapter;
    String wallpaperId = "";
    boolean drawnLight;

    HomeBinder(Context ctx, View root, boolean preview, AppAdapter.TileListener listener) {
        this.ctx = ctx;
        this.root = root;
        this.preview = preview;
        dock = root.findViewById(R.id.dock);
        list = root.findViewById(R.id.list);
        dockGlass = root.findViewById(R.id.dockGlass);
        listGlass = root.findViewById(R.id.listGlass);
        dockName = root.findViewById(R.id.dockName);
        status = root.findViewById(R.id.homeStatus);
        statusDot = root.findViewById(R.id.statusDot);
        statusRow = root.findViewById(R.id.statusRow);
        mark = root.findViewById(R.id.mark);
        strip = root.findViewById(R.id.strip);
        clock = root.findViewById(R.id.clock);
        date = root.findViewById(R.id.date);
        topIcons = root.findViewById(R.id.topIcons);

        int mode = preview ? AppAdapter.MODE_PREVIEW : AppAdapter.MODE_DOCK;
        dock.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
        dock.setItemAnimator(null);
        dockAdapter = new AppAdapter(ctx, listener, 6, true);
        dockAdapter.setMode(mode);
        dock.setAdapter(dockAdapter);

        list.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.VERTICAL, false));
        list.setItemAnimator(null);
        listAdapter = new AppAdapter(ctx, listener, 6, true);
        listAdapter.setMode(preview ? AppAdapter.MODE_PREVIEW : AppAdapter.MODE_LIST);
        listAdapter.setPreviewList(preview);
        list.setAdapter(listAdapter);
        if (preview) {
            dock.setFocusable(false);
            dock.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    /** Settings' "if List is applied" strip renders an arrangement that isn't committed yet. */
    Integer forceArrangement;

    boolean listMode() {
        int a = forceArrangement != null ? forceArrangement : Prefs.arrangement(ctx);
        return a == Prefs.ARRANGE_LIST;
    }

    /** Apply everything from prefs. Cheap enough to call on every resume. */
    void bind() {
        t = Ui.T.of(ctx);
        drawnLight = t.light;
        String wp = Prefs.wallpaper(ctx);
        wallpaperId = wp;
        root.setBackgroundResource(Wallpapers.drawableFor(wp, t.light));
        AuroraView aurora = root.findViewById(R.id.aurora);
        VideoBackdrop video = root.findViewById(R.id.video);
        boolean isVideo = Wallpapers.isVideo(wp) && video != null;
        if (aurora != null) {
            int motion = Wallpapers.motion(wp);
            aurora.setVisibility(motion != AuroraView.NONE ? View.VISIBLE : View.GONE);
            aurora.setStyle(motion, t.light);
            aurora.setStill(preview);   // the Settings preview shows a still frame; menus stay smooth
        }
        if (video != null) {
            if (isVideo) {
                // the clip covers the Nebula; if it is missing or fails, the Nebula comes back
                video.setOnFail(() -> { if (aurora != null) aurora.setVisibility(View.VISIBLE); });
                video.setStill(preview);
                video.setVisibility(View.VISIBLE);
                video.setClip(VideoBackgrounds.fileFor(ctx, wp));
                if (aurora != null && !video.failed()) aurora.setVisibility(View.GONE);
            } else {
                video.setVisibility(View.GONE);
                video.clear();
            }
        }

        Wallpapers.Wp meta = Wallpapers.get(wp);
        dockGlass.setGlass(t.light, meta.dockAlpha, t.opacity);
        listGlass.setGlass(t.light, meta.dockAlpha, t.opacity);
        listGlass.setRadius(Ui.dp(ctx, 14));
        FrostMaker.get(ctx, wp, t.light, b -> { dockGlass.setFrost(b); listGlass.setFrost(b); });

        // clock / date / status (blueprint B1)
        boolean large = Prefs.largeType(ctx);
        clock.setTextColor(t.text);
        Ui.weigh(clock, 300);
        clock.setTextSize(large ? 46 : 40);
        if (Prefs.clock24h(ctx)) { clock.setFormat12Hour("H:mm"); clock.setFormat24Hour("H:mm"); }
        else { clock.setFormat12Hour("h:mm"); clock.setFormat24Hour("h:mm"); }
        date.setTextColor(t.light ? 0xB80B0F14 : 0xCCF2F4F7);
        Ui.weigh(date, 300);
        date.setTextSize(large ? 17 : 15);
        date.setVisibility(Prefs.showDate(ctx) ? View.VISIBLE : View.GONE);
        status.setTextColor(t.light ? 0x990B0F14 : 0x9EF2F4F7);
        Ui.weigh(status, 300);
        status.setTextSize(large ? 14.5f : 13);
        statusDot.getBackground().mutate().setTint(t.idle);
        if (!Live.mission().stale() || Live.weather().has()) {
            status.setText(HomeStatus.line(Live.weather(), Live.mission()));
            statusDot.getBackground().setTint(t.state(Live.mission().teamState()));
        }
        dockName.setTextColor(t.text);
        Ui.weigh(dockName, 400);
        dockName.setVisibility(Prefs.nameUnderFocus(ctx) && Prefs.show(ctx) == Prefs.SHOW_ICONS ? View.VISIBLE : View.INVISIBLE);
        mark.removeAllViews();
        mark.addView(Ui.mark(ctx, t), new android.widget.FrameLayout.LayoutParams(-2, -2));
        StatusStrip.bind(ctx, t, strip);

        // dock or list
        boolean listMode = listMode();
        root.findViewById(R.id.dockGroup).setVisibility(listMode ? View.GONE : View.VISIBLE);
        listGlass.setVisibility(listMode ? View.VISIBLE : View.GONE);
        ((android.widget.FrameLayout.LayoutParams) listGlass.getLayoutParams()).topMargin =
                Ui.dp(ctx, Prefs.strip(ctx) != Prefs.STRIP_OFF ? 236 : 165);   // make room for the strip
        boolean largeDock = Prefs.largeDock(ctx);
        dock.getLayoutParams().height = Ui.dp(ctx, largeDock ? 110 : 88);
        dockGlass.setRadius(Ui.dp(ctx, largeDock ? 22 : 18));
        dockAdapter.setTokens(t);
        listAdapter.setTokens(t);
        if (listMode) listAdapter.reload(); else dockAdapter.reload();
        sizeDock();
        if (!preview) buildTopIcons();
    }

    /** Rows never wrap: the dock shows at most as many 98dp cells as fit (8 at standard size). */
    void sizeDock() {
        int cell = Ui.dp(ctx, Prefs.largeDock(ctx) ? 120 : 98);
        int max = Math.max(1, (Ui.dp(ctx, 960 - 128) - Ui.dp(ctx, 28)) / cell);
        int n = Math.min(Math.max(1, dockAdapter.getItemCount()), max);
        ViewGroup.LayoutParams lp = dock.getLayoutParams();
        lp.width = n * cell;
        dock.setLayoutParams(lp);
    }

    /** Wallpaper picker, All apps, Settings: 18dp monochrome icons, 24dp apart, as focus plates. */
    private void buildTopIcons() {
        topIcons.removeAllViews();
        int[] icons = {R.drawable.ic_sliders, R.drawable.ic_wall, R.drawable.ic_grid, R.drawable.ic_gear};
        int[] ids = {R.id.btnQuick, R.id.btnWall, R.id.btnApps, R.id.btnSettings};
        String[] names = {"Quick menu", "Wallpaper", "All apps", "Settings"};
        for (int i = 0; i < icons.length; i++) {
            Plate p = Plate.of(ctx, t, Plate.Kind.ROW);
            p.setId(ids[i]);
            p.underlineAt(3, 1);
            p.setGravity(android.view.Gravity.CENTER);
            p.setContentDescription(names[i]);
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(icons[i]);
            final int normal = t.light ? 0xB80B0F14 : 0xC7F2F4F7;
            iv.setColorFilter(normal);
            // Night Glass: a focused icon sits on white, so it goes dark with it
            if (t.inkOnFocus) p.onFocus((pl, has) -> iv.setColorFilter(has ? 0xFF0B0F14 : normal));
            int s = Ui.dp(ctx, 18);
            p.addView(iv, new LinearLayout.LayoutParams(s, s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(ctx, 30), Ui.dp(ctx, 30));
            lp.leftMargin = i == 0 ? 0 : Ui.dp(ctx, 12);
            topIcons.addView(p, lp);
        }
    }
}
