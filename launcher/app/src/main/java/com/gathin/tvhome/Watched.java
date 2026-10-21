package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Our own Continue Watching, for TVs whose Watch Next we may not read. MediaWatcher feeds it what
 * media sessions say while something plays; this keeps the last 20 titles (one per app + title)
 * in a JSON file and small thumbnails, all in the app's private storage. Nothing here leaves the
 * TV. The resume PendingIntent lives in memory only: after a restart OK opens the app instead.
 */
final class Watched {
    private Watched() {}

    private static final String TAG = "MillsWatched";
    private static final String FILE = "continue_watching.json", THUMBS = "cw";
    private static final int KEEP = 20;
    static final long MIN_WATCHED_MS = 2 * 60_000L, MAX_AGE_MS = 30L * 24 * 60 * 60_000L;
    static final float DONE = 0.95f;
    static final int THUMB_W = 384;

    static final class Entry {
        String pkg, title, show, season, episode, thumb;
        long position, duration, lastPlayed;

        String key() { return key(pkg, title); }
        static String key(String pkg, String title) { return pkg + "|" + title.trim().toLowerCase(Locale.ROOT); }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg); o.put("title", title);
            if (show != null) o.put("show", show);
            if (season != null) o.put("season", season);
            if (episode != null) o.put("episode", episode);
            if (thumb != null) o.put("thumb", thumb);
            o.put("pos", position); o.put("dur", duration); o.put("at", lastPlayed);
            return o;
        }

        static Entry of(JSONObject o) {
            Entry e = new Entry();
            e.pkg = o.optString("pkg", null);
            e.title = o.optString("title", null);
            e.show = o.has("show") ? o.optString("show") : null;
            e.season = o.has("season") ? o.optString("season") : null;
            e.episode = o.has("episode") ? o.optString("episode") : null;
            e.thumb = o.has("thumb") ? o.optString("thumb") : null;
            e.position = o.optLong("pos"); e.duration = o.optLong("dur"); e.lastPlayed = o.optLong("at");
            return e.pkg == null || e.title == null ? null : e;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Entry> items;                                   // newest first; null until loaded
    private static final Map<String, PendingIntent> RESUME = new HashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static final Runnable SAVE = () -> IO.execute(Watched::write);

    private static void ensure(Context c) {
        synchronized (LOCK) {
            if (items != null) return;
            app = c.getApplicationContext();
            items = new ArrayList<>();
            try {
                File f = new File(app.getFilesDir(), FILE);
                if (f.exists()) {
                    JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    for (int i = 0; i < a.length(); i++) {
                        Entry e = Entry.of(a.getJSONObject(i));
                        if (e != null) items.add(e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "history unreadable, starting fresh: " + e.getClass().getSimpleName());
            }
        }
    }

    /** "S1 E5", "S01E05", "Season 1 Episode 5" in a title or subtitle. */
    private static final Pattern SE = Pattern.compile("(?i)\\b(?:S|Season\\s*)(\\d{1,2})\\s*[ .:x-]?\\s*(?:E|Ep\\.?\\s*|Episode\\s*)(\\d{1,3})\\b");

    /**
     * One observation from a playing session. Merged into the item with the same app + title,
     * which moves to the front. art, when given, becomes the item's thumbnail (downscaled, off the
     * main thread). Saving is debounced, so a burst of callbacks writes once.
     */
    static void record(Context c, String pkg, String title, String show, long position, long duration,
                       PendingIntent resume, Bitmap art, String artUri) {
        if (pkg == null || title == null || title.trim().isEmpty()) return;
        ensure(c);
        final String key = Entry.key(pkg, title);
        Entry e;
        boolean needArt;
        synchronized (LOCK) {
            e = null;
            for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(key)) { e = items.remove(i); break; }
            if (e == null) { e = new Entry(); e.pkg = pkg; e.title = title.trim(); }
            if (show != null && !show.trim().isEmpty() && !show.trim().equalsIgnoreCase(e.title)) e.show = show.trim();
            Matcher m = SE.matcher(e.title + " " + (e.show == null ? "" : e.show));
            if (m.find()) { e.season = String.valueOf(Integer.parseInt(m.group(1))); e.episode = String.valueOf(Integer.parseInt(m.group(2))); }
            if (duration > 0) e.duration = duration;
            if (position > 0) e.position = e.duration > 0 ? Math.min(position, e.duration) : position;
            e.lastPlayed = System.currentTimeMillis();
            items.add(0, e);
            needArt = e.thumb == null || !new File(thumbDir(), e.thumb).exists();
            if (resume != null) RESUME.put(key, resume);
            trim();
        }
        if (needArt && (art != null || artUri != null)) {
            final Entry target = e;
            IO.execute(() -> saveThumb(target, art, artUri));
        }
        MAIN.removeCallbacks(SAVE);
        MAIN.postDelayed(SAVE, 2_000L);
    }

    /** Keep the newest KEEP (write() then drops thumbnails nobody points at). Caller holds LOCK. */
    private static void trim() {
        while (items.size() > KEEP) items.remove(items.size() - 1);
    }

    private static File thumbDir() {
        File d = new File(app.getFilesDir(), THUMBS);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void saveThumb(Entry e, Bitmap art, String artUri) {
        try {
            Bitmap b = art;
            if (b == null) b = WatchNext.decode(app.getContentResolver(), artUri, THUMB_W);
            if (b == null) return;
            if (b.getWidth() > THUMB_W) {
                int h = Math.max(1, Math.round(b.getHeight() * (THUMB_W / (float) b.getWidth())));
                b = Bitmap.createScaledBitmap(b, THUMB_W, h, true);
            }
            String name = Integer.toHexString(e.key().hashCode()) + ".jpg";
            try (FileOutputStream out = new FileOutputStream(new File(thumbDir(), name))) {
                b.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            synchronized (LOCK) { e.thumb = name; }
            write();
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail skipped: " + t.getClass().getSimpleName());
        }
    }

    private static void write() {
        String json;
        int n;
        Set<String> keep = new HashSet<>();
        synchronized (LOCK) {
            if (items == null) return;
            JSONArray a = new JSONArray();
            try { for (Entry e : items) { a.put(e.json()); if (e.thumb != null) keep.add(e.thumb); } }
            catch (Exception ex) { return; }
            json = a.toString();
            n = items.size();
        }
        try {
            File tmp = new File(app.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new File(app.getFilesDir(), FILE))) Log.w(TAG, "history save failed");
            else Log.i(TAG, "history saved: " + n + " items, " + keep.size() + " thumbnails");   // counts only, never titles
            File[] old = thumbDir().listFiles();
            if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
        } catch (Exception ex) {
            Log.w(TAG, "history save failed: " + ex.getClass().getSimpleName());
        }
    }

    /** How many items are stored (shown and hidden), for Settings. */
    static int count(Context c) {
        ensure(c);
        synchronized (LOCK) { return items.size(); }
    }

    /** Settings > Layout > Clear Continue Watching: the file, the thumbnails, the resume intents. */
    static void clear(Context c) {
        ensure(c);
        synchronized (LOCK) { items.clear(); RESUME.clear(); }
        MAIN.removeCallbacks(SAVE);
        IO.execute(Watched::write);
    }

    /**
     * The cards Theater shows, newest first: started (2 min or more in), not finished (95%), with
     * a duration, and played in the last 30 days.
     */
    static List<WatchNext.Item> shown(Context c) {
        ensure(c);
        List<WatchNext.Item> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            for (Entry e : items) {
                if (e.duration <= 0 || e.position < MIN_WATCHED_MS) continue;
                if (e.position >= e.duration * DONE) continue;
                if (now - e.lastPlayed > MAX_AGE_MS) continue;
                WatchNext.Item it = new WatchNext.Item();
                it.title = e.title;
                it.pkg = e.pkg;
                it.progress = Math.max(0f, Math.min(1f, e.position / (float) e.duration));
                it.subtitle = WatchNext.subtitle(e.season, e.episode, e.position, e.duration, -1);
                if (e.thumb != null) it.poster = "file://" + new File(thumbDir(), e.thumb).getAbsolutePath();
                it.resume = RESUME.get(e.key());
                out.add(it);
            }
        }
        Log.i(TAG, "showing " + out.size() + " of " + items.size() + " saved");   // counts only, never titles
        return out;
    }

    /** OK on a card: the session's own PendingIntent while it is still valid, else the app. */
    static boolean resume(Context c, WatchNext.Item it) {
        if (it.resume == null) return false;
        try {
            it.resume.send();
            return true;
        } catch (PendingIntent.CanceledException e) {
            synchronized (LOCK) { RESUME.values().remove(it.resume); }
            it.resume = null;
            return false;
        }
    }
}
