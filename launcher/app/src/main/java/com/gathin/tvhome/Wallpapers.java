package com.gathin.tvhome;

/**
 * The wallpapers the home can show, with the per-wallpaper ink metadata from blueprint D3.
 * Each one decides by eye how text and glass sit on it; the launcher never samples pixels.
 * Graded wallpapers and their frost slices are pre-rendered from the blueprint's own CSS by
 * ~/Scripts/tv/wallpaper_render/render.sh. Aurora, Deep Night and the colour backdrops are drawn
 * from XML, so their frost is made once on a background thread when they are applied (FrostMaker).
 * The moving ones (motion != NONE) get an AuroraView over that base on Calm, Theater and the
 * Wallpaper screensaver; the dimmed views behind the command center keep the still base.
 * Video backgrounds ("video:<file>", see VideoBackgrounds) stand in as dark Nebula wallpapers:
 * that is their base, their frost fallback and what shows if the clip can't play.
 */
final class Wallpapers {
    private Wallpapers() {}

    static final class Wp {
        final String id, label;
        final int dark, light;           // light = 0: no light version
        final int frost, frostLight;     // 0: make at runtime
        final int motion;                // AuroraView style drawn over it, AuroraView.NONE: still
        final float dockAlpha;           // glass tint alpha on this wallpaper
        Wp(String id, String label, int dark, int light, int frost, int frostLight, int motion, float dockAlpha) {
            this.id = id; this.label = label; this.dark = dark; this.light = light;
            this.frost = frost; this.frostLight = frostLight; this.motion = motion; this.dockAlpha = dockAlpha;
        }
        boolean hasLight() { return light != 0; }
    }

    static final Wp[] ALL = {
        new Wp("dusk",   "Dusk",       R.drawable.wp_dusk,  R.drawable.wp_day, R.drawable.dock_frost_dusk,  R.drawable.dock_frost_day, AuroraView.NONE, 0.075f),
        new Wp("ridge",  "Ridge",      R.drawable.wp_ridge, 0, R.drawable.dock_frost_ridge, 0, AuroraView.NONE, 0.075f),
        new Wp("coast",  "Coast",      R.drawable.wp_coast, 0, R.drawable.dock_frost_coast, 0, AuroraView.NONE, 0.09f),
        new Wp("aurora", "Aurora",     R.drawable.bg_aurora, 0, 0, 0, AuroraView.DEEP, 0.075f),
        new Wp("night",  "Deep Night", R.drawable.bg_mills, 0, 0, 0, AuroraView.NONE, 0.075f),
        new Wp("color",  "Aurora Color", R.drawable.bg_aurora_color, R.drawable.bg_aurora_color_day, 0, 0, AuroraView.COLOR, 0.09f),
        new Wp("waves",  "Color Waves",  R.drawable.bg_waves,  R.drawable.bg_waves_day,  0, 0, AuroraView.WAVES,  0.09f),
        new Wp("nebula", "Nebula",       R.drawable.bg_nebula, 0, 0, 0, AuroraView.NEBULA, 0.075f),
        new Wp("sunset", "Sunset Drift", R.drawable.bg_sunset, R.drawable.bg_sunset_day, 0, 0, AuroraView.SUNSET, 0.09f),
    };

    static Wp get(String id) {
        if (id != null) for (Wp w : ALL) if (w.id.equals(id)) return w;
        if (VideoBackgrounds.isVideo(id)) return video(id);
        return ALL[0];
    }

    private static Wp lastVideo;
    private static Wp video(String id) {
        Wp v = lastVideo;
        if (v == null || !v.id.equals(id)) {
            v = new Wp(id, VideoBackgrounds.label(id), R.drawable.bg_nebula, 0, 0, 0, AuroraView.NEBULA, 0.075f);
            lastVideo = v;
        }
        return v;
    }

    static boolean isVideo(String id) { return VideoBackgrounds.isVideo(id); }

    static int indexOf(String id) {
        Wp w = get(id);
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == w) return i;
        return 0;
    }

    static boolean hasLight(String id) { return get(id).hasLight(); }

    /** The drawable to show for this wallpaper in the given theme. */
    static int drawableFor(String id, boolean light) {
        Wp w = get(id);
        return light && w.hasLight() ? w.light : w.dark;
    }

    static int drawableFor(String id) { return drawableFor(id, false); }

    /** Pre-rendered frost for the dock (blueprint D2), or 0 when FrostMaker must make it. */
    static int frostFor(String id, boolean light) {
        Wp w = get(id);
        return light && w.hasLight() ? w.frostLight : w.frost;
    }

    /** The AuroraView style for this wallpaper, or AuroraView.NONE when it stands still. */
    static int motion(String id) { return get(id).motion; }

    static String label(String id) { return get(id).label; }
}
