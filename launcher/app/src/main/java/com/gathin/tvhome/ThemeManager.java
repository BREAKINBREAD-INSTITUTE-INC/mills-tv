package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Calendar;

/** Light / Dark / Auto for plain (non-AppCompat) activities.
 *  AUTO follows the real sunrise and sunset at home (Open-Meteo, cached by HomeStatus), because
 *  the BRAVIA locks its own night mode on and would never go light. LIGHT/DARK force it.
 *  Light only applies when the current wallpaper has a light version (blueprint D3 hasLight);
 *  otherwise the home stays dark and Settings says why. Default is DARK. */
final class ThemeManager {
    static final int AUTO = 0, LIGHT = 1, DARK = 2;
    private static final String PREF = "mills_tv", KEY = "theme_mode";
    private static final String K_RISE = "sun_rise_min", K_SET = "sun_set_min";

    static int mode(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, DARK);
    }
    static void setMode(Context c, int m) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY, m).apply();
    }

    /** Minutes after midnight, saved whenever the weather fetch brings sunrise/sunset back. */
    static void saveSun(Context c, int riseMin, int setMin) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(K_RISE, riseMin).putInt(K_SET, setMin).apply();
    }

    static boolean isDaytime(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int rise = p.getInt(K_RISE, 7 * 60), set = p.getInt(K_SET, 19 * 60);
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return m >= rise && m < set;
    }

    /** What the user asked for, before the wallpaper gets a say. */
    static boolean wantsLight(Context c) {
        int m = mode(c);
        return m == LIGHT || (m == AUTO && isDaytime(c));
    }

    /** What actually renders. */
    static boolean isLight(Context c) {
        return wantsLight(c) && Wallpapers.hasLight(Prefs.wallpaper(c));
    }

    /** Wrap an Activity's base context so @color tokens in values / values-night follow isLight. */
    static Context wrap(Context base) {
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int night = isLight(base) ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(cfg);
    }
}
