package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The video backgrounds (Settings > Background > Video). The clips are plain files in the app's
 * own external folder, /sdcard/Android/data/com.gathin.tvhome/files/backgrounds/, which needs no
 * permission and is listed fresh each time, so a clip pushed there later shows up without a
 * rebuild. The file name is the label: ocean-1.mp4 is "Ocean 1".
 *
 * A video background is stored as the wallpaper id "video:<file name>", or "video:*" for Rotate
 * videos (the clip it is on is kept in "video_now"). Wallpapers.get() turns either into a dark,
 * Nebula-based wallpaper, so the theme, frost fallback and dimmed screens all keep working, and
 * when a clip is missing or fails to play the moving Nebula is what shows.
 */
final class VideoBackgrounds {
    private VideoBackgrounds() {}

    static final String TAG = "MillsVideo";
    static final String PREFIX = "video:";
    static final String ROTATE = PREFIX + "*";
    private static final String K_NOW = "video_now";
    private static final String[] THEMES = {"ocean", "forest", "waterfall", "city"};   // the order in Settings
    private static final long FRAME_US = 2_000_000L;   // thumbnails and stills: 2 s in, past any fade

    static final class Clip {
        final File file;
        final String id, label;
        Clip(File f) { file = f; id = PREFIX + f.getName(); label = label(f.getName()); }
    }

    static boolean isVideo(String id) { return id != null && id.startsWith(PREFIX); }
    static boolean isRotate(String id) { return ROTATE.equals(id); }

    static File dir(Context c) { return c.getExternalFilesDir("backgrounds"); }

    /** Every clip in the folder: the four themes first, then anything else, by name. */
    static List<Clip> list(Context c) {
        List<Clip> out = new ArrayList<>();
        File d = dir(c);
        File[] fs = d == null ? null : d.listFiles();
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> {
            int ta = theme(a.getName()), tb = theme(b.getName());
            return ta != tb ? ta - tb : a.getName().compareToIgnoreCase(b.getName());
        });
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && f.length() > 0 && (n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mkv") || n.endsWith(".webm")))
                out.add(new Clip(f));
        }
        return out;
    }

    private static int theme(String name) {
        String n = name.toLowerCase(Locale.US);
        for (int i = 0; i < THEMES.length; i++) if (n.startsWith(THEMES[i])) return i;
        return THEMES.length;
    }

    /** "ocean-1.mp4" → "Ocean 1"; "video:*" → "Rotate videos". */
    static String label(String idOrName) {
        if (isRotate(idOrName)) return "Rotate videos";
        String n = idOrName.startsWith(PREFIX) ? idOrName.substring(PREFIX.length()) : idOrName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replace('-', ' ').replace('_', ' ').trim();
        StringBuilder sb = new StringBuilder();
        for (String w : n.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? "Video" : sb.toString();
    }

    /** The file this wallpaper id plays now, or null (not a video, or the clip is gone). */
    static File fileFor(Context c, String id) {
        if (!isVideo(id)) return null;
        if (isRotate(id)) {
            List<Clip> all = list(c);
            if (all.isEmpty()) return null;
            String now = Prefs.prefs(c).getString(K_NOW, "");
            for (Clip k : all) if (k.file.getName().equals(now)) return k.file;
            return all.get(0).file;
        }
        File d = dir(c);
        File f = d == null ? null : new File(d, id.substring(PREFIX.length()));
        return f != null && f.isFile() ? f : null;
    }

    /**
     * Settings > Background > Rotation across the clips: a single clip steps to the next clip,
     * Rotate videos steps its own "now". Returns true when the background changed.
     */
    static boolean rotate(Context c, String id) {
        List<Clip> all = list(c);
        if (all.size() < 2) return false;
        File cur = fileFor(c, id);
        int i = 0;
        for (int k = 0; k < all.size(); k++) if (cur != null && all.get(k).file.equals(cur)) i = k;
        Clip next = all.get((i + 1) % all.size());
        if (isRotate(id)) Prefs.prefs(c).edit().putString(K_NOW, next.file.getName()).apply();
        else Prefs.setWallpaper(c, next.id);
        return true;
    }

    // ---- frames: thumbnails, the Reduced-motion still, the dock frost --------------------------

    private static final LruCache<String, Bitmap> FRAMES = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler worker;
    private static synchronized Handler worker() {
        if (worker == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("mills-video-frames", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }

    interface Got { void got(Bitmap b); }

    /** A frame of this clip at w x h (cached), delivered on the main thread; null if it can't be read. */
    static void frame(File f, int w, int h, Got cb) {
        if (f == null) { cb.got(null); return; }
        final String key = f.getAbsolutePath() + "@" + f.lastModified() + ":" + w + "x" + h;
        Bitmap hit = FRAMES.get(key);
        if (hit != null) { cb.got(hit); return; }
        worker().post(() -> {
            Bitmap b = frameNow(f, w, h);
            if (b != null) FRAMES.put(key, b);
            MAIN.post(() -> cb.got(b));
        });
    }

    /** The same, on the calling (background) thread. */
    static Bitmap frameNow(File f, int w, int h) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            Bitmap b = r.getScaledFrameAtTime(FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
            if (b != null && (b.getWidth() != w || b.getHeight() != h)) b = Bitmap.createScaledBitmap(b, w, h, true);
            return b;
        } catch (Exception e) {
            return null;   // the player reports the clip once (VideoBackdrop.fail); thumbnails stay quiet
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }
}
