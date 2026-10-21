package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Theater's "Continue Watching": the TV's own Watch Next rows (platform TvContract, API 26+, so
 * no extra dependency). Read off the main thread, newest engagement first. The result says
 * honestly why it is empty: refused, filtered to our own rows, or nothing there. Posters load on
 * two background threads into a small LRU cache and fail quietly; nothing here is ever made up.
 */
final class WatchNext {
    private WatchNext() {}

    private static final String TAG = "MillsWatchNext";
    /** Without this (signature|privileged) the provider only returns rows our own package wrote. */
    static final String ALL_EPG = "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA";
    private static final int MAX = 12;

    static final class Item {
        String title, subtitle, pkg, intentUri, poster;
        float progress = -1f;            // 0..1, or -1 when the app gave no position
        PendingIntent resume;            // our own history only: the session's activity, in memory
        Intent intent(Context c) {
            if (intentUri != null) {
                try { return Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME); } catch (Exception ignored) {}
            }
            Intent i = pkg == null ? null : c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            return i != null ? i : pkg == null ? null : c.getPackageManager().getLaunchIntentForPackage(pkg);
        }
    }

    /** What the read found. items is empty unless state == OK. */
    static final class Result {
        static final int OK = 0, REFUSED = 1, FILTERED = 2, EMPTY = 3;
        int state;
        final List<Item> items = new ArrayList<>();
    }

    interface Got { void got(Result r); }

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(Context ctx, Got cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            Result r = read(app);
            MAIN.post(() -> cb.got(r));
        });
    }

    private static Result read(Context c) {
        Result r = new Result();
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        };
        // No selection: the provider rejects some columns in a selection from ordinary apps,
        // so hidden (non-browsable) rows are dropped below instead.
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cur != null) {
                while (cur.moveToNext() && r.items.size() < MAX) {
                    if (!cur.isNull(11) && cur.getInt(11) == 0) continue;
                    Item it = new Item();
                    it.title = cur.getString(0);
                    if (it.title == null || it.title.trim().isEmpty()) it.title = cur.getString(1);
                    if (it.title == null || it.title.trim().isEmpty()) continue;
                    long pos = cur.isNull(7) ? 0 : cur.getLong(7), dur = cur.isNull(8) ? 0 : cur.getLong(8);
                    if (dur > 0 && pos > 0) it.progress = Math.max(0f, Math.min(1f, pos / (float) dur));
                    it.subtitle = subtitle(cur.getString(2), cur.getString(3), pos, dur, cur.isNull(9) ? -1 : cur.getInt(9));
                    it.poster = cur.getString(4) != null ? cur.getString(4) : cur.getString(5);
                    it.intentUri = cur.getString(6);
                    it.pkg = cur.getString(10);
                    r.items.add(it);
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Watch Next refused: " + e.getMessage());
            r.state = Result.REFUSED;
            return r;
        } catch (Exception e) {
            Log.w(TAG, "Watch Next read failed", e);
            r.state = Result.REFUSED;
            return r;
        }
        boolean all = c.checkSelfPermission(ALL_EPG) == PackageManager.PERMISSION_GRANTED;
        r.state = !r.items.isEmpty() ? Result.OK : all ? Result.EMPTY : Result.FILTERED;
        Log.i(TAG, "Watch Next: " + r.items.size() + " rows, all-EPG access " + (all ? "granted" : "not granted"));
        return r;
    }

    /** "S1 E5 · 45 min left", "S2 E1 · next episode", "1 h 10 min left", or null. */
    static String subtitle(String season, String episode, long pos, long dur, int type) {
        StringBuilder sb = new StringBuilder();
        if (season != null && !season.isEmpty()) sb.append('S').append(season);
        if (episode != null && !episode.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append('E').append(episode);
        String rest = null;
        if (type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) rest = "next episode";
        else if (type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW) rest = "new";
        else if (dur > 0 && pos > 0 && dur > pos) {
            long min = Math.max(1, Math.round((dur - pos) / 60_000.0));
            rest = (min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min") + " left";
        }
        if (rest != null) sb.append(sb.length() > 0 ? " · " : "").append(rest);
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** TheaterActivity's debug-only layout check: labelled as such, posters are our own wallpapers. */
    static Result layoutCheck(String pkg) {
        Result r = new Result();
        String[] wp = {"wp_coast", "wp_ridge", "wp_dusk", "wp_day"};
        for (int i = 0; i < wp.length; i++) {
            Item it = new Item();
            it.title = "Layout check " + (i + 1);
            it.subtitle = subtitle("1", String.valueOf(i + 2), (20 + i * 12) * 60_000L, 60 * 60_000L, -1);
            it.progress = (20 + i * 12) / 60f;
            it.poster = "android.resource://" + pkg + "/drawable/" + wp[i];
            it.pkg = pkg;
            r.items.add(it);
        }
        return r;
    }

    // ---- posters ---------------------------------------------------------------------------------

    interface GotPoster { void got(Bitmap b); }

    /** About 12 MB of decoded posters; a 16:9 card at 392 px wide is ~600 KB. */
    private static final LruCache<String, Bitmap> POSTERS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    /** cb runs on the main thread, only on success. Failures stay quiet: the card keeps its plain plate. */
    static void poster(Context ctx, String uri, int widthPx, GotPoster cb) {
        if (uri == null || uri.isEmpty()) return;
        Bitmap hit = POSTERS.get(uri);
        if (hit != null) { cb.got(hit); return; }
        final ContentResolver cr = ctx.getApplicationContext().getContentResolver();
        IO.execute(() -> {
            Bitmap b = decode(cr, uri, widthPx);
            if (b == null) return;
            POSTERS.put(uri, b);
            MAIN.post(() -> cb.got(b));
        });
    }

    static Bitmap decode(ContentResolver cr, String uri, int widthPx) {
        try {
            byte[] bytes = fetch(cr, uri);
            if (bytes == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= widthPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Throwable t) {
            Log.w(TAG, "poster failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static byte[] fetch(ContentResolver cr, String uri) throws Exception {
        String scheme = Uri.parse(uri).getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.US);
        InputStream in;
        HttpURLConnection http = null;
        if (scheme.equals("http") || scheme.equals("https")) {
            http = (HttpURLConnection) new URL(uri).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(8000);
            if (http.getResponseCode() / 100 != 2) { http.disconnect(); return null; }
            in = http.getInputStream();
        } else {
            in = cr.openInputStream(Uri.parse(uri));
            if (in == null) return null;
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 6 * 1024 * 1024) return null;   // not a poster
            }
            return out.toByteArray();
        } finally {
            if (http != null) http.disconnect();
        }
    }
}
