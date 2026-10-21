package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * The MILLS TV kit: blueprint tokens (C1/C2), the type scale (C3), radii and spacing (C4), and
 * small builders every screen uses, so all screens speak the same language. Sizes are in dp,
 * which on the BRAVIA (density 2.0) is exactly blueprint px / 2.
 */
final class Ui {
    private Ui() {}

    /** Colour tokens for the theme that is actually rendering. */
    static final class T {
        final boolean light;
        final int text, dim, idle, ink, gold, goldText, teal, tealText, red, redText;
        final int panel, edge, glass, glassEdge, plate, dimmer, markGold, markText, onGold;
        /** Status only (JJ, Oct 21): green good, orange not 100%, red down, grey never connected. */
        final int green, greenText, orange, orangeText;
        /** Glass opacity from Settings > Transparency: 0 clear .. 1 solid; 0.6 is the shipped look. */
        final float opacity;

        T(boolean light) { this(light, 40); }

        T(boolean light, int transparency) {
            this.opacity = 1f - Math.max(0, Math.min(100, transparency)) / 100f;
            this.light = light;
            gold = 0xFFE0A526; teal = 0xFF2BB3A3; red = 0xFFCF5B5B; ink = 0xFF0B0F14; onGold = 0xFF0B0F14;
            green = 0xFF3FB950; orange = 0xFFF0883E;
            greenText = light ? 0xFF1F7A32 : green; orangeText = light ? 0xFFB3561A : orange;
            if (!light) {
                text = 0xFFF2F4F7; dim = 0xFF9AA6B2; idle = 0xFF55606B;
                goldText = gold; tealText = teal; redText = red;
                panel = surface(0xD1121A23, 0xF5121A23, opacity); edge = 0xFF1C2733;
                glass = surface(0x13FFFFFF, 0xF2172230, opacity); glassEdge = 0x29FFFFFF; plate = 0x21FFFFFF;
                dimmer = 0x9E060C12; markGold = 0xBFE0A526; markText = 0x61F2F4F7;
            } else {
                text = 0xFF0B0F14; dim = 0xFF5B6977; idle = 0xFF9AA6B2;
                goldText = 0xFF8F6208; tealText = 0xFF167F73; redText = 0xFFB04040;
                panel = surface(0xC7FFFFFF, 0xF7FFFFFF, opacity); edge = 0xFFD3DCE5;
                glass = surface(0x6BFFFFFF, 0xF2F4F7FA, opacity); glassEdge = 0xB3FFFFFF; plate = 0x140B0F14;
                dimmer = 0x73E8EEF4; markGold = 0xFF9A6B0A; markText = 0x6B0B0F14;
            }
        }

        static T of(Context c) { return new T(ThemeManager.isLight(c), Prefs.transparency(c)); }

        /**
         * A surface at this opacity. Up to the shipped 0.6 it fades from clear to its normal
         * colour; past it, it firms up toward a solid colour (a panel, not white, in dark mode).
         */
        static int surface(int normal, int solid, float o) {
            if (o <= 0.6f) {
                int a = Math.round(((normal >>> 24) & 255) * (o / 0.6f));
                return (a << 24) | (normal & 0xFFFFFF);
            }
            float k = (o - 0.6f) / 0.4f;
            int out = 0;
            for (int shift = 24; shift >= 0; shift -= 8) {
                int x = (normal >>> shift) & 255, y = (solid >>> shift) & 255;
                out |= Math.round(x + (y - x) * k) << shift;
            }
            return out;
        }

        /** Status colour for a dot. */
        int state(String s) {
            switch (s) {
                case Status.OK:   return green;
                case Status.WARN: return orange;
                case Status.BAD:  return red;
                default:          return light ? 0xFF9AA6B2 : idle;
            }
        }
        /** Status colour as text (light mode uses the darker text variants). */
        int stateText(String s) {
            switch (s) {
                case Status.OK:   return greenText;
                case Status.WARN: return orangeText;
                case Status.BAD:  return redText;
                default:          return dim;
            }
        }
    }

    /** The four states every dot, chip and row speaks. Status is always a dot plus a word. */
    static final class Status {
        static final String OK = "ok", WARN = "warn", BAD = "bad", IDLE = "idle";
    }

    // ---- sizes ----------------------------------------------------------------------------------

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static final int MARGIN = 64, MARGIN_TOP = 46;             // safe margins
    static final int R_CARD = 14, R_ROW = 8, R_PV = 10, R_SLOT = 11, R_THUMB = 7, R_DOCK = 18;

    // ---- type -----------------------------------------------------------------------------------

    private static Typeface outfit;

    static Typeface outfit(Context c) {
        if (outfit == null) {
            try { outfit = c.getResources().getFont(R.font.outfit); }
            catch (Throwable t) { outfit = Typeface.SANS_SERIF; }
        }
        return outfit;
    }

    /** A TextView in Outfit at a variable weight. sp follows the blueprint's sp column. */
    static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(outfit(c));
        t.setFontVariationSettings("'wght' " + weight);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView eyebrow(Context c, T t, String s) {
        TextView v = text(c, s.toUpperCase(java.util.Locale.US), 9, t.dim, 600);
        v.setLetterSpacing(0.14f);
        return v;
    }

    static TextView groupHeading(Context c, T t, String s) {
        TextView v = text(c, s.toUpperCase(java.util.Locale.US), 7.5f, t.dim, 600);
        v.setLetterSpacing(0.18f);
        return v;
    }

    static TextView single(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    // ---- shapes ---------------------------------------------------------------------------------

    static GradientDrawable round(Context c, int fill, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) g.setStroke(Math.max(1, dp(c, 0.5f)), strokeColor);
        return g;
    }

    static GradientDrawable pill(Context c, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 999));
        return g;
    }

    static View dot(Context c, int color, float sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        int s = dp(c, sizeDp);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return v;
    }

    /** Chip: 20dp tall pill, accent at 16%, dot 6dp + 9sp 600 label (blueprint B3). */
    static LinearLayout chip(Context c, T t, String state, String label) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int color = t.state(state);
        int fill = Status.IDLE.equals(state) ? (t.light ? 0x24556070 : 0x3D55606B) : (color & 0x00FFFFFF) | (t.light ? 0x33000000 : 0x29000000);
        row.setBackground(pill(c, fill));
        row.setPadding(dp(c, 8), 0, dp(c, 9), 0);
        row.setMinimumHeight(dp(c, 20));
        row.addView(dot(c, color, 6));
        TextView l = single(text(c, label, 9, t.stateText(state), 600));   // a chip is one line, never two
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(c, 5);
        row.addView(l, lp);
        return row;
    }

    /** A dim key on the left, a value on the right (card row lines, facts). */
    static LinearLayout kv(Context c, T t, String k, String v, int valueColor, float sp) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // The key is short and always whole; a long value gives way (ellipsis) instead of the key.
        TextView kk = single(text(c, k, sp, t.dim, 300));
        row.addView(kk, new LinearLayout.LayoutParams(-2, -2));
        TextView vv = single(text(c, v, sp, valueColor == 0 ? t.text : valueColor, 500));
        vv.setGravity(Gravity.END);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, -2, 1f);
        vp.leftMargin = dp(c, 8);
        row.addView(vv, vp);
        return row;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lpTop(Context c, int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static LinearLayout vbox(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout hbox(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); return l; }

    static FrameLayout.LayoutParams at(Context c, int gravity, float leftDp, float topDp, float rightDp, float bottomDp) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, gravity);
        p.leftMargin = dp(c, leftDp); p.topMargin = dp(c, topDp);
        p.rightMargin = dp(c, rightDp); p.bottomMargin = dp(c, bottomDp);
        return p;
    }

    // ---- chrome shared by every sub-screen --------------------------------------------------------

    /**
     * The MILLS OS TV wordmark bottom-left on every screen (blueprint A6 lockup): MILLS bold and
     * tracked, OS TV light, and the one gold element, a short bar under the M.
     */
    static LinearLayout mark(Context c, T t) {
        LinearLayout box = vbox(c);
        LinearLayout row = hbox(c);
        row.setGravity(Gravity.BOTTOM);
        TextView mills = text(c, "MILLS", 11, (t.text & 0x00FFFFFF) | 0xD9000000, 600);
        mills.setLetterSpacing(0.22f);
        row.addView(mills);
        TextView os = text(c, "OS TV", 11, (t.text & 0x00FFFFFF) | 0xB3000000, 300);
        os.setLetterSpacing(0.07f);
        LinearLayout.LayoutParams op = lp(-2, -2);
        op.leftMargin = dp(c, 6);
        row.addView(os, op);
        box.addView(row, lp(-2, -2));   // wrap: a match_parent row would shrink to the 7dp bar
        View bar = new View(c);
        bar.setBackground(pill(c, t.gold));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(c, 7), dp(c, 1.5f));
        bp.topMargin = dp(c, 3);
        bp.leftMargin = dp(c, 0.5f);
        box.addView(bar, bp);
        return box;
    }

    /** Breadcrumb: a round back glyph then HOME / MISSION CONTROL / AI TEAM, last part bright. */
    static LinearLayout crumb(Context c, T t, String... parts) {
        LinearLayout row = hbox(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(c, "‹", 10, t.text, 400);
        back.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Math.max(1, dp(c, 0.5f)), t.glassEdge);
        back.setBackground(ring);
        int s = dp(c, 20);
        row.addView(back, new LinearLayout.LayoutParams(s, s));
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                TextView sl = text(c, "/", 10, t.dim, 600);
                sl.setAlpha(0.5f);
                LinearLayout.LayoutParams p = lp(-2, -2); p.leftMargin = dp(c, 8);
                row.addView(sl, p);
            }
            boolean last = i == parts.length - 1;
            TextView v = text(c, parts[i], 10, last ? t.text : (t.light ? 0x8C0B0F14 : 0x8CF2F4F7), 600);
            v.setLetterSpacing(0.16f);
            LinearLayout.LayoutParams p = lp(-2, -2); p.leftMargin = dp(c, i == 0 ? 10 : 8);
            row.addView(v, p);
        }
        return row;
    }

    static TextClock smallClock(Context c, T t) {
        TextClock tc = new TextClock(c);
        tc.setFormat12Hour("h:mm");
        tc.setFormat24Hour("H:mm");
        tc.setTextSize(18);
        tc.setTextColor(t.text);
        tc.setTypeface(outfit(c));
        tc.setFontVariationSettings("'wght' 300");
        tc.setIncludeFontPadding(false);
        return tc;
    }

    /** Foot hints bottom-right: [OK] open agent  [▲] command center. */
    static LinearLayout foot(Context c, T t, String... keyThenLabel) {
        LinearLayout row = hbox(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i + 1 < keyThenLabel.length; i += 2) {
            TextView k = text(c, keyThenLabel[i], 7.5f, t.light ? 0xB30B0F14 : 0xB3F2F4F7, 600);
            k.setBackground(round(c, 0, 3, t.glassEdge));
            k.setPadding(dp(c, 4.5f), dp(c, 1), dp(c, 4.5f), dp(c, 1));
            LinearLayout.LayoutParams kp = lp(-2, -2); kp.leftMargin = dp(c, i == 0 ? 0 : 17);
            row.addView(k, kp);
            TextView l = text(c, keyThenLabel[i + 1], 9, t.light ? 0x730B0F14 : 0x73F2F4F7, 400);
            LinearLayout.LayoutParams lp2 = lp(-2, -2); lp2.leftMargin = dp(c, 4);
            row.addView(l, lp2);
        }
        return row;
    }

    static void removeAllFocus(ViewGroup g) {
        g.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        g.setFocusable(false);
    }
}
