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
import java.util.Map;

/**
 * Cameras: every Blink camera and the doorbell, 2 across, each with its latest snapshot, armed
 * state, battery and when it last saw something. Read through the Blink bridge on the home hub.
 * Snapshots are Blink's latest, labelled with their age; nothing here claims to be live video.
 */
public class CamerasActivity extends Screen {

    private LinearLayout summary;
    private TextView feed;
    private GridLayout grid;
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private static String lastCam;

    @Override protected String[] crumb() { return new String[]{"HOME", "CAMERAS"}; }

    @Override
    protected void build() {
        summary = Ui.hbox(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(title("Cameras", summary), new FrameLayout.LayoutParams(-2, -2));
        feed = Ui.text(this, "", 9, t.dim, 300);
        feed.setMaxWidth(Ui.dp(this, 460));   // never under the title, whatever the bridge says
        feed.setMaxLines(2);
        feed.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(feed, Ui.at(this, Gravity.TOP | Gravity.END, 0, 16, 0, 0));
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, -2);
        gp.topMargin = Ui.dp(this, 72);
        body.addView(grid, gp);
        foot("OK", "open camera", "BACK", "command center");
        render(Live.cams());
    }

    @Override
    protected void refresh() {
        new Thread(() -> {
            Live.Cams c = Live.fetchCamsBlocking(getApplicationContext());
            for (Live.Cam cam : c.cams) {
                if (!cam.hasThumb) continue;
                Bitmap b = Live.camImage(getApplicationContext(), "/thumb/" + android.net.Uri.encode(cam.name), 2);
                if (b != null) synchronized (thumbs) { thumbs.put(cam.name, b); }
            }
            main.post(() -> render(c));
        }, "mills-cams").start();
    }

    private void render(Live.Cams c) {
        summary.removeAllViews();
        boolean fresh = c.fresh();
        if (c.cams.isEmpty()) {
            summary.addView(dotWord(Ui.Status.IDLE, c.chip(), 10));
        } else {
            int armed = 0, low = 0;
            for (Live.Cam m : c.cams) { if (Boolean.TRUE.equals(m.armed)) armed++; if ("low".equalsIgnoreCase(m.battery)) low++; }
            add(dotWord(fresh ? Ui.Status.OK : Ui.Status.IDLE, c.cams.size() + " cameras", 10));
            add(dotWord(armed > 0 ? Ui.Status.OK : Ui.Status.IDLE, armed > 0 ? "armed" : "disarmed", 10));
            if (low > 0) add(dotWord(Ui.Status.WARN, low + " low battery", 10));
            Live.CamEvent ring = c.lastRing();
            if (ring != null) add(dotWord(c.recentRing(10 * 60_000L) != null ? Ui.Status.WARN : Ui.Status.IDLE, "doorbell " + Live.ago(ring.at), 10));
        }
        String nas = c.archiveLine();
        feed.setText((fresh ? "Blink · updated " + Live.ago(c.at) : c.chip()) + (nas != null ? "\n" + nas : ""));
        feed.setGravity(Gravity.END);

        View had = getCurrentFocus();
        String keep = had != null && had.getTag() instanceof String ? (String) had.getTag() : lastCam;
        grid.removeAllViews();
        int w = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 128) - Ui.dp(this, 14)) / 2;
        Plate focusMe = null;
        for (int i = 0; i < c.cams.size(); i++) {
            Live.Cam m = c.cams.get(i);
            Plate p = tile(m, c, w);
            final int col = i % 2, n = c.cams.size(), idx = i;
            p.setOnKeyListener((v, key, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                    && ((key == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) || (key == KeyEvent.KEYCODE_DPAD_RIGHT && (col == 1 || idx == n - 1))));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = Ui.dp(this, 150);
            lp.rightMargin = col == 0 ? Ui.dp(this, 14) : 0;
            lp.bottomMargin = Ui.dp(this, 12);
            grid.addView(p, lp);
            if (focusMe == null || m.name.equals(keep)) focusMe = p;
        }
        if (focusMe != null) focusMe.requestFocus();
    }

    private void add(View v) {
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.rightMargin = Ui.dp(this, 15);
        summary.addView(v, lp);
    }

    private Plate tile(final Live.Cam m, Live.Cams c, int w) {
        Plate p = Plate.of(this, t, Plate.Kind.TILE);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        p.setTag(m.name);
        p.onFocus((pl, has) -> { if (has) lastCam = m.name; });
        p.setOnClickListener(v -> {
            startActivity(new Intent(this, CameraActivity.class).putExtra(CameraActivity.EXTRA, m.name));
            overridePendingTransition(0, 0);
        });

        FrameLayout shot = snapshotView(m.name);
        int sh = Ui.dp(this, 130), sw = Math.round(sh * 16f / 9f);
        p.addView(shot, new LinearLayout.LayoutParams(sw, sh));

        LinearLayout info = Ui.vbox(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -1, 1f);
        ip.leftMargin = Ui.dp(this, 14);
        p.addView(info, ip);
        info.addView(Ui.single(Ui.text(this, m.name, 16, t.text, 600)));
        info.addView(Ui.text(this, m.doorbell ? "Video Doorbell" : "Outdoor camera", 9.5f, t.dim, 300), Ui.lpTop(this, -2, -2, 2));
        boolean fresh = c.fresh();
        LinearLayout chips = Ui.hbox(this);
        String st = !fresh ? Ui.Status.IDLE : !m.online ? Ui.Status.BAD : Ui.Status.OK;
        chips.addView(Ui.chip(this, t, st, !fresh ? "last reading" : !m.online ? "offline" : Boolean.TRUE.equals(m.armed) ? "armed" : "online"));
        if ("low".equalsIgnoreCase(m.battery)) {
            LinearLayout.LayoutParams cp = Ui.lp(-2, Ui.dp(this, 20)); cp.leftMargin = Ui.dp(this, 5);
            chips.addView(Ui.chip(this, t, Ui.Status.WARN, "battery low"), cp);
        }
        info.addView(chips, Ui.lpTop(this, -2, Ui.dp(this, 20), 8));
        View sp = new View(this);
        info.addView(sp, new LinearLayout.LayoutParams(1, 0, 1f));
        Live.CamEvent last = c.lastFor(m.name);
        info.addView(Ui.single(Ui.text(this, last != null ? last.what() + " · " + Live.ago(last.at) : "no events in 24 hours", 10, t.text, 400)));
        String facts = (m.tempF != null ? m.tempF + "°F" : "") + (m.battery != null ? (m.tempF != null ? " · " : "") + "battery " + m.battery : "");
        if (!facts.isEmpty()) info.addView(Ui.single(Ui.text(this, facts, 9, t.dim, 300)), Ui.lpTop(this, -2, -2, 2));
        return p;
    }

    private FrameLayout snapshotView(String name) {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xFF0B0F14);
        final float r = Ui.dp(this, 8);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        f.setClipToOutline(true);
        Bitmap b;
        synchronized (thumbs) { b = thumbs.get(name); }
        if (b != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(b);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView none = Ui.text(this, "no snapshot yet", 9, 0x99F2F4F7, 300);
            f.addView(none, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        return f;
    }
}
