package com.gathin.tvhome;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cameras: every Blink camera and the doorbell in one grid, each with its latest snapshot, a dot
 * and a word for online and armed (green good, orange partial, red down, grey no reading), battery
 * and Wi-Fi, the last motion and today's clips. The header sums it up, archive included. Read
 * through the Blink bridge on the hub. Snapshots are Blink's latest; nothing here says "live".
 * The grid is built once and updated in place, so a refresh never moves the focus.
 */
public class CamerasActivity extends Screen {

    private LinearLayout summary;
    private TextView feed;
    private GridLayout grid;
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final Map<String, Tile> tiles = new HashMap<>();
    private String shownNames = "";
    private static String lastCam;

    /** One camera's tile and the views a refresh rewrites. */
    private static final class Tile {
        Plate plate;
        FrameLayout shot;
        ImageView img;
        TextView none, sub;
        LinearLayout dots, facts;
        boolean compact;   // three rows of tiles: two fact lines instead of four
    }

    @Override protected String[] crumb() { return new String[]{"HOME", "CAMERAS"}; }

    @Override
    protected void build() {
        summary = Ui.hbox(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(title("Cameras", summary), new FrameLayout.LayoutParams(-2, -2));
        feed = Ui.text(this, "", 9, t.dim, 300);
        feed.setMaxWidth(Ui.dp(this, 300));
        feed.setMaxLines(2);
        feed.setGravity(Gravity.END);
        feed.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(feed, Ui.at(this, Gravity.TOP | Gravity.END, 0, 16, 0, 0));
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setClipChildren(false);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, -2);
        gp.topMargin = Ui.dp(this, 70);
        body.addView(grid, gp);
        foot("OK", "clips and snapshot", "BACK", "command center");
        render(Live.cams());
    }

    @Override
    protected void refresh() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Live.Cams c = Live.fetchCamsBlocking(app);
            main.post(() -> render(c));
            // snapshots after the words: the grid shows at once, pictures land as they arrive.
            // A picture is only downloaded when Blink has a new one (Live.camSnapshot), not every poll.
            for (Live.Cam cam : c.cams) {
                if (!cam.hasThumb) continue;
                Bitmap b = Live.camSnapshot(app, cam);
                if (b == null) continue;
                synchronized (thumbs) { if (thumbs.get(cam.name) == b) continue; thumbs.put(cam.name, b); }
                main.post(() -> setShot(cam.name, b));
            }
        }, "mills-cams").start();
    }

    // ---- header ---------------------------------------------------------------------------------

    private void render(Live.Cams c) {
        summary.removeAllViews();
        boolean fresh = c.fresh();
        if (c.cams.isEmpty()) {
            summary.addView(dotWord(Ui.Status.IDLE, c.chip(), 10));
        } else {
            int online = 0;
            long lastMotion = 0;
            for (Live.Cam m : c.cams) {
                if (m.online) online++;
                lastMotion = Math.max(lastMotion, lastMotionOf(m, c));
            }
            int n = c.cams.size();
            add(dotWord(fresh ? Ui.Status.OK : Ui.Status.IDLE, n + (n == 1 ? " camera" : " cameras"), 10));
            add(dotWord(!fresh ? Ui.Status.IDLE : online == n ? Ui.Status.OK : online > 0 ? Ui.Status.WARN : Ui.Status.BAD,
                    online + " online", 10));
            add(dotWord(lastMotion > 0 ? Ui.Status.OK : Ui.Status.IDLE,
                    lastMotion > 0 ? "last motion " + Live.ago(lastMotion) : "no motion known", 10));
            if (c.archiveOk != null || c.archiveClips > 0) {
                boolean ok = !Boolean.FALSE.equals(c.archiveOk);
                String place = c.archivePlace == null ? "the NAS" : c.archivePlace.toUpperCase(Locale.US);
                add(dotWord(ok ? Ui.Status.OK : Ui.Status.WARN,
                        ok ? "archive " + String.format(Locale.US, "%,d", c.archiveClips) + " clips on " + place
                           : "archive · " + Live.shortError(c.archiveError), 10));
            }
            Live.CamEvent ring = c.recentRing(10 * 60_000L);
            if (ring != null) add(dotWord(Ui.Status.WARN, "doorbell " + Live.ago(ring.at), 10));
        }
        feed.setText(fresh ? "Blink · updated " + Live.ago(c.at) : c.chip());

        StringBuilder names = new StringBuilder();
        for (Live.Cam m : c.cams) names.append(m.name).append('\n');
        if (!names.toString().equals(shownNames)) buildGrid(c);
        shownNames = names.toString();
        for (Live.Cam m : c.cams) fill(tiles.get(m.name), m, c);
    }

    private void add(View v) {
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.rightMargin = Ui.dp(this, 15);
        summary.addView(v, lp);
    }

    /** The newer of what the bridge counted (archive + cloud) and the latest event it listed. */
    private static long lastMotionOf(Live.Cam m, Live.Cams c) {
        Live.CamEvent e = c.lastFor(m.name);
        return Math.max(m.lastMotionAt, e != null && !"snapshot".equals(e.what()) ? e.at : 0);
    }

    // ---- grid -----------------------------------------------------------------------------------

    /** Two across; rows share the height, so 4, 5 or 6 cameras still fit without scrolling. */
    private void buildGrid(Live.Cams c) {
        View had = getCurrentFocus();
        String keep = had != null && had.getTag() instanceof String ? (String) had.getTag() : lastCam;
        grid.removeAllViews();
        tiles.clear();
        final int n = c.cams.size();
        if (n == 0) return;
        final int rows = (n + 1) / 2;
        int gap = Ui.dp(this, 12);
        int w = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 128) - Ui.dp(this, 14)) / 2;
        int avail = getResources().getDisplayMetrics().heightPixels - Ui.dp(this, 84 + 62 + 70 + 6);
        int h = Math.min(Ui.dp(this, 152), (avail - gap * (rows - 1)) / rows);
        Plate focusMe = null;
        for (int i = 0; i < n; i++) {
            Live.Cam m = c.cams.get(i);
            Tile tl = tile(m, h);
            tl.compact = h < Ui.dp(this, 132);
            tiles.put(m.name, tl);
            final int col = i % 2, row = i / 2, idx = i;
            // never wrap: the edges of the grid stop the D-pad instead of jumping elsewhere
            tl.plate.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (key) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:  return col == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: return col == 1 || idx == n - 1;
                    case KeyEvent.KEYCODE_DPAD_UP:    return row == 0;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (row == rows - 1) return true;
                        if (idx + 2 >= n) { grid.getChildAt(n - 1).requestFocus(); return true; }   // odd last row
                        return false;
                    default: return false;
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = h;
            lp.rightMargin = col == 0 ? Ui.dp(this, 14) : 0;
            lp.bottomMargin = gap;
            grid.addView(tl.plate, lp);
            if (focusMe == null || m.name.equals(keep)) focusMe = tl.plate;
        }
        if (focusMe != null) focusMe.requestFocus();
    }

    private Tile tile(final Live.Cam m, int h) {
        Tile tl = new Tile();
        Plate p = Plate.of(this, t, Plate.Kind.TILE);
        tl.plate = p;
        p.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.dp(this, 10);
        p.setPadding(pad, pad, Ui.dp(this, 14), pad);
        p.setTag(m.name);
        p.onFocus((pl, has) -> { if (has) lastCam = m.name; });
        p.setOnClickListener(v -> {
            startActivity(new Intent(this, CameraActivity.class).putExtra(CameraActivity.EXTRA, m.name));
            overridePendingTransition(0, 0);
        });

        int sh = h - 2 * pad, sw = Math.round(sh * 16f / 9f);
        tl.shot = snapshotFrame();
        tl.img = new ImageView(this);
        tl.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tl.shot.addView(tl.img, new FrameLayout.LayoutParams(-1, -1));
        tl.none = Ui.text(this, "no snapshot yet", 9, 0x99F2F4F7, 300);
        tl.shot.addView(tl.none, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        if (m.doorbell) {   // the doorbell is marked on its picture, in gold, like the banner
            TextView tag = Ui.text(this, "DOORBELL", 7, t.ink, 700);
            tag.setLetterSpacing(0.14f);
            tag.setBackground(Ui.pill(this, t.gold));
            tag.setPadding(Ui.dp(this, 7), Ui.dp(this, 2), Ui.dp(this, 7), Ui.dp(this, 2));
            tl.shot.addView(tag, Ui.at(this, Gravity.BOTTOM | Gravity.START, 7, 0, 0, 7));
        }
        Bitmap b;
        synchronized (thumbs) { b = thumbs.get(m.name); }
        if (b == null) b = Live.camSnapshotCached(m.name);   // shown at once on a second visit
        if (b != null) { tl.img.setImageBitmap(b); tl.none.setVisibility(View.GONE); }
        p.addView(tl.shot, new LinearLayout.LayoutParams(sw, sh));

        LinearLayout info = Ui.vbox(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -1, 1f);
        ip.leftMargin = Ui.dp(this, 14);
        p.addView(info, ip);
        info.addView(Ui.single(Ui.text(this, m.label(), 15, t.text, 600)));
        tl.sub = Ui.single(Ui.text(this, "", 9, t.dim, 300));
        info.addView(tl.sub, Ui.lpTop(this, -2, -2, 2));
        tl.dots = Ui.hbox(this);
        info.addView(tl.dots, Ui.lpTop(this, -1, -2, 7));
        info.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        tl.facts = Ui.vbox(this);
        info.addView(tl.facts, Ui.lpTop(this, -1, -2, 4));
        return tl;
    }

    /** Rewrite a tile's words in place (no relayout of the grid, no focus change). */
    private void fill(Tile tl, Live.Cam m, Live.Cams c) {
        if (tl == null) return;
        boolean fresh = c.fresh();
        tl.sub.setText((m.doorbell ? "Video Doorbell" : "sedona".equals(m.type) ? "Outdoor 4" : "Outdoor camera")
                + (m.tempF != null ? " · " + m.tempF + "°F" : ""));

        tl.dots.removeAllViews();
        String on = !fresh ? Ui.Status.IDLE : !m.online ? Ui.Status.BAD
                : m.batteryLow() || m.wifiWeak() ? Ui.Status.WARN : Ui.Status.OK;
        tl.dots.addView(dotWord(on, !fresh ? "no reading" : !m.online ? "offline" : "online", 9.5f));
        String arm = !fresh || m.armed == null ? Ui.Status.IDLE
                : !m.armed || Boolean.FALSE.equals(m.motion) ? Ui.Status.WARN : Ui.Status.OK;
        LinearLayout.LayoutParams ap = Ui.lp(-2, -2); ap.leftMargin = Ui.dp(this, 12);
        tl.dots.addView(dotWord(arm, m.armed == null ? "armed —" : !m.armed ? "disarmed"
                : Boolean.FALSE.equals(m.motion) ? "motion off" : "armed", 9.5f), ap);

        tl.facts.removeAllViews();
        String wifi = m.wifiWord();
        long lm = lastMotionOf(m, c);
        String today = m.clipsToday == 1 ? "1 clip" : m.clipsToday + " clips";
        if (tl.compact) {
            tl.facts.addView(Ui.kv(this, t, "Battery " + (m.battery == null ? "—" : m.battery),
                    wifi == null ? "" : "Wi-Fi " + wifi, m.wifiWeak() ? t.orangeText : 0, 9));
            tl.facts.addView(Ui.kv(this, t, "Motion " + (lm > 0 ? Live.ago(lm) : "none known"), "today " + today, 0, 9),
                    Ui.lpTop(this, -1, -2, 3));
            return;
        }
        tl.facts.addView(Ui.kv(this, t, "Battery", m.battery == null ? "—" : m.battery, m.batteryLow() ? t.orangeText : 0, 9));
        tl.facts.addView(Ui.kv(this, t, "Wi-Fi", wifi == null ? (m.doorbell ? "not reported" : "—") : wifi,
                m.wifiWeak() ? t.orangeText : 0, 9), Ui.lpTop(this, -1, -2, 3));
        tl.facts.addView(Ui.kv(this, t, "Last motion", lm > 0 ? Live.ago(lm) : "none known", 0, 9), Ui.lpTop(this, -1, -2, 3));
        tl.facts.addView(Ui.kv(this, t, "Today", today, 0, 9), Ui.lpTop(this, -1, -2, 3));
    }

    private void setShot(String name, Bitmap b) {
        Tile tl = tiles.get(name);
        if (tl == null) return;
        tl.img.setImageBitmap(b);
        tl.none.setVisibility(View.GONE);
    }

    private FrameLayout snapshotFrame() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xFF0B0F14);
        final float r = Ui.dp(this, 8);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        f.setClipToOutline(true);
        return f;
    }
}
