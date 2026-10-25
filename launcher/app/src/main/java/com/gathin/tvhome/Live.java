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
        /** JJ's own name and note for the tile (Mission Control edit mode); the hub's defaults beside them. */
        String label = "", defaultLabel = "", defaultNote = "";
        /** The latest tests' result from the hub ("Ready", "Waiting") and its detail line; empty when none. */
        String reviewWord = "", detail = "";
        /** What serves it, from its own model key (never the dashboard host): "gpt-oss-120b · CORE
         *  (stand-in DYNAMO)", that machine's check (ok/warn/bad/idle) and words, its machine, cloud. */
        String servedBy = "", servedState = "", servedWhy = "", machineLabel = "", cloud = "";
        String shown() { return label.isEmpty() ? name : label; }
        /** One of up / ready / waiting / partial / down / not built, for the team counts. */
        String kind() {
            String s = state();
            if (!reviewWord.isEmpty() && (Ui.Status.OK.equals(s) || Ui.Status.WARN.equals(s))) return reviewWord.toLowerCase(java.util.Locale.US);
            switch (s) {
                case Ui.Status.OK: return "up";
                case Ui.Status.WARN: return "partial";
                case Ui.Status.BAD: return "down";
                default: return "not built";
            }
        }
        Agent copy() {
            Agent c = new Agent();
            c.name = name; c.status = status; c.note = note; c.host = host; c.checked = checked; c.why = why;
            c.label = label; c.defaultLabel = defaultLabel; c.defaultNote = defaultNote;
            c.reviewWord = reviewWord; c.detail = detail;
            c.servedBy = servedBy; c.servedState = servedState; c.servedWhy = servedWhy; c.machineLabel = machineLabel; c.cloud = cloud;
            return c;
        }
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
            if (!reviewWord.isEmpty() && (Ui.Status.OK.equals(state()) || Ui.Status.WARN.equals(state()))) return reviewWord;
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

    /** One of the Sparks or NAS, as the hub checked it (/api/status "infra"). */
    static final class Machine {
        String name = "", kind = "", role = "", model = "", engine = "", why = "", checked = "";
        /** JJ's own name and note (edit mode; the note shows as the role); the hub's defaults beside them. */
        String label = "", defaultLabel = "", defaultNote = "";
        String shown() { return label.isEmpty() ? name : label; }
        Machine copy() {
            Machine c = new Machine();
            c.name = name; c.kind = kind; c.role = role; c.model = model; c.engine = engine; c.why = why; c.checked = checked;
            c.ip = ip; c.note = note; c.standIn = standIn; c.speed = speed; c.speedAt = speedAt; c.online = online; c.lastSeen = lastSeen;
            c.label = label; c.defaultLabel = defaultLabel; c.defaultNote = defaultNote;
            c.agents.addAll(agents); c.facts.addAll(facts); c.stores.addAll(stores);
            return c;
        }
        /** For the detail screen; empty or null from an older hub. */
        String ip = "", note = "", standIn = "", speed = "", speedAt = "";
        Boolean online;           // the tailnet's view (Sparks); null when the hub didn't say
        long lastSeen;            // the tailnet's last sighting, 0 when it never said
        final List<String> agents = new ArrayList<>();
        final List<String[]> facts = new ArrayList<>();   // {key, value}
        final List<Stored> stores = new ArrayList<>();
        boolean nas() { return "nas".equals(kind); }
        String state() {
            switch (checked) {
                case Ui.Status.OK: case Ui.Status.WARN: case Ui.Status.BAD: return checked;
                default: return Ui.Status.IDLE;
            }
        }
        /** "gpt-oss-120b · vLLM 0.20.1" for a Spark; the box itself for NAS. */
        String spec() {
            if (model.isEmpty()) return engine;
            return engine.isEmpty() ? model : model + " · " + engine;
        }
        /** {free, total} in TB from the hub's "11.2 TB free of 14.6 TB", or null when it didn't say. */
        double[] space() {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\d.]+) TB free of ([\\d.]+) TB").matcher(why);
            if (!m.find()) return null;
            try {
                double free = Double.parseDouble(m.group(1)), total = Double.parseDouble(m.group(2));
                return total > 0 ? new double[]{free, total} : null;
            } catch (NumberFormatException e) { return null; }
        }
    }

    /** One thing kept on NAS, as the hub read it. */
    static final class Stored {
        String name = "";
        boolean present;
        int clips = -1, items = -1;
        long bytes = -1, updated;   // updated: the folder's last change, ms
    }

    static final class Mission {
        boolean ok;               // the last fetch answered
        long at;                  // when the data shown was fetched
        String host = "", uptime = "", disk = "";
        int loadPct = -1, models = -1;
        double memGb = -1;
        final List<Agent> agents = new ArrayList<>();
        final List<Machine> infra = new ArrayList<>();   // empty from an older hub

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
        /** How many agents read this kind ("up", "ready", "waiting", "partial", "down", "not built"). */
        int countKind(String kind) { int n = 0; for (Agent a : agents) if (kind.equals(a.kind())) n++; return n; }
        /** The kinds in the order the TV lists them, each with its state colour. */
        static final String[][] KINDS = {{Ui.Status.OK, "up"}, {Ui.Status.OK, "ready"}, {Ui.Status.WARN, "waiting"},
                {Ui.Status.WARN, "partial"}, {Ui.Status.BAD, "down"}, {Ui.Status.IDLE, "not built"}};
        /** "1 up · 2 ready · 1 waiting · 6 not built" (zeros left out; withIdle false drops the grey ones). */
        String summary(boolean withIdle) {
            StringBuilder sb = new StringBuilder();
            for (String[] p : KINDS) {
                if (!withIdle && p[0].equals(Ui.Status.IDLE)) continue;
                int n = countKind(p[1]);
                if (n == 0) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(n).append(' ').append(p[1]);
            }
            return sb.length() == 0 ? (agents.isEmpty() ? "no agents" : count(Ui.Status.IDLE) + " not built") : sb.toString();
        }
        Agent find(String name) { for (Agent a : agents) if (a.name.equalsIgnoreCase(name)) return a; return null; }
        Machine machine(String name) { for (Machine x : infra) if (x.name.equalsIgnoreCase(name)) return x; return null; }
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
                        ag.label = j.optString("label", "");
                        ag.reviewWord = j.optString("word", "");
                        ag.detail = j.optString("detail", "");
                        ag.servedBy = j.optString("served_by", "");
                        ag.servedState = j.optString("served_state", "");
                        ag.servedWhy = j.optString("served_why", "");
                        ag.machineLabel = j.isNull("machine_label") ? "" : j.optString("machine_label", "");
                        ag.cloud = j.optString("cloud", "");
                        ag.defaultLabel = j.optString("default_label", ag.name);
                        ag.defaultNote = j.optString("default_note", ag.note);
                        m.agents.add(ag);
                    }
                    JSONArray inf = o.optJSONArray("infra");
                    if (inf != null) for (int i = 0; i < inf.length(); i++) {
                        JSONObject j = inf.getJSONObject(i);
                        Machine mc = new Machine();
                        mc.name = j.optString("name", "?");
                        mc.kind = j.optString("kind", "");
                        mc.role = j.optString("role", "");
                        mc.model = j.optString("model", "");
                        mc.engine = j.optString("engine", "");
                        mc.checked = j.optString("state", "");
                        mc.why = j.optString("why", "");
                        mc.ip = j.optString("ip", "");
                        mc.note = j.optString("note", "");
                        mc.standIn = j.optString("stand_in", "");
                        mc.speed = j.optString("speed", "");
                        mc.speedAt = j.optString("speed_at", "");
                        mc.label = j.optString("label", "");
                        mc.defaultLabel = j.optString("default_label", mc.name);
                        mc.defaultNote = j.optString("default_note", mc.role);
                        if (j.has("online")) mc.online = j.optBoolean("online");
                        String seen = j.optString("last_seen", "");
                        if (!seen.isEmpty()) try { mc.lastSeen = java.time.Instant.parse(seen).toEpochMilli(); } catch (Exception ignored) {}
                        JSONArray ag = j.optJSONArray("agents");
                        if (ag != null) for (int k = 0; k < ag.length(); k++) mc.agents.add(ag.optString(k));
                        JSONArray fa = j.optJSONArray("facts");
                        if (fa != null) for (int k = 0; k < fa.length(); k++) {
                            JSONArray f = fa.optJSONArray(k);
                            if (f != null && f.length() >= 2) mc.facts.add(new String[]{f.optString(0), f.optString(1)});
                        }
                        JSONArray st = j.optJSONArray("stores");
                        if (st != null) for (int k = 0; k < st.length(); k++) {
                            JSONObject so = st.getJSONObject(k);
                            Stored sd = new Stored();
                            sd.name = so.optString("name", "?");
                            sd.present = so.optBoolean("present", false);
                            sd.clips = so.optInt("clips", -1);
                            sd.items = so.optInt("items", -1);
                            sd.bytes = so.optLong("bytes", -1);
                            sd.updated = so.optLong("updated", 0) * 1000L;
                            mc.stores.add(sd);
                        }
                        m.infra.add(mc);
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
        // bridge v2: what may be changed, and how far (all empty from a v1 bridge: no controls)
        boolean controls, runningExposed;
        String modeKey;                                   // Off / Heat / Cool / Auto
        final List<String> modes = new ArrayList<>();     // only what the Nest accepts
        int heatMin = 68, heatMax = 82, coolMin = 68, coolMax = 82, gapF = 3;
        /** The last change the bridge confirmed: "cool 75° → 76° · TV", and when. */
        String lastWhat; long lastAt;
        boolean canControl() { return fresh() && controls; }
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

    static String houseBase(Context ctx) { return "http://" + Prefs.hub(ctx) + ":8778/api/house"; }

    static House fetchHouseBlocking(Context ctx) {
        House h = new House();
        try {
            String body = get(houseBase(ctx), 2500);
            if (body != null) {
                house = h = parseHouse(new JSONObject(body));
                return h;
            }
        } catch (Exception ignored) {}
        house = h;
        return h;
    }

    /** One reading from the bridge's JSON (GET /api/house, or the "house" in a change's answer). */
    static House parseHouse(JSONObject o) {
        House h = new House();
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
        h.controls = o.optBoolean("controls", false);
        h.runningExposed = o.optBoolean("runningExposed", false);
        // a v2 bridge says when the Nest doesn't share its running state: then "idle" is only a
        // placeholder, so the cards show nothing rather than a guess
        if (h.controls && !h.runningExposed) h.running = null;
        h.modeKey = o.isNull("modeKey") ? null : o.optString("modeKey", null);
        JSONArray ms = o.optJSONArray("modes");
        if (ms != null) for (int i = 0; i < ms.length(); i++) h.modes.add(ms.optString(i));
        JSONObject lim = o.optJSONObject("limits");
        if (lim != null) {
            JSONObject hl = lim.optJSONObject("heat"), cl = lim.optJSONObject("cool");
            if (hl != null) { h.heatMin = hl.optInt("minF", 68); h.heatMax = hl.optInt("maxF", 82); }
            if (cl != null) { h.coolMin = cl.optInt("minF", 68); h.coolMax = cl.optInt("maxF", 82); }
            h.gapF = lim.optInt("gapF", 3);
        }
        JSONObject lc = o.optJSONObject("lastChange");
        if (lc != null) {
            String what = lc.optString("what");
            boolean deg = !"mode".equals(what);
            h.lastWhat = what + " " + lc.opt("old") + (deg ? "°" : "") + " → " + lc.opt("new") + (deg ? "°" : "")
                    + " · " + lc.optString("who");
            try { h.lastAt = java.time.OffsetDateTime.parse(lc.optString("at")).toInstant().toEpochMilli(); }
            catch (Exception e) { h.lastAt = 0; }
        }
        return h;
    }

    /** What the bridge said to a change: ok with the read-back value, or the reason it didn't happen. */
    static final class HouseChange {
        boolean ok;
        int code;                 // HTTP status (0: the bridge didn't answer)
        Integer confirmedF;       // the setpoint read back from the Nest
        String confirmedMode, error;
        int retryAfterSec;        // 429: when the bridge will take the next change
        House house;              // the reading after the change, when the bridge sent one
    }

    /** POST /api/house/{cool|heat|mode} and wait for the Nest's read-back. Background thread only. */
    static HouseChange changeHouseBlocking(Context ctx, String what, JSONObject body) {
        HouseChange r = new HouseChange();
        try {
            String[] got = post(houseBase(ctx) + "/" + what, body.toString(), 3000, 15000);
            r.code = Integer.parseInt(got[0]);
            JSONObject o = new JSONObject(got[1]);
            r.ok = o.optBoolean("ok", false);
            r.error = o.optString("error", r.ok ? null : "HTTP " + r.code);
            r.retryAfterSec = o.optInt("retryAfterSec", 0);
            JSONObject c = o.optJSONObject("confirmed");
            if (c != null) {
                if (c.has("coolF") && !c.isNull("coolF")) r.confirmedF = c.optInt("coolF");
                if (c.has("heatF") && !c.isNull("heatF")) r.confirmedF = c.optInt("heatF");
                if (c.has("mode")) r.confirmedMode = c.optString("mode");
            }
            JSONObject h = o.optJSONObject("house");
            if (h != null) { r.house = parseHouse(h); r.house.bridge = true; house = r.house; }
        } catch (Exception e) {
            r.ok = false;
            r.error = "bridge not answering";
        }
        return r;
    }

    static String layoutUrl(Context ctx) { return "http://" + Prefs.hub(ctx) + ":8777/api/layout"; }
    static String budgetUrl(Context ctx) { return "http://" + Prefs.hub(ctx) + ":8777/api/budget"; }

    /** Save JJ's Mission Control layout on Hub (blocking). Null when saved, else why not. */
    static String saveLayoutBlocking(Context ctx, JSONObject layout) {
        try {
            String[] r = post(layoutUrl(ctx), layout.toString(), 2500, 4000);
            if ("200".equals(r[0])) return null;
            return new JSONObject(r[1]).optString("why", "Hub said " + r[0]);
        } catch (Exception e) { return "Hub not answering"; }
    }

    // ---- the monthly housing budget (Hub's /api/budget, from JJ's bills; private, LAN only) -----

    static final class Budget {
        boolean ok;
        long at, updated;         // when fetched; when the bills file last changed
        String why = "", source = "";
        double total;
        final List<String[]> items = new ArrayList<>();   // {label, basis}
        final List<Double> amounts = new ArrayList<>();
    }

    private static Budget budget = new Budget();
    static Budget budget() { return budget; }

    static Budget fetchBudgetBlocking(Context ctx) {
        Budget b = new Budget();
        try {
            String body = get(budgetUrl(ctx), 2500);
            if (body != null) {
                JSONObject o = new JSONObject(body);
                b.ok = o.optBoolean("ok", false);
                b.why = o.optString("why", "");
                b.source = o.optString("source", "");
                b.total = o.optDouble("total", 0);
                b.updated = o.optLong("updated", 0) * 1000L;
                b.at = System.currentTimeMillis();
                JSONArray a = o.optJSONArray("items");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject j = a.getJSONObject(i);
                    b.items.add(new String[]{j.optString("label", "?"), j.optString("basis", "")});
                    b.amounts.add(j.optDouble("monthly", 0));
                }
            }
        } catch (Exception ignored) {}
        if (b.at == 0 && budget.at > 0) { budget.ok = false; return budget; }   // keep the last reading, by its age
        budget = b;
        return b;
    }

    static String missionUrl(Context ctx) { return "http://" + Prefs.hub(ctx) + ":8777/api/status"; }
    static String blinkBase(Context ctx) { return blinkHost(ctx) + "/api/blink"; }
    /** The bridge's origin: clip and thumbnail paths it returns ("/api/blink/clip/…") hang off this. */
    static String blinkHost(Context ctx) { return "http://" + Prefs.hub(ctx) + ":8779"; }

    // ---- cameras: Blink, read by the Blink bridge on the hub (~/Scripts/tv/blink-bridge) -------------

    static final class Cam {
        String name, type, battery, wifi;
        boolean doorbell, online, hasThumb;
        Boolean armed, motion;
        Integer tempF;
        String lastRecord, networkId;
        /** Changes only when Blink has a new snapshot (null from an older bridge: always re-fetch). */
        String thumbKey;
        /** Clips today (NAS archive + Blink's cloud, local day), and the last motion or ring in ms (0 = none known). */
        int clipsToday;
        long lastMotionAt;
        /** Blink names can carry a trailing space ("Porch "): show the trimmed one, keep the raw one for the bridge. */
        String label() { return name == null ? "" : name.trim(); }
        /** dBm as a word: Blink reports the Outdoor 4's Wi-Fi in dBm; the doorbell reports none. */
        String wifiWord() {
            if (wifi == null || wifi.isEmpty()) return null;
            try {
                int d = Integer.parseInt(wifi.trim());
                if (d >= 0) return wifi;   // not dBm (bars, or a level): say it as it came
                return (d >= -55 ? "strong" : d >= -67 ? "good" : d >= -75 ? "fair" : "weak") + " · " + d + " dBm";
            } catch (NumberFormatException e) { return wifi; }
        }
        boolean wifiWeak() { try { return Integer.parseInt(wifi.trim()) < -75; } catch (Exception e) { return false; } }
        boolean batteryLow() { return "low".equalsIgnoreCase(battery); }
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
        /** Clips in the archive's index right now, and where the archive lives ("NAS"). */
        int archiveClips; String archivePlace;
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
                    c.archiveClips = ar.optInt("clips", c.archiveTotal);
                    c.archivePlace = ar.isNull("place") ? null : ar.optString("place", null);
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
                    m.thumbKey = j.isNull("thumbKey") ? null : j.optString("thumbKey", null);
                    m.armed = j.isNull("armed") ? null : j.optBoolean("armed");
                    m.motion = j.isNull("motionEnabled") ? null : j.optBoolean("motionEnabled");
                    m.battery = j.isNull("battery") ? null : j.optString("battery");
                    m.tempF = j.isNull("temperatureF") ? null : j.optInt("temperatureF");
                    m.wifi = j.isNull("wifi") ? null : j.optString("wifi");
                    m.lastRecord = j.isNull("lastRecord") ? null : j.optString("lastRecord");
                    m.networkId = j.isNull("networkId") ? null : j.optString("networkId");
                    m.clipsToday = j.optInt("clipsToday");
                    m.lastMotionAt = j.isNull("lastMotionAt") ? 0 : j.optLong("lastMotionAt");
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

    /** Each camera's latest snapshot, decoded once per new picture: {thumbKey, Bitmap}. */
    private static final java.util.Map<String, Object[]> SNAPS = new java.util.HashMap<>();

    /**
     * A camera's latest snapshot, downloaded only when the bridge says Blink has a new one (its
     * thumbKey changed); otherwise the picture already decoded. Background thread only.
     */
    static android.graphics.Bitmap camSnapshot(Context ctx, Cam cam) {
        Object[] had;
        synchronized (SNAPS) { had = SNAPS.get(cam.name); }
        if (had != null && cam.thumbKey != null && cam.thumbKey.equals(had[0])) return (android.graphics.Bitmap) had[1];
        android.graphics.Bitmap b = camImage(ctx, "/thumb/" + android.net.Uri.encode(cam.name), 2);
        if (b == null) return had == null ? null : (android.graphics.Bitmap) had[1];
        synchronized (SNAPS) { SNAPS.put(cam.name, new Object[]{cam.thumbKey, b}); }
        return b;
    }

    /** The snapshot already in memory for a camera, or null. */
    static android.graphics.Bitmap camSnapshotCached(String name) {
        synchronized (SNAPS) { Object[] had = SNAPS.get(name); return had == null ? null : (android.graphics.Bitmap) had[1]; }
    }

    // ---- clip history: the NAS archive (and recent cloud clips), through the bridge --------------

    static final class Clip {
        String id, camera, source, kind, thumb, url;
        long at;
        Double duration;
        boolean archived;
        /** "motion", "doorbell press", "live view", "snapshot". */
        String what() {
            switch (kind == null ? "" : kind) {
                case "doorbell": return "doorbell press";
                case "liveview": return "live view";
                case "snapshot": return "snapshot";
                default:         return "motion";
            }
        }
        String length() { return duration == null ? null : Math.round(duration) + " s"; }
    }

    static final class ClipPage {
        boolean ok;
        int total, next = -1;
        int all, motion, doorbell;
        final List<Clip> clips = new ArrayList<>();
    }

    /** One page of a camera's clips, newest first. kind: all, motion or doorbell. Background thread only. */
    static ClipPage fetchClipsBlocking(Context ctx, String camera, int hours, String kind, int offset, int limit) {
        ClipPage p = new ClipPage();
        try {
            String body = get(blinkBase(ctx) + "/clips?camera=" + android.net.Uri.encode(camera) + "&hours=" + hours
                    + "&kind=" + kind + "&offset=" + offset + "&limit=" + limit, 8000);
            if (body == null) return p;
            JSONObject o = new JSONObject(body);
            p.ok = o.optBoolean("ok");
            p.total = o.optInt("total");
            p.next = o.isNull("next") ? -1 : o.optInt("next", -1);
            JSONObject n = o.optJSONObject("counts");
            if (n != null) { p.all = n.optInt("all"); p.motion = n.optInt("motion"); p.doorbell = n.optInt("doorbell"); }
            JSONArray a = o.optJSONArray("clips");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Clip c = new Clip();
                c.id = j.optString("id");
                c.camera = j.optString("camera");
                c.source = j.optString("source");
                c.kind = j.optString("kind");
                c.at = j.optLong("at");
                c.duration = j.isNull("duration") ? null : j.optDouble("duration");
                c.archived = j.optBoolean("archived");
                c.thumb = j.optString("thumb");
                c.url = j.optString("url");
                p.clips.add(c);
            }
        } catch (Exception ignored) { p.ok = false; }
        return p;
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

    /** Open-Meteo updates its current reading every 15 minutes: asking more often only costs the TV. */
    private static final long WEATHER_EVERY_MS = 10 * 60_000L;

    static Weather fetchWeatherBlocking(Context ctx) {
        if (weather.has() && System.currentTimeMillis() - weather.at < WEATHER_EVERY_MS) return weather;
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

    /** POST JSON; returns {status, body} for any status (the bridge explains its refusals in JSON). */
    static String[] post(String urlStr, String json, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (java.io.OutputStream os = c.getOutputStream()) { os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
        }
        c.disconnect();
        return new String[]{String.valueOf(code), sb.length() == 0 ? "{}" : sb.toString()};
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
