package com.gathin.tvhome;

import android.content.pm.PackageManager;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * System: "is the TV itself healthy?" A balanced 2x2 of live, on-device readings only:
 * storage as one segmented bar (the ring read as a spinner), memory, privacy & updates, and
 * display. Read-only: changing anything happens in Settings.
 */
public class SystemActivity extends Screen {

    private LinearLayout storage, memory, privacy, display;


    @Override protected String[] crumb() { return new String[]{"HOME", "SYSTEM"}; }

    @Override
    protected void build() {
        LinearLayout col = Ui.vbox(this);
        col.addView(title("System", null));
        LinearLayout r1 = Ui.hbox(this), r2 = Ui.hbox(this);
        storage = cell(r1, 1.25f, 0); memory = cell(r1, 1f, 14);
        privacy = cell(r2, 1.25f, 0); display = cell(r2, 1f, 14);
        col.addView(r1, Ui.lpTop(this, -1, -2, 14));
        col.addView(r2, Ui.lpTop(this, -1, -2, 12));
        body.addView(col, new FrameLayout.LayoutParams(-1, -2));
        foot("◀ ▶", "move", "BACK", "command center");
        storage.requestFocus();    // entering System: focus starts on Storage (rules §4)
    }

    private LinearLayout cell(LinearLayout row, float w, int left) {
        Plate p = Plate.of(this, t, Plate.Kind.TILE);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(Ui.dp(this, 18), Ui.dp(this, 15), Ui.dp(this, 18), Ui.dp(this, 15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 158), w);
        lp.leftMargin = Ui.dp(this, left);
        row.addView(p, lp);
        return p;
    }

    @Override
    protected void refresh() {
        Live.Storage st = Live.storage();
        storage.removeAllViews();
        storage.addView(Ui.eyebrow(this, t, "Storage"));
        LinearLayout big = Ui.hbox(this);
        big.setGravity(Gravity.BOTTOM);
        big.addView(Ui.text(this, st.pct() + "%", 32, t.text, 600));
        TextView of = Ui.text(this, "  of " + Live.gb(st.total) + " used", 12, t.dim, 300);
        of.setPadding(0, 0, 0, Ui.dp(this, 5));
        big.addView(of);
        storage.addView(big, Ui.lpTop(this, -2, -2, 6));
        LinearLayout bar = Ui.hbox(this);
        bar.setBackground(Ui.pill(this, t.light ? 0x1F0B0F14 : 0xFF1C2733));
        bar.setClipToOutline(true);
        View used = new View(this);
        used.setBackgroundColor(st.pct() >= 85 ? t.red : t.gold);
        bar.addView(used, new LinearLayout.LayoutParams(0, -1, Math.max(1, st.pct())));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, Math.max(1, 100 - st.pct())));
        storage.addView(bar, Ui.lpTop(this, -1, Ui.dp(this, 6), 12));
        LinearLayout leg = Ui.hbox(this);
        leg.addView(legend(t.gold, Live.gb(st.total - st.free), "used"));
        View sp = new View(this); leg.addView(sp, new LinearLayout.LayoutParams(Ui.dp(this, 22), 1));
        leg.addView(legend(t.light ? 0x330B0F14 : 0xFF2A3644, Live.gb(st.free), "free"));
        storage.addView(leg, Ui.lpTop(this, -2, -2, 10));
        storage.addView(Ui.chip(this, t, st.pct() >= 85 ? Ui.Status.BAD : st.pct() >= 75 ? Ui.Status.WARN : Ui.Status.OK,
                st.pct() >= 85 ? "Low on space" : st.pct() >= 75 ? "Getting full" : "Room to spare"), Ui.lpTop(this, -2, Ui.dp(this, 20), 9));

        long[] ram = Live.ram(this);
        memory.removeAllViews();
        memory.addView(Ui.eyebrow(this, t, "Memory"));
        memory.addView(Ui.text(this, Live.gb(ram[2]) + " free", 24, t.text, 500), Ui.lpTop(this, -2, -2, 8));
        memory.addView(Ui.text(this, "of " + Live.gb(ram[1]) + " RAM · " + Runtime.getRuntime().availableProcessors() + " cores",
                10.5f, t.dim, 300), Ui.lpTop(this, -2, -2, 4));
        double freeK = ram[1] > 0 ? ram[2] / (double) ram[1] : 0;
        memory.addView(Ui.chip(this, t, freeK > 0.3 ? Ui.Status.OK : Ui.Status.WARN, freeK > 0.3 ? "Running smooth" : "Memory tight"),
                Ui.lpTop(this, -2, Ui.dp(this, 20), 9));
        memory.addView(Ui.kv(this, t, "Up since restart", Live.uptime(), 0, 10), Ui.lpTop(this, -1, -2, 8));

        privacy.removeAllViews();
        privacy.addView(Ui.eyebrow(this, t, "Privacy & updates"));
        int off = 0;
        PackageManager pm = getPackageManager();
        try {
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
                if (!ai.enabled) off++;
            }
        } catch (Exception ignored) {}
        privacy.addView(Ui.text(this, off + (off == 1 ? " app" : " apps") + " turned off", 18, t.text, 500), Ui.lpTop(this, -2, -2, 8));
        LinearLayout chips = Ui.hbox(this);
        boolean frozen = Live.updatesFrozen(this);
        chips.addView(Ui.chip(this, t, frozen ? Ui.Status.WARN : Ui.Status.OK, frozen ? "Updates frozen" : "Updates on"));
        String dns = Live.privateDnsSetting(this);
        LinearLayout dc = Ui.chip(this, t, dns.contains(".") ? Ui.Status.OK : Ui.Status.IDLE, dns.contains(".") ? "Ad-block DNS" : "DNS " + dns);
        LinearLayout.LayoutParams dcp = Ui.lp(-2, Ui.dp(this, 20)); dcp.leftMargin = Ui.dp(this, 6);
        chips.addView(dc, dcp);
        privacy.addView(chips, Ui.lpTop(this, -2, Ui.dp(this, 20), 9));
        privacy.addView(Ui.text(this, "turned off, not deleted · Settings › System › Original brings them back",
                8.5f, t.dim, 300), Ui.lpTop(this, -1, -2, 9));

        display.removeAllViews();
        display.addView(Ui.eyebrow(this, t, "Display & device"));
        String now = "—", hdr = "SDR";
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Display.Mode m = d.getMode();
            now = (m.getPhysicalWidth() >= 3840 ? "4K" : m.getPhysicalHeight() + "p") + " · " + Math.round(m.getRefreshRate()) + " Hz";
            Display.HdrCapabilities hc = d.getHdrCapabilities();
            if (hc != null && hc.getSupportedHdrTypes() != null && hc.getSupportedHdrTypes().length > 0) hdr = "HDR capable";
        } catch (Exception ignored) {}
        display.addView(Ui.text(this, now, 18, t.text, 500), Ui.lpTop(this, -2, -2, 8));
        display.addView(Ui.chip(this, t, Ui.Status.IDLE, hdr), Ui.lpTop(this, -2, Ui.dp(this, 20), 9));
        display.addView(Ui.kv(this, t, "Model", Build.MODEL, 0, 10), Ui.lpTop(this, -1, -2, 8));
        display.addView(Ui.kv(this, t, "Android", Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT, 0, 10), Ui.lpTop(this, -1, -2, 4));
    }

    private LinearLayout legend(int color, String value, String word) {
        LinearLayout l = Ui.hbox(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        View sw = new View(this);
        sw.setBackground(Ui.round(this, color, 2, 0));
        int s = Ui.dp(this, 8);
        l.addView(sw, new LinearLayout.LayoutParams(s, s));
        TextView v = Ui.text(this, "  " + value, 10.5f, t.text, 500);
        l.addView(v);
        l.addView(Ui.text(this, " " + word, 10.5f, t.dim, 300));
        return l;
    }
}
