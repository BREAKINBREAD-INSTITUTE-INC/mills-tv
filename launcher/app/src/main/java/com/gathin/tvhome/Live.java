package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every reading the TV shows, from one place, so the command-center cards, the roster and the
 * full views can never disagree (one source of truth). Honest by construction: each reading
 * carries when it was taken, network data goes stale after five minutes, and anything that
 * can't be read says so instead of being made up.
 */
final class Live {
    private Live() {}

    static final long STALE_MS = 5 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- Mission Control (Hub's /api/status) ----------------------------------------------------

    static final class Agent {
        String name, status, note, host;
        /** Checked by the hub (ok/warn/bad/idle) and its plain words; empty from an older hub. */
        String checked = "", why = "";
        /** ok/warn/bad/idle: the hub's own check, or a best guess from an older hub's status word. */
        String state() {
            switch (checked) {
                case Ui.Status.OK: case Ui.Status.WARN: case Ui.Status.BAD: case Ui.Status.IDLE: return checked;
            }
            switch (status == null ? "" : status) {
                case "live":    return Ui.Status.OK;
                case "partial": case "blocked": return Ui.Status.WARN;
                case "down":    return Ui.Status.BAD;
                default:        return Ui.Status.IDLE;
            }
        }
        /** One short word for state(), for a dot where the full reason won't fit. */
        String word() {
            switch (state()) {
                case Ui.Status.OK:   return "Up";
                case Ui.Status.WARN: return "Partial";
                case Ui.Status.BAD:  return "Down";
                default:
                    boolean unbuilt = "planned".equals(status) || "stubbed".equals(status) || why.toLowerCase(java.util.Locale.US).contains("built");
                    return unbuilt ? "Not built" : "Never connected";
            }
        }
        /** The hub's reason ("In shadow · no real jobs yet"), or a word that matches state(). */
        String label() {
            if (!why.isEmpty()) return why;
            switch (status == null ? "" : status) {
                case "live":    return "Up";
                case "partial": return "Partial";
                case "profile": return "Profile";
                case "blocked": return "Blocked";
                case "down":    return "Down";
                case "planned": case "stubbed": return "Not built yet";
                default:        return "Unknown";
            }
        }
    }

    static final class Mission {
        boolean ok;               // the last fetch answered
        long at;                  // when the data shown was fetched
        String host = "", uptime = "", disk = "";
        int loadPct = -1, models = -1;
        double memGb = -1;
        final List<Agent> agents = new ArrayList<>();

        boolean stale() { return at == 0 || System.currentTimeMillis() - at > STALE_MS; }
        /** How many agents are in this state (Ui.Status.OK / WARN / BAD / IDLE). */
        int count(String state) { int n = 0; for (Agent a : agents) if (state.equals(a.state())) n++; return n; }
        /** The team in one state for a single dot: red if any is down, orange if any isn't 100%, green if any is up. */
        String teamState() {
            if (!ok || stale() || agents.isEmpty()) return Ui.Status.IDLE;
            if (count(Ui.Status.BAD) > 0) return Ui.Status.BAD;
            if (count(Ui.Status.WARN) > 0) return Ui.Status.WARN;
            return count(Ui.Status.OK) > 0 ? Ui.Status.OK : Ui.Status.IDLE;
        }
        /** "1 up · 3 partial · 6 not built" (zeros left out; withIdle false drops the grey ones). */
        String summary(boolean withIdle) {
            StringBuilder sb = new StringBuilder();
            String[][] parts = {{Ui.Status.OK, "up"}, {Ui.Status.WARN, "partial"}, {Ui.Status.BAD, "down"}, {Ui.Status.IDLE, "not built"}};
            for (String[] p : parts) {
                if (!withIdle && p[0].equals(Ui.Status.IDLE)) continue;
                int n = count(p[0]);
                if (n == 0) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(n).append(' ').append(p[1]);
            }
            return sb.length() == 0 ? (agents.isEmpty() ? "no agents" : count(Ui.Status.IDLE) + " not built") : sb.toString();
        }
        Agent find(String name) { for (Agent a : agents) if (a.name.equalsIgnoreCase(name)) return a; return null; }
    }

    private static Mission mission = new Mission();

    static Mission mission() { return mission; }

    interface Done<T> { void done(T value); }

    static void fetchMission(Context ctx, Done<Mission> cb) {
        final String url = missionUrl(ctx);
        new Thread(() -> {
            Mission m = new Mission();
            try {
                String body = get(url, 2500);
                if (body != null) {
                    JSONObject o = new JSONObject(body);
                    m.ok = true;
                    m.at = System.currentTimeMillis();
                    m.host = o.optString("host", "");
                    m.uptime = o.optString("uptime", "");
                    m.disk = o.optString("disk_pct", "");
                    m.loadPct = o.optInt("load_pct", -1);
                    m.models = o.optInt("model_count", -1);
                    m.memGb = o.optDouble("mem_gb", -1);
                    JSONArray a = o.optJSONArray("agents");
                    if (a != null) for (int i = 0; i < a.length(); i++) {
                        JSONObject j = a.getJSONObject(i);
                        Agent ag = new Agent();
                        ag.name = j.optString("name", "?");
                        ag.status = j.optString("status", "");
                        ag.note = j.optString("note", "");
                        ag.host = j.optString("host", "");
                        ag.checked = j.optString("state", "");
                        ag.why = j.optString("why", "");
                        m.agents.add(ag);
                    }
                }
            } catch (Exception ignored) {}
            if (!m.ok) {                         // keep the last good data, marked by its age
                Mission old = mission;
                old.ok = false;
                m = old;
            }
            mission = m;
            final Mission out = m;
            MAIN.post(() -> cb.done(out));
        }, "mills-mission").start();
    }

    /** Agent controls (approve / pause / restart) aren't served by the API yet: it answers 501. */
    static void probeControls(Context ctx, Done<Boolean> cb) {
        final String base = "http://" + Prefs.hub(ctx) + ":8777";
        new Thread(() -> {
            boolean ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/agents/FRIDAY/logs").openConnection();
                c.setConnectTimeout(1500); c.setReadTimeout(1500);
                int code = c.getResponseCode();
                String type = c.getContentType();
                ok = code == 200 && type != null && type.contains("json");
                c.disconnect();
            } catch (Exception ignored) {}
            final boolean r = ok;
            MAIN.post(() -> cb.done(r));
        }, "mills-probe").start();
    }

    // ---- house: the Nest, read over Matter by the bridge on Hub (~/Scripts/tv/nest-bridge) ------

    static final class House {
        boolean bridge;           // the bridge answered
        boolean paired, ok;       // it has the Nest, and its last read worked
        long at;                  // when the bridge read the thermostat
        Integer indoorF, heatF, coolF, humidity;
        String mode, running, name, error;
        boolean fresh() { return bridge && ok && at > 0 && System.currentTimeMillis() - at < STALE_MS; }
        String state() { return fresh() ? ("heating".equals(running) ? Ui.Status.WARN : Ui.Status.OK) : Ui.Status.IDLE; }
        String chip() {
            if (fresh()) return "Nest via Matter · " + ago(at);
            if (!bridge) return "Nest bridge not answering";
            if (!paired) return "Nest not linked";
            return at > 0 ? "Nest stale · " + ago(at) : "Nest not answering";
        }
        String targets() {
            if (heatF != null && coolF != null && "Heat · Cool".equals(mode)) return "Heat " + heatF + "° · Cool " + coolF + "°";
            if ("Heat".equals(mode) && heatF != null) return "Heat to " + heatF + "°";
            if ("Cool".equals(mode) && coolF != null) return "Cool to " + coolF + "°";
            return mode == null ? "—" : mode;
        }
    }

    private static House house = new House();
    static House house() { return house; }

    static House fetchHouseBlocking(Context ctx) {
        House h = new House();
        try {
            String body = get("http://" + Prefs.hub(ctx) + ":8778/api/house", 2500);
            if (body != null) {
                JSONObject o = new JSONObject(body);
                h.bridge = true;
                h.paired = o.optBoolean("paired", false);
                h.ok = o.optBoolean("ok", false);
                h.at = o.optLong("at", 0);
                h.indoorF = o.isNull("indoorF") ? null : o.optInt("indoorF");
                h.heatF = o.isNull("heatF") ? null : o.optInt("heatF");
                h.coolF = o.isNull("coolF") ? null : o.optInt("coolF");
                h.humidity = o.isNull("humidity") ? null : o.optInt("humidity");
                h.mode = o.isNull("mode") ? null : o.optString("mode");
                h.running = o.isNull("running") ? null : o.optString("running");
                h.name = o.isNull("name") ? null : o.optString("name");
                h.error = o.isNull("error") ? null : o.optString("error");
            }
        } catch (Exception ignored) {}
        house = h;
        return h;
    }

    static String missionUrl(Context ctx) { return "http://" + Prefs.hub(ctx) + ":8777/api/status"; }
    static String blinkBase(Context ctx) { return "http://" + Prefs.hub(ctx) + ":8779/api/blink"; }

    // ---- cameras: Blink, read by the Blink bridge on the hub (~/Scripts/tv/blink-bridge) -------------

    static final class Cam {
        String name, type, battery, wifi;
        boolean doorbell, online, hasThumb;
        Boolean armed, motion;
        Integer tempF;
        String lastRecord;
    }

    static final class CamEvent {
        String id, camera, source, summary;
        long at;
        boolean ring;
        String what() {
            if (ring) return "rang the doorbell";
            if (source == null || source.isEmpty() || source.equals("pir") || source.contains("motion")) return "motion";   // pir, cv_motion
            if (source.contains("liveview")) return "live view";
            if (source.contains("snapshot")) return "snapshot";
            return source.replace('_', ' ');
        }
    }

    static final class Cams {
        boolean bridge, ok, signedIn;
        long at;
        String error;
        /** NAS archive: null when the bridge doesn't archive. */
        Boolean archiveOk; int archiveTotal, archivePending; long archiveAt; String archiveError;
        final List<Cam> cams = new ArrayList<>();
        final List<CamEvent> events = new ArrayList<>();
        boolean fresh() { return bridge && ok && at > 0 && System.currentTimeMillis() - at < STALE_MS; }
        Cam doorbell() { for (Cam c : cams) if (c.doorbell) return c; return null; }
        CamEvent lastRing() { for (CamEvent e : events) if (e.ring) return e; return null; }
        CamEvent lastFor(String cam) { for (CamEvent e : events) if (cam.equals(e.camera)) return e; return null; }
        /** A ring in the last few minutes: worth a banner on Home. */
        CamEvent recentRing(long withinMs) {
            CamEvent r = lastRing();
            return r != null && System.currentTimeMillis() - r.at < withinMs ? r : null;
        }
        /** "412 clips saved to the NAS · checked 3 min ago", or why it isn't saving. */
        String archiveLine() {
            if (!bridge) return null;
            if (archiveOk == null && archiveAt == 0 && archiveError == null) return "NAS archive off";
            if (archiveError != null && !Boolean.TRUE.equals(archiveOk))
                return "NAS archive · " + shortError(archiveError) + (archiveAt > 0 ? " · " + ago(archiveAt) : "");
            return archiveTotal + " clips saved to the NAS" + (archivePending > 0 ? " · " + archivePending + " waiting" : "")
                    + (archiveAt > 0 ? " · checked " + ago(archiveAt) : "");
        }
        String chip() {
            if (fresh()) return "Blink · " + ago(at);
            if (!bridge) return "Blink bridge not answering";
            if (!signedIn) return "Blink not signed in";
            return at > 0 ? "Blink stale · " + ago(at) : "Blink not answering";
        }
    }

    /**
     * A bridge error made fit for one line on a TV: "[Errno 16] Resource busy: '/Volumes/…'" says
     * "Resource busy", not the file path it failed on.
     */
    static String shortError(String e) {
        if (e == null) return "";
        String s = e;
        int errno = s.indexOf("] ");
        if (s.contains("[Errno") && errno > 0) s = s.substring(errno + 2);
        int colon = s.indexOf(": ");
        if (colon > 0 && (s.indexOf('/') > colon || s.indexOf('\'') > colon)) s = s.substring(0, colon);
        s = s.replaceAll("'[^']*'?", "").replaceAll("\\s+", " ").trim();
        if (s.length() > 48) s = s.substring(0, 47).trim() + "…";
        return s.isEmpty() ? "error" : s;
    }

    private static Cams cams = new Cams();
    static Cams cams() { return cams; }

    static Cams fetchCamsBlocking(Context ctx) {
        Cams c = new Cams();
        try {
            String body = get(blinkBase(ctx), 3000);
            if (body != null) {
                JSONObject o = new JSONObject(body);
                c.bridge = true;
                c.ok = o.optBoolean("ok", false);
                c.signedIn = o.optBoolean("signedIn", false);
                c.at = o.optLong("at", 0);
                c.error = o.isNull("error") ? null : o.optString("error");
                JSONObject ar = o.optJSONObject("archive");
                if (ar != null && ar.optBoolean("enabled")) {
                    c.archiveOk = ar.isNull("ok") ? null : ar.optBoolean("ok");
                    c.archiveTotal = ar.optInt("total");
                    c.archivePending = ar.optInt("pending");
                    c.archiveAt = ar.optLong("at");
                    c.archiveError = ar.isNull("error") ? null : ar.optString("error");
                }
                JSONArray a = o.optJSONArray("cameras");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject j = a.getJSONObject(i);
                    Cam m = new Cam();
                    m.name = j.optString("name");
                    m.type = j.optString("type");
                    m.doorbell = j.optBoolean("doorbell");
                    m.online = j.optBoolean("online", true);
                    m.hasThumb = j.optBoolean("hasThumb");
                    m.armed = j.isNull("armed") ? null : j.optBoolean("armed");
                    m.motion = j.isNull("motionEnabled") ? null : j.optBoolean("motionEnabled");
                    m.battery = j.isNull("battery") ? null : j.optString("battery");
                    m.tempF = j.isNull("temperatureF") ? null : j.optInt("temperatureF");
                    m.wifi = j.isNull("wifi") ? null : j.optString("wifi");
                    m.lastRecord = j.isNull("lastRecord") ? null : j.optString("lastRecord");
                    c.cams.add(m);
                }
                JSONArray ev = o.optJSONArray("events");
                if (ev != null) for (int i = 0; i < ev.length(); i++) {
                    JSONObject j = ev.getJSONObject(i);
                    CamEvent e = new CamEvent();
                    e.id = j.optString("id");
                    e.camera = j.optString("camera");
                    e.source = j.optString("source");
                    e.ring = j.optBoolean("ring");
                    e.summary = j.isNull("summary") ? null : j.optString("summary");
                    try { e.at = java.time.OffsetDateTime.parse(j.optString("at")).toInstant().toEpochMilli(); }
                    catch (Exception ignored) { e.at = 0; }
                    c.events.add(e);
                }
            }
        } catch (Exception ignored) {}
        cams = c;
        return c;
    }

    /** A snapshot JPEG from the bridge, decoded at a TV-sized sample. Background thread only. */
    static android.graphics.Bitmap camImage(Context ctx, String path, int sample) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(blinkBase(ctx) + path).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(c.getInputStream(), null, o);
            c.disconnect();
            return b;
        } catch (Exception e) { return null; }
    }

    /** Ask a camera for a fresh snapshot. Returns the bridge's note (rate-limited to 1/min/camera). */
    static String requestSnap(Context ctx, String camera) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(blinkBase(ctx) + "/snap/" + android.net.Uri.encode(camera)).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) { String l; while ((l = r.readLine()) != null) sb.append(l); }
            c.disconnect();
            JSONObject o = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
            return o.optBoolean("ok") ? o.optString("note", "Asked the camera.") : o.optString("error", "Couldn't ask the camera.");
        } catch (Exception e) { return "Blink bridge not answering."; }
    }

    // ---- weather (Open-Meteo, no key) ------------------------------------------------------------

    static final class Weather {
        int tempF = Integer.MIN_VALUE, code = -1, riseMin = -1, setMin = -1;
        long at;
        boolean has() { return tempF != Integer.MIN_VALUE; }
        String sky() { return describe(code); }
    }

    private static Weather weather = new Weather();
    static Weather weather() { return weather; }

    static Weather fetchWeatherBlocking(Context ctx) {
        try {
            String lat = ctx.getString(R.string.home_lat), lon = ctx.getString(R.string.home_lon);
            String u = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code&daily=sunrise,sunset&timezone=auto"
                    + "&forecast_days=1&temperature_unit=fahrenheit";
            String body = get(u, 4000);
            if (body == null) return weather;
            JSONObject o = new JSONObject(body);
            JSONObject cur = o.getJSONObject("current");
            Weather w = new Weather();
            w.tempF = (int) Math.round(cur.getDouble("temperature_2m"));
            w.code = cur.optInt("weather_code", -1);
            JSONObject daily = o.optJSONObject("daily");
            if (daily != null) {
                w.riseMin = minutes(daily.getJSONArray("sunrise").getString(0));
                w.setMin = minutes(daily.getJSONArray("sunset").getString(0));
                if (w.riseMin >= 0 && w.setMin > w.riseMin) ThemeManager.saveSun(ctx, w.riseMin, w.setMin);
            }
            w.at = System.currentTimeMillis();
            weather = w;
        } catch (Exception ignored) {}
        return weather;
    }

    private static int minutes(String iso) {   // "2026-10-20T07:21"
        try { int t = iso.indexOf('T'); return Integer.parseInt(iso.substring(t + 1, t + 3)) * 60 + Integer.parseInt(iso.substring(t + 4, t + 6)); }
        catch (Exception e) { return -1; }
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1 || code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Storm";
        return "";
    }

    // ---- this TV ---------------------------------------------------------------------------------

    static final class Storage { long total, free; int pct() { return total > 0 ? (int) Math.round((total - free) * 100.0 / total) : 0; } }

    static Storage storage() {
        Storage s = new Storage();
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            s.total = sf.getTotalBytes(); s.free = sf.getAvailableBytes();
        } catch (Exception ignored) {}
        return s;
    }

    static long[] ram(Context c) {   // {used, total, available}
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return new long[]{mi.totalMem - mi.availMem, mi.totalMem, mi.availMem};
        } catch (Exception e) { return new long[]{0, 0, 0}; }
    }

    static String uptime() {
        long up = SystemClock.elapsedRealtime();
        long days = up / 86_400_000L, hrs = (up % 86_400_000L) / 3_600_000L, mins = (up % 3_600_000L) / 60_000L;
        if (days > 0) return days + "d " + hrs + "h";
        if (hrs > 0) return hrs + "h " + mins + "m";
        return mins + "m";
    }

    static final class Net {
        boolean online, validated, wifi, ethernet;
        String ip = "—", ssid = "", dns = "";
        int linkMbps = -1;
        String kind() { return ethernet ? "Ethernet" : wifi ? "Wi-Fi" : online ? "Connected" : "No network"; }
    }

    static Net net(Context c) {
        Net n = new Net();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network an = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = an != null ? cm.getNetworkCapabilities(an) : null;
            if (caps != null) {
                n.online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                n.validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                n.wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                n.ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            }
            LinkProperties lp = an != null ? cm.getLinkProperties(an) : null;
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) { n.ip = a.getHostAddress(); break; }
                }
                if (lp.isPrivateDnsActive()) n.dns = lp.getPrivateDnsServerName() != null ? lp.getPrivateDnsServerName() : "private DNS";
            }
            if (n.wifi) {
                WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                if (wi != null) {
                    n.linkMbps = wi.getLinkSpeed();
                    String s = wi.getSSID();
                    if (s != null && !s.contains("unknown")) n.ssid = s.replace("\"", "");
                }
            }
        } catch (Exception ignored) {}
        return n;
    }

    static String privateDnsSetting(Context c) {
        try {
            String mode = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
            String host = Settings.Global.getString(c.getContentResolver(), "private_dns_specifier");
            if ("hostname".equals(mode) && host != null && !host.isEmpty()) return host;
            if ("off".equals(mode)) return "off";
            return "automatic";
        } catch (Exception e) { return "—"; }
    }

    /** True when the TV's own update app is turned off (a real, checkable fact). */
    static boolean updatesFrozen(Context c) {
        try {
            int s = c.getPackageManager().getApplicationEnabledSetting("com.sony.dtv.systemupdate");
            return s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Exception e) { return false; }
    }

    /** Is a host on the LAN answering? Says "online", never "live" (design rules §8). */
    static boolean reachable(String host, int timeoutMs) {
        try { return InetAddress.getByName(host).isReachable(timeoutMs); } catch (Exception e) { return false; }
    }

    // ---- formatting ------------------------------------------------------------------------------

    static String gb(long bytes) {
        double g = bytes / 1_000_000_000.0;
        return String.format(Locale.US, g >= 10 ? "%.0f GB" : "%.1f GB", g);
    }

    /** "12s ago", "4 min ago", "2 h ago". */
    static String ago(long at) {
        if (at <= 0) return "never";
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + " min ago";
        return (s / 3600) + " h ago";
    }

    static String get(String urlStr, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); return null; }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
        }
        c.disconnect();
        return sb.toString();
    }
}
