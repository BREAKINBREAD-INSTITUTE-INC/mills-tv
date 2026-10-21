package com.gathin.tvhome;

import android.content.Context;
import android.provider.Settings;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Motion setting (blueprint A5b / D4 reduced motion). Full: drift, pulse and entrances. Reduced or
 * Off, or the system animator scale at 0: focus still lands, but instantly, and nothing drifts.
 * The screen is identical at rest in every mode.
 */
final class Motion {
    static final int FULL = 0, REDUCED = 1, OFF = 2;

    /** FastOutSlowIn, the curve for the command-center reveal. */
    static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private Motion() {}

    static int mode(Context c) {
        float sys = 1f;
        try { sys = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f); }
        catch (Exception ignored) {}
        if (sys == 0f) return OFF;
        return Prefs.motion(c);
    }

    static boolean reduced(Context c) { return mode(c) != FULL; }

    /** A duration in this mode: the full value, or 0 when motion is reduced. */
    static long ms(Context c, long full) { return reduced(c) ? 0 : full; }

    /** Ambient motion (aurora drift, live-dot pulse) only runs on Full. */
    static boolean ambient(Context c) { return mode(c) == FULL; }
}
