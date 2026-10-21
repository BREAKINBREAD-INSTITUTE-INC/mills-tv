package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One camera: its latest snapshot large, what it reports, and its events today. "New snapshot"
 * asks the camera for a fresh picture through the bridge (at most once a minute; it costs the
 * camera some battery). Nothing here arms, disarms or changes a Blink setting.
 */
public class CameraActivity extends Screen {

    static final String EXTRA = "camera";
    private String name;
    private ImageView shot;
    private TextView shotAge, note;
    private LinearLayout side;

    @Override
    protected String[] crumb() {
        name = getIntent().getStringExtra(EXTRA);
        if (name == null) name = "Camera";
        return new String[]{"HOME", "CAMERAS", name.toUpperCase(java.util.Locale.US)};
    }

    @Override
    protected void build() {
        LinearLayout cols = Ui.hbox(this);
        LinearLayout left = Ui.vbox(this);
        left.setClipChildren(false);
        cols.addView(left, new LinearLayout.LayoutParams(Ui.dp(this, 520), -2));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF0B0F14);
        final float r = Ui.dp(this, Ui.R_CARD);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        frame.setClipToOutline(true);
        shot = new ImageView(this);
        shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(shot, new FrameLayout.LayoutParams(-1, -1));
        shotAge = Ui.text(this, "loading snapshot…", 9, 0xE6FFFFFF, 500);
        shotAge.setBackground(Ui.pill(this, 0x99000000));
        shotAge.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        frame.addView(shotAge, Ui.at(this, Gravity.BOTTOM | Gravity.START, 10, 0, 0, 10));
        left.addView(frame, new LinearLayout.LayoutParams(-1, Ui.dp(this, 292)));

        LinearLayout row = Ui.hbox(this);
        row.setClipChildren(false);
        Plate snap = Plate.of(this, t, Plate.Kind.BUTTON);
        snap.setGravity(Gravity.CENTER);
        snap.setPadding(Ui.dp(this, 15), 0, Ui.dp(this, 15), 0);
        snap.addView(Ui.text(this, "New snapshot", 11.5f, t.text, 500));
        snap.setOnClickListener(v -> askSnap());
        snap.setOnKeyListener((v, key, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP));
        row.addView(snap, new LinearLayout.LayoutParams(-2, Ui.dp(this, 34)));
        note = Ui.text(this, "  uses a little battery · once a minute at most", 9, t.dim, 300);
        row.addView(note);
        row.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(row, Ui.lpTop(this, -1, -2, 14));

        side = Ui.vbox(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
        sp.leftMargin = Ui.dp(this, 24);
        cols.addView(side, sp);
        body.addView(cols, new FrameLayout.LayoutParams(-1, -1));
        foot("OK", "new snapshot", "BACK", "cameras");
        snap.requestFocus();
        render(Live.cams());
    }

    @Override
    protected void refresh() {
        new Thread(() -> {
            Live.Cams c = Live.fetchCamsBlocking(getApplicationContext());
            Bitmap b = Live.camImage(getApplicationContext(), "/thumb/" + android.net.Uri.encode(name), 1);
            main.post(() -> {
                if (b != null) shot.setImageBitmap(b);
                render(c);
            });
        }, "mills-cam").start();
    }

    private void askSnap() {
        note.setText("  asking the camera…");
        new Thread(() -> {
            String msg = Live.requestSnap(getApplicationContext(), name);
            main.post(() -> note.setText("  " + msg));
            try { Thread.sleep(16_000); } catch (InterruptedException ignored) {}
            main.post(this::refresh);
        }, "mills-snap").start();
    }

    private void render(Live.Cams c) {
        Live.Cam m = null;
        for (Live.Cam x : c.cams) if (x.name.equals(name)) m = x;
        side.removeAllViews();
        TextView title = Ui.single(Ui.text(this, name, 28, t.text, 300));
        side.addView(title);
        side.addView(Ui.text(this, m == null ? c.chip() : m.doorbell ? "Video Doorbell" : "Outdoor camera", 11, t.dim, 300), Ui.lpTop(this, -2, -2, 4));
        Live.CamEvent last = c.lastFor(name);
        shotAge.setText(last != null ? "latest snapshot · " + last.what() + " " + Live.ago(last.at) : "latest snapshot Blink has");

        LinearLayout facts = glass(14);
        facts.addView(Ui.kv(this, t, "Status", m == null ? "—" : !m.online ? "offline" : "online", m != null && m.online ? t.tealText : 0, 10));
        facts.addView(Ui.kv(this, t, "System", m == null || m.armed == null ? "—" : m.armed ? "armed" : "disarmed", 0, 10), Ui.lpTop(this, -1, -2, 6));
        facts.addView(Ui.kv(this, t, "Motion alerts", m == null || m.motion == null ? "—" : m.motion ? "on" : "off", 0, 10), Ui.lpTop(this, -1, -2, 6));
        facts.addView(Ui.kv(this, t, "Battery", m == null || m.battery == null ? "—" : m.battery, m != null && "low".equalsIgnoreCase(m.battery) ? t.goldText : 0, 10), Ui.lpTop(this, -1, -2, 6));
        facts.addView(Ui.kv(this, t, "Temperature", m == null || m.tempF == null ? "—" : m.tempF + "°F", 0, 10), Ui.lpTop(this, -1, -2, 6));
        facts.addView(Ui.kv(this, t, "Wi-Fi", m == null || m.wifi == null ? "—" : m.wifi.matches("-?\\d+") ? m.wifi + " dBm" : m.wifi, 0, 10), Ui.lpTop(this, -1, -2, 6));
        side.addView(facts, Ui.lpTop(this, -1, -2, 14));

        LinearLayout ev = glass(14);
        TextView h = Ui.text(this, "LAST 24 HOURS", 7, t.dim, 600);
        h.setLetterSpacing(0.16f);
        ev.addView(h);
        int n = 0;
        for (Live.CamEvent e : c.events) {
            if (!name.equals(e.camera)) continue;
            LinearLayout r = Ui.hbox(this);
            r.addView(Ui.dot(this, e.ring ? t.gold : t.teal, 5));
            TextView w = Ui.single(Ui.text(this, "  " + e.what() + (e.summary != null && !e.summary.isEmpty() ? " · " + e.summary : ""), 10, t.text, 400));
            r.addView(w, new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(Ui.text(this, Live.ago(e.at), 9, t.dim, 300));
            r.setGravity(Gravity.CENTER_VERTICAL);
            ev.addView(r, Ui.lpTop(this, -1, -2, 7));
            if (++n >= 6) break;
        }
        if (n == 0) ev.addView(Ui.text(this, c.fresh() ? "No events in the last 24 hours." : c.chip(), 10, t.dim, 300), Ui.lpTop(this, -1, -2, 7));
        side.addView(ev, Ui.lpTop(this, -1, -2, 12));
    }
}
