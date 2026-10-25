package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clip thumbnails from the Blink bridge: fetched on three background threads, decoded small, kept
 * in a 6 MB memory cache. An ImageView remembers which clip it wants (its tag), so a recycled row
 * never shows the previous clip's picture. A thumbnail the bridge couldn't make yet (a clip brand
 * new in Blink's cloud) is asked for again a little later, while its row is still showing.
 */
final class ClipThumbs {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** After a miss, ask again after these waits (the bridge itself retries a miss after 60 s). */
    private static final long[] RETRY_MS = {65_000L, 150_000L, 300_000L};

    private ClipThumbs() {}

    /** Show the thumbnail for this clip in iv: at once from memory, else when it arrives. */
    static void into(Context ctx, Live.Clip c, ImageView iv) {
        final String key = c.id;
        iv.setTag(key);
        Bitmap hit = CACHE.get(key);
        iv.setImageBitmap(hit);
        if (hit != null || c.thumb == null || c.thumb.isEmpty()) return;
        load(Live.blinkHost(ctx.getApplicationContext()) + c.thumb, key, iv, 0);
    }

    private static void load(String url, String key, ImageView iv, int attempt) {
        POOL.execute(() -> {
            if (!key.equals(iv.getTag())) return;   // scrolled away before its turn (a hint; checked again on main)
            Bitmap b = CACHE.get(key);
            if (b == null) b = fetch(url);
            if (b != null) CACHE.put(key, b);
            final Bitmap done = b;
            MAIN.post(() -> {
                if (!key.equals(iv.getTag())) return;
                if (done != null) { iv.setImageBitmap(done); return; }
                if (attempt >= RETRY_MS.length) return;
                // weakly: a closed screen's rows aren't kept alive for minutes by a pending retry
                final java.lang.ref.WeakReference<ImageView> ref = new java.lang.ref.WeakReference<>(iv);
                MAIN.postDelayed(() -> {
                    ImageView v = ref.get();
                    if (v == null || !key.equals(v.getTag()) || !v.isAttachedToWindow()) return;
                    Bitmap got = CACHE.get(key);
                    if (got != null) v.setImageBitmap(got); else load(url, key, v, attempt + 1);
                }, RETRY_MS[attempt]);
            });
        });
    }

    /** No thumbnail in memory for this clip yet (it failed, or never loaded). */
    static boolean missing(Live.Clip c) { return c.thumb != null && !c.thumb.isEmpty() && CACHE.get(c.id) == null; }

    private static Bitmap fetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(30000);   // the first look at an old clip runs ffmpeg on the hub
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;            // 480 wide from the bridge -> 240, plenty for a row
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeStream(c.getInputStream(), null, o);
            c.disconnect();
            return b;
        } catch (Exception e) { return null; }
    }
}
