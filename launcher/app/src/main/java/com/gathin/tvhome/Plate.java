package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

/**
 * Every focusable surface in MILLS TV: command-center cards, settings rows, agent tiles, buttons,
 * thumbnails, editor slots. One shape grammar (blueprint D4/D5): the surface, a gold edge when
 * focused, and the same 24x2dp gold underline everywhere. Focus animates scale and alpha only,
 * never layout. Children are content; the plate itself takes focus.
 *
 * The Glass look (Prefs.look, JJ Oct 25, after the Lumen layouts he liked): cards, tiles, buttons
 * and rows are frosted pills and rounded cards, and the focused one turns solid white and lifts a
 * little, with no ring or underline. On Night Glass the text inside a focused plate takes the
 * light theme's colours for as long as it has focus, so it reads on the white. Gold stays for a
 * lifted card and the one primary button.
 */
public class Plate extends LinearLayout {

    enum Kind { CARD, ROW, TILE, BUTTON, PRIMARY, THUMB, SLOT, APP }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;

    private Kind kind = Kind.ROW;
    private Ui.T t;
    private float radius, pad;
    private int restFill, focusFill, restStroke, focusStroke, focusRing;
    private float focusScale = 1f;
    private boolean underlineCenter, underlineOutside;
    private float underlineX, underlineBottom;
    private boolean lifted;           // editor: picked-up slot
    private boolean whiteFocus;       // Glass look: the focus is a solid white fill, no underline
    private final java.util.Map<android.widget.TextView, int[]> inked = new java.util.HashMap<>();   // {own colour, colour on white}
    private boolean inactive;
    private OnFocus onFocus;

    interface OnFocus { void focus(Plate p, boolean has); }

    public Plate(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setFocusable(true);
        setClickable(true);
        setClipToPadding(false);
        stroke.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
    }

    static Plate of(Context c, Ui.T t, Kind kind) {
        Plate p = new Plate(c);
        p.setKind(t, kind);
        return p;
    }

    Plate setKind(Ui.T t, Kind kind) {
        this.t = t; this.kind = kind;
        int glass = t.glass, edge = t.glassEdge, gold = t.gold;
        switch (kind) {
            case CARD:     // command center: panel 82%, 1px edge; focus = gold stroke + outer gold
                radius = 14; pad = 1.5f; restFill = t.panel; focusFill = t.panel;
                restStroke = t.edge; focusStroke = gold; focusRing = (gold & 0x00FFFFFF) | 0x40000000;
                focusScale = 1f; underlineX = 16; underlineBottom = 9; break;
            case TILE:     // agent roster tile: glass; focus = gold 60% edge, 1.03
                radius = 14; pad = 1.5f; restFill = glass; focusFill = glass;
                restStroke = edge; focusStroke = (gold & 0x00FFFFFF) | 0x99000000; focusRing = 0;
                focusScale = 1.03f; underlineX = 16; underlineBottom = 8; break;
            case BUTTON:   // pill, glass; focus = white ring + 1.05 + underline below
                radius = 999; pad = 2; restFill = glass; focusFill = t.light ? 0x33FFFFFF : 0x24FFFFFF;
                restStroke = edge; focusStroke = t.light ? 0xD90B0F14 : 0xE6FFFFFF; focusRing = (gold & 0x00FFFFFF) | 0x40000000;
                focusScale = 1.05f; underlineCenter = true; underlineOutside = true; underlineBottom = -7; break;
            case PRIMARY:  // gold pill (one per screen, only while something is pending)
                radius = 999; pad = 2; restFill = gold; focusFill = gold; restStroke = gold;
                focusStroke = t.light ? 0xD90B0F14 : 0xE6FFFFFF; focusRing = (gold & 0x00FFFFFF) | 0x40000000;
                focusScale = 1.05f; underlineCenter = true; underlineOutside = true; underlineBottom = -7; break;
            case THUMB:    // wallpaper thumbnail
                radius = 7; pad = 2; restFill = 0; focusFill = 0; restStroke = edge;
                focusStroke = t.light ? 0x590B0F14 : 0x59FFFFFF; focusRing = t.plate;
                focusScale = 1f; underlineX = 6; underlineBottom = 17; break;
            case SLOT:     // apps-on-home slot
                radius = 11; pad = 2; restFill = glass; focusFill = t.light ? 0x52FFFFFF : 0x1FFFFFFF;
                restStroke = edge; focusStroke = gold; focusRing = 0;
                focusScale = 1f; underlineCenter = true; underlineBottom = 6; break;
            case APP:      // all-apps tile in the editor: glass, plate on focus, underline
                radius = 10; pad = 0; restFill = glass; focusFill = t.light ? 0x52FFFFFF : 0x24FFFFFF;
                restStroke = edge; focusStroke = 0; focusRing = 0;
                focusScale = 1f; underlineX = 12; underlineBottom = 4.5f; break;
            case ROW:
            default:       // settings row: plate fill on focus only
                radius = 8; pad = 0; restFill = 0; focusFill = t.plate; restStroke = 0; focusStroke = 0; focusRing = 0;
                focusScale = 1f; underlineX = 12; underlineBottom = 3.5f; break;
        }
        whiteFocus = false;
        if (t.glassLook) switch (kind) {
            case CARD: case TILE: case BUTTON: case ROW:
                whiteFocus = true;
                focusFill = t.focusWhite; focusStroke = 0; focusRing = 0;
                if (kind == Kind.CARD || kind == Kind.TILE) { radius = 22; focusScale = 1.04f; restFill = kind == Kind.CARD ? t.panel : t.glass; restStroke = t.glassEdge; }
                else if (kind == Kind.ROW) { radius = 999; restFill = t.glass; restStroke = t.glassEdge; }
                else { restFill = t.glass; restStroke = t.glassEdge; }
                break;
            default: break;
        }
        bar.setColor(Prefs.accentColor(getContext()));
        invalidate();
        return this;
    }

    Plate underlineAt(float leftDp, float bottomDp) { underlineCenter = false; underlineX = leftDp; underlineBottom = bottomDp; return this; }
    Plate onFocus(OnFocus f) { onFocus = f; return this; }

    /** Visible but skipped by the D-pad (blueprint B4: 42% and focusable=false). */
    Plate setInactive(boolean v) {
        inactive = v;
        setFocusable(!v);
        setClickable(!v);
        setAlpha(v ? 0.42f : 1f);
        return this;
    }

    /** "Needs connection": shown at 45%, still focusable so the reason can be read. */
    Plate setDisconnected(boolean v) { setAlpha(v ? 0.45f : 1f); return this; }

    void setLifted(boolean v) {
        lifted = v;
        float ty = v ? -11 * d : 0, s = v ? 1.06f : 1f;
        animate().translationY(ty).scaleX(s).scaleY(s).setDuration(Motion.ms(getContext(), 160)).start();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (focusScale == 1f && kind != Kind.BUTTON && kind != Kind.PRIMARY) return;
        // Let the focus scale / outside underline draw past the row, but never unclip a
        // scrolling container: that would let its whole content spill over the screen.
        android.view.ViewParent p = getParent();
        for (int i = 0; i < 3 && p instanceof android.view.ViewGroup; i++, p = p.getParent()) {
            if (p instanceof android.widget.ScrollView || p instanceof androidx.recyclerview.widget.RecyclerView) break;
            ((android.view.ViewGroup) p).setClipChildren(false);
            ((android.view.ViewGroup) p).setClipToPadding(false);
            if (p.getParent() instanceof android.widget.ScrollView) break;
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (focusScale != 1f && !lifted) {
            float s = gainFocus ? focusScale : 1f;
            animate().scaleX(s).scaleY(s).setDuration(Motion.ms(getContext(), 180))
                    .setInterpolator(new OvershootInterpolator(0.6f)).start();
        }
        invalidate();
        if (onFocus != null) onFocus.focus(this, gainFocus);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean f = isFocused() || lifted;
        float p = pad * d;
        r.set(p, p, getWidth() - p, getHeight() - p);
        float rad = Math.min(radius * d, r.height() / 2f);
        if (f && focusRing != 0) {
            ring.setColor(focusRing);
            ring.setStrokeWidth(p * 2f);
            c.drawRoundRect(r, rad, rad, ring);
        }
        int fc = f ? focusFill : restFill;
        if (fc != 0) { fill.setColor(fc); c.drawRoundRect(r, rad, rad, fill); }
        int sc = f ? (lifted ? t.gold : focusStroke) : restStroke;
        if (sc != 0) {
            stroke.setColor(sc);
            stroke.setStrokeWidth(Math.max(1f, (f && kind != Kind.TILE && kind != Kind.SLOT ? 1f : 0.5f) * d));
            float h = stroke.getStrokeWidth() / 2f;
            c.drawRoundRect(r.left + h, r.top + h, r.right - h, r.bottom - h, rad, rad, stroke);
        }
        super.onDraw(c);
    }

    /** Night Glass: while focused (solid white), every text inside takes its colour for white. */
    private void inkText(boolean on) {
        if (!on) {
            if (inked.isEmpty()) return;
            for (java.util.Map.Entry<android.widget.TextView, int[]> e : inked.entrySet())
                if (e.getKey().getCurrentTextColor() == e.getValue()[1]) e.getKey().setTextColor(e.getValue()[0]);
            inked.clear();
            return;
        }
        java.util.ArrayDeque<View> q = new java.util.ArrayDeque<>();
        q.add(this);
        while (!q.isEmpty()) {
            View v = q.poll();
            if (v instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) v;
                int cur = tv.getCurrentTextColor();
                int[] k = inked.get(tv);
                if (k == null || cur != k[1]) {        // new, or a refresh set its colour again
                    int w = t.onWhite(cur);
                    inked.put(tv, new int[]{cur, w});
                    if (w != cur) tv.setTextColor(w);
                }
            } else if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (whiteFocus && t.inkOnFocus) inkText(isFocused() || lifted);
        super.dispatchDraw(c);
        if (whiteFocus && !lifted) return;           // Glass: the white fill is the focus
        if (isFocused() && !inactive && kind != Kind.SLOT || lifted) {
            float w = 24 * d, h = 2 * d;
            float left = underlineCenter ? (getWidth() - w) / 2f : underlineX * d;
            float bottom = getHeight() - underlineBottom * d;
            c.drawRoundRect(left, bottom - h, left + w, bottom, d, d, bar);
        }
    }
}
