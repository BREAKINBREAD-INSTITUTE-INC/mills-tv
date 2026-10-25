package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * The dock's frosted glass, to blueprint D2. No live blur (the BRAVIA is Android 12): the frost
 * is a pre-blurred copy of the wallpaper, and this layout draws the part of it that sits under
 * the dock. Stack, bottom to top: frost · glass tint · 1px top highlight · 1px edge · children.
 * Dark glass is white 7.5% / edge 16% / highlight 12%; light glass is white 42% / 70% / 80%.
 * The frost is fixed to the dock rect, so scrolling the glyphs inside never moves the glass.
 */
public class FrostLayout extends LinearLayout {

    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Rect src = new Rect();
    private final float stroke;
    private float radius;
    private Bitmap frost;

    public FrostLayout(Context c) { this(c, null); }

    public FrostLayout(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        radius = 18f * d;                    // r-dock 36 px
        stroke = Math.max(1f, 0.5f * d);     // 1 px on the panel
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setStrokeWidth(stroke);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
        setGlass(false, 0.075f);
    }

    void setRadius(float px) { radius = px; invalidate(); }

    void setGlass(boolean light, float dockAlpha) { setGlass(light, dockAlpha, 0.6f); }

    /**
     * Glass values for the theme and this wallpaper's tint alpha (D3 dockAlpha), at the opacity
     * from Settings > Transparency: below the shipped 0.6 the frost and tint fade to clear glass;
     * above it a solid panel colour fills in on top.
     */
    void setGlass(boolean light, float dockAlpha, float opacity) {
        float k = Math.min(1f, opacity / 0.6f);
        int a = Math.round(255 * (light ? 0.42f : dockAlpha) * k);
        tint.setColor((a << 24) | 0xFFFFFF);
        frostPaint.setAlpha(Math.round(255 * k));
        float firm = Math.max(0f, (opacity - 0.6f) / 0.4f);
        solid.setColor(((Math.round(242 * firm)) << 24) | (light ? 0xF4F7FA : 0x172230));
        highlight.setColor(light ? 0xCCFFFFFF : 0x1FFFFFFF);
        edge.setColor(light ? 0xB3FFFFFF : 0x29FFFFFF);
        invalidate();
    }

    /** The pre-blurred slice for the current wallpaper, or null for tint only. */
    void setFrost(Bitmap b) {
        frost = b;
        updateSource();
        invalidate();
    }

    /** Re-map the frost after this view moved without a layout pass (a scrolled row). */
    void refreshSource() { updateSource(); invalidate(); }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        updateSource();
    }

    /**
     * Map this view's rect on the wallpaper to frost-bitmap pixels. The wallpaper is the nearest
     * ancestor with id "root" (the Home layout), else the window. Positions are summed in layout
     * coordinates up to it, so a scaled copy of Home (the Settings preview at 48%) maps the same as
     * the real one; window coordinates are scaled there and the frost covered only part of the dock.
     */
    private void updateSource() {
        if (frost == null || getWidth() == 0) return;
        float x = 0, y = 0;
        View v = this, root = getRootView();
        while (v != null && v.getId() != R.id.root && v.getParent() instanceof View) {
            View p = (View) v.getParent();
            x += v.getLeft() + v.getTranslationX() - p.getScrollX();
            y += v.getTop() + v.getTranslationY() - p.getScrollY();
            v = p;
        }
        if (v != null && v.getId() == R.id.root) root = v;
        if (root == null || root.getWidth() == 0) return;
        float sx = frost.getWidth() / (float) root.getWidth();
        float sy = frost.getHeight() / (float) root.getHeight();
        src.set(Math.round(x * sx), Math.round(y * sy),
                Math.round((x + getWidth()) * sx), Math.round((y + getHeight()) * sy));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rect.set(0, 0, getWidth(), getHeight());
        float rad = Math.min(radius, getHeight() / 2f);
        clip.reset();
        clip.addRoundRect(rect, rad, rad, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (frost != null && !src.isEmpty()) canvas.drawBitmap(frost, src, rect, frostPaint);
        canvas.drawRect(rect, tint);
        if (solid.getAlpha() > 0) canvas.drawRect(rect, solid);
        canvas.restore();

        float h = stroke / 2f;
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), rad);
        canvas.drawRoundRect(h + stroke, h + stroke, getWidth() - h - stroke, getHeight() - h - stroke,
                rad - stroke, rad - stroke, highlight);
        canvas.restore();
        canvas.drawRoundRect(h, h, getWidth() - h, getHeight() - h, rad, rad, edge);
    }
}
