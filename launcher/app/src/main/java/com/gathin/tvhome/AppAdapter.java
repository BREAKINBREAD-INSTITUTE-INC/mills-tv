package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical. A favourite of the form "input:hdmi:N" is a TV-input tile, not an app.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);
        boolean onTileVerticalKey(int position, int keyCode);
        /** A launch failed: show the real reason inline, never pretend (blueprint D4). */
        default void onLaunchError(String message) {}
    }

    /** How the home shows its apps (Settings > Layout). */
    static final int MODE_DOCK = 0, MODE_LIST = 1, MODE_PREVIEW = 2;

    static final class Entry {
        final String label;
        final String sub;                 // subtitle (input tiles), else null
        final ComponentName component;    // null for input tiles
        final String pkg;                 // app package, or the config token for input tiles
        final Drawable banner;
        final Drawable icon;
        final boolean isInput;
        final String inputId;             // resolved HDMI input id (input tiles)
        int ambient;
        Drawable glyph;                   // cached monochrome dock glyph (dockOnly)
        Drawable halo;                    // its pre-rendered gold focus halo

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label; this.sub = null; this.component = component;
            this.pkg = component.getPackageName(); this.banner = banner; this.icon = icon;
            this.isInput = false; this.inputId = null;
        }

        Entry(String label, String sub, String token, String inputId, Drawable banner) {
            this.label = label; this.sub = sub; this.component = null; this.pkg = token;
            this.banner = banner; this.icon = null; this.isInput = true; this.inputId = inputId;
        }

        String key() { return component != null ? component.flattenToShortString() : pkg; }
    }

    static float FOCUS_SCALE = 1.18f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private final boolean dockOnly;
    private int mode = MODE_DOCK;
    private Ui.T tokens;
    private List<String> favourites;
    private Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this(ctx, listener, dockSize, false);
    }

    AppAdapter(Context ctx, TileListener listener, int dockSize, boolean dockOnly) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.dockOnly = dockOnly;
        this.favourites = Prefs.favourites(ctx);
        this.hidden = Prefs.hidden(ctx);
        this.tokens = Ui.T.of(ctx);
        setHasStableIds(true);
    }

    private boolean previewList;

    void setMode(int m) { mode = m; }

    /** The Settings preview of the List arrangement: list rows, but nothing focusable. */
    void setPreviewList(boolean v) { previewList = v; }

    /** Re-read the theme so glyphs follow light/dark; drops cached glyphs when it changed. */
    void setTokens(Ui.T t) {
        if (tokens != null && tokens.light == t.light) return;
        tokens = t;
        for (Entry e : items) { e.glyph = null; e.halo = null; }
    }

    int ink() { return tokens.text; }

    /** What the last reload was built from; the same again means nothing to redo. */
    private String loadedFrom;

    /**
     * Re-read the apps. Home calls this on every resume, so when nothing changed (same apps, same
     * versions, same favourites and hidden list) it returns at once: loading every banner and icon
     * and re-rendering the glyphs cost ~0.3 s on the main thread each time Home came back.
     */
    void reload() {
        FOCUS_SCALE = Prefs.focusScale(ctx);
        favourites = Prefs.favourites(ctx);
        hidden = Prefs.hidden(ctx);
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        StringBuilder from = new StringBuilder().append(favourites).append('|').append(new java.util.TreeSet<>(hidden)).append('|');
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            // an update installs to a new path, so the path stands for the version
            from.append(ri.activityInfo.packageName).append('/').append(ri.activityInfo.name).append('@')
                .append(ri.activityInfo.applicationInfo.sourceDir).append(ri.activityInfo.applicationInfo.enabled).append(';');
        }
        if (from.toString().equals(loadedFrom) && !items.isEmpty()) { notifyDataSetChanged(); return; }   // rebind only: display prefs may have changed
        loadedFrom = from.toString();
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            if (dockOnly && favourites.indexOf(pkg) < 0) continue;   // the dock shows favourites only: don't load the rest
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        // Input tiles declared in dock_apps as "input:hdmi:N".
        for (String fav : favourites) {
            if (fav.startsWith("input:hdmi:")) {
                int port = 1;
                try { port = Integer.parseInt(fav.substring("input:hdmi:".length())); } catch (Exception ignore) {}
                String inputId = findHdmiInputId(ctx, port);
                fresh.add(new Entry(ctx.getString(R.string.input_tv_label), "HDMI " + port,
                        fav, inputId, ctx.getDrawable(R.drawable.banner_tv)));
            }
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        if (dockOnly) {
            List<Entry> only = new ArrayList<>();
            for (Entry e : fresh) if (favourites.indexOf(e.pkg) >= 0) only.add(e);
            fresh = only;
        }
        if (!dockOnly && ctx.getResources().getBoolean(R.bool.show_home_settings_shortcut)
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings)));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    static String findHdmiInputId(Context ctx, int port) {
        try {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return null;
            List<TvInputInfo> list = tim.getTvInputList();
            // getHdmiDeviceInfo() is a hidden system API, not in the public SDK, so it won't compile.
            // Match by the input's label ("HDMI 2"), then by passthrough order.
            Pattern p = Pattern.compile("HDMI\\s*" + port + "\\b", Pattern.CASE_INSENSITIVE);
            for (TvInputInfo info : list) {
                if (info.getType() == TvInputInfo.TYPE_HDMI
                        && p.matcher(String.valueOf(info.loadLabel(ctx))).find()) return info.getId();
            }
            List<TvInputInfo> hdmi = new ArrayList<>();
            for (TvInputInfo info : list) if (info.getType() == TvInputInfo.TYPE_HDMI && info.isPassthroughInput()) hdmi.add(info);
            return hdmi.size() >= port ? hdmi.get(port - 1).getId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        boolean rows = mode == MODE_LIST || previewList;
        int layout = rows ? R.layout.item_app_row : R.layout.item_app;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        if (rows) v.findViewById(R.id.card).setTag("row");   // rows take the plate, not the 1.18 scale
        if (Prefs.largeDock(ctx) && !rows) {
            View card = v.findViewById(R.id.card);
            card.getLayoutParams().width = Ui.dp(ctx, 120);
        }
        if (mode == MODE_PREVIEW) {
            View card = v.findViewById(R.id.card);
            card.setFocusable(false);
            card.setClickable(false);
        }
        return new Holder(v, mode == MODE_PREVIEW ? null : listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.card.setClipToOutline(true);
        h.name.setText(e.label);
        h.name.setVisibility(View.GONE);
        boolean focused = h.card.isFocused();
        float nd = h.itemView.getResources().getDisplayMetrics().density;
        h.name.setAlpha(focused ? 1f : 0f);
        h.name.setTranslationY(focused ? nd * 4f : -nd * 6f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (dockOnly) {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            boolean rows = mode == MODE_LIST || previewList;
            int show = rows ? Prefs.SHOW_BOTH : Prefs.show(ctx);
            float scale = rows ? 0.5f : show == Prefs.SHOW_BOTH ? 0.72f : Prefs.largeDock(ctx) ? 1.25f : 1f;
            if (e.glyph == null) {
                int cut = tokens.light ? 0xE6FFFFFF : 0xD90B0F14;
                e.glyph = Glyphs.forApp(ctx, e.label, e.pkg, scale, tokens.text, cut);
            }
            h.icon.setColorFilter(null);
            h.icon.setImageDrawable(show == Prefs.SHOW_NAMES ? null : e.glyph);
            if (h.halo != null) {
                if (e.halo == null && !rows && show == Prefs.SHOW_ICONS) e.halo = Glyphs.halo(ctx, e.glyph, Prefs.accentColor(ctx));
                h.halo.setImageDrawable(rows ? null : e.halo);
                h.halo.setAlpha(h.card.isFocused() ? 1f : 0f);
            }
            h.icon.setVisibility(show == Prefs.SHOW_NAMES ? View.GONE : View.VISIBLE);
            if (show != Prefs.SHOW_ICONS) {
                h.label.setText(e.label);
                h.label.setTextColor(tokens.text);
                h.label.setTypeface(Ui.outfit(ctx));
                Ui.weigh(h.label, rows ? 300 : 400);
                h.label.setTextSize(rows ? 15 : show == Prefs.SHOW_NAMES ? 13 : 9);
                h.label.setVisibility(View.VISIBLE);
            } else {
                h.label.setVisibility(View.GONE);
            }
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
        }
        if (!dockOnly) h.label.setVisibility(View.GONE);
        if (h.focusbar != null) {
            h.focusbar.setBackgroundColor(Prefs.accentColor(ctx));
            h.focusbar.setAlpha(h.card.isFocused() ? 1f : 0f);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            if (e.isInput || e.component == null) return true;
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); } catch (ActivityNotFoundException ignored) {}
            return true;
        });
    }

    /** Open an entry (app or input tile); failures go to the listener's onLaunchError. Theater uses it too. */
    void launch(Entry e) {
        if (e.isInput) { launchInput(e); return; }
        if (e.component.equals(new ComponentName(ctx, HomeSettingsActivity.class))) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (ctx.getPackageManager().resolveActivity(i, 0) == null) {
            fail(e.label + " isn't installed");
            return;
        }
        try { ctx.startActivity(i); }
        catch (Exception ex) { fail(ctx.getString(R.string.cant_open, e.label)); }
    }

    private void fail(String msg) {
        if (listener != null) listener.onLaunchError(msg);
        else Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    private void launchInput(Entry e) {
        if (e.inputId == null) { fail(ctx.getString(R.string.cant_open, e.label)); return; }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(e.inputId);
        try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ex) { fail(ctx.getString(R.string.cant_open, e.label)); }
    }


    @Override
    public int getItemCount() { return items.size(); }

    @Override
    public long getItemId(int position) { return items.get(position).key().hashCode(); }

    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key() : null;
    }

    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(key)) return i;
        return -1;
    }

    Entry entryAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    android.graphics.drawable.Drawable bannerAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).banner : null;
    }

    android.graphics.drawable.Drawable iconAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).icon : null;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFFE0A526;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p); g += Color.green(p); b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                if (score > bestScore) { bestScore = score; best = p; }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final View focusbar;
        final ImageView banner;
        final ImageView icon;
        final ImageView halo;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            focusbar = v.findViewById(R.id.focusbar);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            halo = v.findViewById(R.id.halo);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused && !"row".equals(view.getTag()) ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(Motion.ms(view.getContext(), 180))
                        .setInterpolator(new OvershootInterpolator(focused ? 0.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                if (focusbar != null) focusbar.animate().alpha(focused ? 1f : 0f).setDuration(Motion.ms(view.getContext(), 180)).start();
                if (halo != null) halo.animate().alpha(focused ? 1f : 0f).setDuration(Motion.ms(view.getContext(), 180)).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
