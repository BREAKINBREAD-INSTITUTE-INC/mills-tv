package com.gathin.tvhome;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Network: "how healthy is the network?" Everything here is checked from the TV, now:
 * the link and whether Android validated internet, Private DNS (the setting, and whether it's
 * actually active), and key devices on the LAN. A device counts as online only if it answered
 * (accepted or refused a connection); silence is "no answer", never assumed healthy.
 * A setting, a checked value and observed traffic are different claims, and are labelled so.
 */
public class NetworkActivity extends Screen {

    private LinearLayout internet, protection, devices;
    private long checkedAt;

    @Override protected String[] crumb() { return new String[]{"HOME", "NETWORK"}; }

    @Override
    protected void build() {
        LinearLayout col = Ui.vbox(this);
        col.addView(title("Network", null));
        LinearLayout row = Ui.hbox(this);
        internet = card(row, 1.1f, 0);
        protection = card(row, 1f, 14);
        col.addView(row, Ui.lpTop(this, -1, -2, 14));
        devices = Plate.of(this, t, Plate.Kind.TILE);
        devices.setOrientation(LinearLayout.VERTICAL);
        devices.setPadding(Ui.dp(this, 18), Ui.dp(this, 15), Ui.dp(this, 18), Ui.dp(this, 15));
        col.addView(devices, Ui.lpTop(this, -1, -2, 12));
        body.addView(col, new FrameLayout.LayoutParams(-1, -2));
        foot("◀ ▶", "move", "BACK", "command center");
        internet.requestFocus();
    }

    private LinearLayout card(LinearLayout row, float weight, int leftDp) {
        Plate p = Plate.of(this, t, Plate.Kind.TILE);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(Ui.dp(this, 18), Ui.dp(this, 15), Ui.dp(this, 18), Ui.dp(this, 15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 150), weight);
        lp.leftMargin = Ui.dp(this, leftDp);
        row.addView(p, lp);
        return p;
    }

    @Override
    protected void refresh() {
        Live.Net n = Live.net(this);
        fillInternet(n);
        fillProtection(n);
        if (devices.getChildCount() == 0) fillDevices(null);
        new Thread(() -> {
            List<String[]> found = probeDevices();
            main.post(() -> { checkedAt = System.currentTimeMillis(); fillDevices(found); });
        }, "mills-net").start();
    }

    private void fillInternet(Live.Net n) {
        internet.removeAllViews();
        internet.addView(Ui.eyebrow(this, t, "Internet"));
        internet.addView(Ui.text(this, n.validated ? "Online" : n.online ? "Limited" : "Offline", 28, t.text, 600), Ui.lpTop(this, -2, -2, 7));
        String sub = n.kind() + (n.ssid.isEmpty() ? "" : " · " + n.ssid) + (n.linkMbps > 0 ? " · link " + n.linkMbps + " Mbps" : "");
        internet.addView(Ui.text(this, sub, 10.5f, t.dim, 300), Ui.lpTop(this, -2, -2, 4));
        internet.addView(Ui.chip(this, t, n.validated ? Ui.Status.OK : n.online ? Ui.Status.WARN : Ui.Status.BAD,
                n.validated ? "Android validated internet" : n.online ? "Connected, no internet" : "Disconnected"),
                Ui.lpTop(this, -2, Ui.dp(this, 20), 8));
        internet.addView(Ui.kv(this, t, "This TV", n.ip, 0, 10), Ui.lpTop(this, -1, -2, 7));
    }

    private void fillProtection(Live.Net n) {
        protection.removeAllViews();
        protection.addView(Ui.eyebrow(this, t, "Protection"));
        String setting = Live.privateDnsSetting(this);
        boolean active = !n.dns.isEmpty();
        boolean adguard = setting.contains("adguard");
        protection.addView(Ui.text(this, adguard ? "Ad-block DNS" : "Private DNS", 18, t.text, 500), Ui.lpTop(this, -2, -2, 8));
        protection.addView(Ui.chip(this, t, active ? Ui.Status.OK : Ui.Status.IDLE, active ? "Encrypted DNS active" : "Not active"),
                Ui.lpTop(this, -2, Ui.dp(this, 20), 8));
        protection.addView(Ui.kv(this, t, "Setting", setting, adguard ? t.tealText : 0, 10), Ui.lpTop(this, -1, -2, 8));
        protection.addView(Ui.text(this, "setting and link checked just now · blocked traffic isn't counted here", 8.5f, t.dim, 300),
                Ui.lpTop(this, -1, -2, 5));
    }

    /** Gateway from the default route, Hub, then the agent machines Mission Control lists. */
    private List<String[]> probeDevices() {
        List<String[]> out = new ArrayList<>();   // {name, ip, state}
        String gw = gateway();
        if (gw != null) out.add(new String[]{"Gateway", gw, ""});
        out.add(new String[]{"HUB · home hub", Prefs.hub(this), ""});
        out.add(new String[]{"NAS · NAS", NAS_IP, ""});
        // The Nest answers over Matter through the bridge; that read is the proof it's online.
        Live.House h = Live.fetchHouseBlocking(getApplicationContext());
        String[] nest = {"Nest thermostat", "via Matter", h.fresh() ? "online" : "no answer"};
        // The AI machines live on their own network (VLAN 101), so the TV can't probe them.
        // The hub reads Tailscale's own view of each and serves it in /api/status "machines".
        List<String[]> ai = aiMachines();
        // all at once: the screen waits for the slowest device, not the sum of them
        List<Thread> threads = new ArrayList<>();
        for (final String[] d : out) {
            Thread th = new Thread(() -> d[2] = answers(d[1]) ? "online" : "no answer");
            th.start();
            threads.add(th);
        }
        for (Thread th : threads) { try { th.join(4000); } catch (InterruptedException ignored) {} }
        for (String[] d : out) if (d[2].isEmpty()) d[2] = "no answer";
        out.add(nest);
        out.addAll(ai);
        return out;
    }

    static final String NAS_IP = "192.168.1.x";

    /** The hub's Tailscale view of the AI machines: ok online, warn partial, bad offline, idle never seen. */
    private List<String[]> aiMachines() {
        List<String[]> l = new ArrayList<>();
        try {
            String body = Live.get(Live.missionUrl(getApplicationContext()), 3000);
            org.json.JSONArray a = body == null ? null : new org.json.JSONObject(body).optJSONArray("machines");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject m = a.getJSONObject(i);
                String st = m.optString("state", "");
                String word = Ui.Status.OK.equals(st) ? "online" : Ui.Status.WARN.equals(st) ? "partial"
                        : Ui.Status.BAD.equals(st) ? "offline" : "never seen";
                l.add(new String[]{m.optString("name", "?"), "tailnet", word});
            }
        } catch (Exception ignored) {}
        if (l.isEmpty()) l.add(new String[]{"AI machines", "via the hub", "no answer"});
        return l;
    }

    /** The colour and text colour for a device's state word. */
    private String stateOf(String word) {
        switch (word) {
            case "online":     return Ui.Status.OK;
            case "partial":    return Ui.Status.WARN;
            case "never seen": return Ui.Status.IDLE;
            default:           return Ui.Status.BAD;
        }
    }

    private String gateway() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network an = cm.getActiveNetwork();
            LinkProperties lp = cm.getLinkProperties(an);
            if (lp == null) return null;
            for (RouteInfo r : lp.getRoutes()) if (r.isDefaultRoute() && r.getGateway() != null) {
                InetAddress g = r.getGateway();
                if (g instanceof java.net.Inet4Address) return g.getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Alive if any common port accepts or refuses; a timeout on all of them is "no answer". */
    private static boolean answers(String ip) {
        int[] ports = {80, 443, 22, 445, 8777};   // 445: the NAS answers on SMB
        for (int port : ports) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), 450);
                return true;
            } catch (ConnectException ce) {
                // A refusal is an answer: something is there. "No route to host" is not.
                String m = String.valueOf(ce.getMessage());
                if (m.contains("ECONNREFUSED") || m.contains("refused")) return true;
                if (m.contains("EHOSTUNREACH") || m.contains("No route")) return false;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void fillDevices(List<String[]> list) {
        devices.removeAllViews();
        LinearLayout head = Ui.hbox(this);
        TextView e = Ui.eyebrow(this, t, list == null ? "Key devices · checking…" :
                "Key devices · " + count(list) + " of " + list.size() + " online");
        head.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
        if (list != null) head.addView(Ui.text(this, "checked " + Live.ago(checkedAt), 8.5f, t.dim, 300));
        devices.addView(head);
        if (list == null) return;
        LinearLayout grid = Ui.hbox(this);
        LinearLayout c1 = Ui.vbox(this), c2 = Ui.vbox(this);
        grid.addView(c1, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams c2p = new LinearLayout.LayoutParams(0, -2, 1f); c2p.leftMargin = Ui.dp(this, 32);
        grid.addView(c2, c2p);
        for (int i = 0; i < list.size(); i++) {
            String[] d = list.get(i);
            String st = stateOf(d[2]);
            LinearLayout r = Ui.hbox(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(Ui.dot(this, t.state(st), 5));
            TextView nm = Ui.single(Ui.text(this, d[0], 10.5f, t.text, 400));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f); np.leftMargin = Ui.dp(this, 8);
            r.addView(nm, np);
            r.addView(Ui.text(this, d[1] + "  ", 9, t.dim, 300));
            r.addView(Ui.text(this, d[2], 10, t.stateText(st), 500));
            (i % 2 == 0 ? c1 : c2).addView(r, Ui.lpTop(this, -1, -2, 8));
        }
        devices.addView(grid, Ui.lpTop(this, -1, -2, 2));
    }

    private static int count(List<String[]> l) { int n = 0; for (String[] d : l) if ("online".equals(d[2])) n++; return n; }
}
