package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MILLS TV's one saved source of truth.
 *
 * The dock, the settings screens, and the apps editor all read and write here, so they can
 * never disagree about which apps are favourites, in what order, or how the home is tuned.
 * It is a thin, versioned wrapper over the same "mills_tv" SharedPreferences the theme uses.
 *
 * On first run it seeds favourites and hidden apps from config.xml, so the shipped look is
 * unchanged until the user edits anything. The version number lets future changes migrate the
 * stored blob instead of wiping the user's choices.
 */
final class Prefs {

    static final int VERSION = 1;

    private static final String PREF = "mills_tv";
    private static final String K_VER    = "blob_version";
    private static final String K_FAV    = "fav_apps";     // ordered, newline-joined packages/tokens
    private static final String K_HIDDEN = "hidden_apps";  // newline-joined

    // The four tuning "extras". Defaults chosen so nothing changes until the user picks.
    private static final String K_DOCK_SIZE = "dock_size";   // 0 small, 1 medium (default), 2 large
    private static final String K_ACCENT    = "focus_accent";// 0 gold (default), 1 teal, 2 white
    private static final String K_TYPE      = "typography";  // 0 Outfit (default)
    private static final String K_WALL      = "wallpaper";   // wallpaper id, "" = default

    static final int DOCK_MEDIUM = 1;
    static final int ACCENT_GOLD = 0;

    private Prefs() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Fill in favourites/hidden from config.xml the first time, and stamp the blob version. */
    static void ensureSeeded(Context c) {
        SharedPreferences p = prefs(c);
        boolean seeded = p.getInt(K_VER, 0) >= VERSION && p.contains(K_FAV);
        if (seeded) return;
        SharedPreferences.Editor e = p.edit();
        if (!p.contains(K_FAV)) {
            e.putString(K_FAV, join(Arrays.asList(c.getResources().getStringArray(R.array.dock_apps))));
        }
        if (!p.contains(K_HIDDEN)) {
            e.putString(K_HIDDEN, join(Arrays.asList(c.getResources().getStringArray(R.array.hidden_apps))));
        }
        e.putInt(K_VER, VERSION);
        e.apply();
    }

    // ----- favourites -----
    static List<String> favourites(Context c) {
        ensureSeeded(c);
        return split(prefs(c).getString(K_FAV, ""));
    }
    static void setFavourites(Context c, List<String> ordered) {
        List<String> capped = ordered.size() > MAX_DOCK ? ordered.subList(0, MAX_DOCK) : ordered;
        prefs(c).edit().putString(K_FAV, join(capped)).apply();
    }

    // ----- hidden -----
    static Set<String> hidden(Context c) {
        ensureSeeded(c);
        return new LinkedHashSet<>(split(prefs(c).getString(K_HIDDEN, "")));
    }
    static void setHidden(Context c, List<String> apps) {
        prefs(c).edit().putString(K_HIDDEN, join(apps)).apply();
    }

    // ----- the four extras -----
    static int dockSize(Context c)  { return prefs(c).getInt(K_DOCK_SIZE, DOCK_MEDIUM); }
    static void setDockSize(Context c, int v) { prefs(c).edit().putInt(K_DOCK_SIZE, v).apply(); }

    static int accent(Context c)    { return prefs(c).getInt(K_ACCENT, ACCENT_GOLD); }
    static int accentColor(Context c) {
        switch (accent(c)) { case 1: return 0xFF2BB3A3; case 2: return 0xFFFFFFFF; default: return 0xFFE0A526; }
    }
    static void setAccent(Context c, int v) { prefs(c).edit().putInt(K_ACCENT, v).apply(); }

    static int typography(Context c){ return prefs(c).getInt(K_TYPE, 0); }
    static void setTypography(Context c, int v) { prefs(c).edit().putInt(K_TYPE, v).apply(); }

    static String wallpaper(Context c) { return prefs(c).getString(K_WALL, ""); }
    static void setWallpaper(Context c, String id) { prefs(c).edit().putString(K_WALL, id == null ? "" : id).apply(); }

    // ----- the rest of the blueprint's mills.home.v1 fields (D4). Defaults = the shipped look. -----
    static final int ROTATE_OFF = 0, ROTATE_EACH_OPEN = 1, ROTATE_15 = 2, ROTATE_60 = 3;
    static final int ARRANGE_DOCK = 0, ARRANGE_LIST = 1;
    static final int SHOW_ICONS = 0, SHOW_NAMES = 1, SHOW_BOTH = 2;
    static final int MAX_DOCK = 9;

    private static int i(Context c, String k, int def) { return prefs(c).getInt(k, def); }
    private static void put(Context c, String k, int v) { prefs(c).edit().putInt(k, v).apply(); }
    private static boolean b(Context c, String k, boolean def) { return prefs(c).getBoolean(k, def); }
    private static void put(Context c, String k, boolean v) { prefs(c).edit().putBoolean(k, v).apply(); }

    static int motion(Context c)            { return i(c, "motion", Motion.FULL); }
    static void setMotion(Context c, int v) { put(c, "motion", v); }
    static int rotation(Context c)            { return i(c, "rotation", ROTATE_OFF); }
    static void setRotation(Context c, int v) { put(c, "rotation", v); }
    static long rotatedAt(Context c)          { return prefs(c).getLong("rotated_at", 0L); }
    static void setRotatedAt(Context c, long t) { prefs(c).edit().putLong("rotated_at", t).apply(); }
    static int arrangement(Context c)            { return i(c, "layout_arrangement", ARRANGE_DOCK); }
    static void setArrangement(Context c, int v) { put(c, "layout_arrangement", v); }
    static int show(Context c)            { return i(c, "layout_show", SHOW_ICONS); }
    static void setShow(Context c, int v) { put(c, "layout_show", v); }
    static boolean largeType(Context c)            { return b(c, "type_large", false); }
    static void setLargeType(Context c, boolean v) { put(c, "type_large", v); }
    static boolean clock24h(Context c)            { return b(c, "type_clock24h", false); }
    static void setClock24h(Context c, boolean v) { put(c, "type_clock24h", v); }
    static boolean showDate(Context c)            { return b(c, "type_date", true); }
    static void setShowDate(Context c, boolean v) { put(c, "type_date", v); }
    static boolean softFocus(Context c)            { return b(c, "focus_soft", false); }   // 1.10 instead of 1.18
    static void setSoftFocus(Context c, boolean v) { put(c, "focus_soft", v); }
    static float focusScale(Context c) { return softFocus(c) ? 1.10f : 1.18f; }
    static boolean nameUnderFocus(Context c)            { return b(c, "focus_name", true); }
    static void setNameUnderFocus(Context c, boolean v) { put(c, "focus_name", v); }

    /** Which page Home lands on (Settings > Layout > Home page): the calm home, Theater, or Clean
     *  (JJ, Oct 25: the Lumen look, a photo, the time and one bar of app banners). Theater and
     *  Clean are both drawn by TheaterActivity. */
    static final int HOME_CALM = 0, HOME_THEATER = 1, HOME_CLEAN = 2;
    static int homePage(Context c)            { return i(c, "home_page", HOME_CALM); }
    static void setHomePage(Context c, int v) { put(c, "home_page", v); }
    static boolean theater(Context c)         { return homePage(c) == HOME_THEATER; }
    static boolean clean(Context c)           { return homePage(c) == HOME_CLEAN; }
    /** Home hands over to TheaterActivity (Theater or Clean). */
    static boolean ownPage(Context c)         { return homePage(c) != HOME_CALM; }

    /** Large dock is 110dp, standard 88dp (blueprint B2). Large is not available with List. */
    /** Compact status strip on Home (the strip drafts): 0 off (calm home), 1 one line, 2 chips, 3 minimal. */
    static final int STRIP_OFF = 0, STRIP_LINE = 1, STRIP_CHIPS = 2, STRIP_MINIMAL = 3;
    static int strip(Context c)            { return i(c, "home_strip", STRIP_OFF); }
    static void setStrip(Context c, int v) { put(c, "home_strip", v); }

    /** Glass transparency, 0 (solid) .. 100 (clear). 40 is the shipped frosted look. */
    static int transparency(Context c)            { return i(c, "glass_transparency", 40); }
    static void setTransparency(Context c, int v) { put(c, "glass_transparency", Math.max(0, Math.min(100, v))); }

    /** The look (JJ, Oct 25): Glass (frosted cards, solid white focus; Daylight by day, Night after
     *  sunset with Mode Auto) or Classic (the dark panels and gold ring). Glass is the default. */
    static final int LOOK_CLASSIC = 0, LOOK_GLASS = 1;
    static int look(Context c)            { return i(c, "look", LOOK_GLASS); }
    static void setLook(Context c, int v) { put(c, "look", v); }

    // ----- screensaver (ClockDream) -----
    static final int SS_CLOCK = 0, SS_STATUS = 1, SS_WALLPAPER = 2, SS_MINIMAL = 3;
    static int ssStyle(Context c)             { return i(c, "ss_style", SS_CLOCK); }
    static void setSsStyle(Context c, int v)  { put(c, "ss_style", v); }
    static int ssSize(Context c)              { return i(c, "ss_size", 2); }        // 0 small · 1 medium · 2 large
    static void setSsSize(Context c, int v)   { put(c, "ss_size", v); }
    static int ssGlow(Context c)              { return i(c, "ss_glow", 0); }        // 0 gold+teal drift · 1 gold · 2 off
    static void setSsGlow(Context c, int v)   { put(c, "ss_glow", v); }
    static int ssBright(Context c)            { return i(c, "ss_bright", 0); }      // 0 100% · 1 70% · 2 40%
    static void setSsBright(Context c, int v) { put(c, "ss_bright", v); }
    static boolean ssDimLater(Context c)            { return b(c, "ss_dim_later", true); }
    static void setSsDimLater(Context c, boolean v) { put(c, "ss_dim_later", v); }
    static boolean ssDate(Context c)            { return b(c, "ss_date", true); }
    static void setSsDate(Context c, boolean v) { put(c, "ss_date", v); }
    static boolean ssMark(Context c)            { return b(c, "ss_mark", true); }
    static void setSsMark(Context c, boolean v) { put(c, "ss_mark", v); }
    static int ssMove(Context c)              { return i(c, "ss_move", 0); }        // 0 every minute · 1 every 5 min
    static void setSsMove(Context c, int v)   { put(c, "ss_move", v); }

    /** The home hub's address (Settings > System > Home hub); every bridge lives on it. */
    static String hub(Context c) {
        String h = prefs(c).getString("hub_host", null);
        return h == null || h.trim().isEmpty() ? c.getString(R.string.hub_host) : h.trim();
    }
    static void setHub(Context c, String host) { prefs(c).edit().putString("hub_host", host == null ? "" : host.trim()).apply(); }

    static boolean largeDock(Context c) { return dockSize(c) == 2 && arrangement(c) == ARRANGE_DOCK; }

    // ----- helpers -----
    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }
    private static List<String> split(String blob) {
        List<String> out = new ArrayList<>();
        if (blob == null) return out;
        for (String s : blob.split("\n")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
