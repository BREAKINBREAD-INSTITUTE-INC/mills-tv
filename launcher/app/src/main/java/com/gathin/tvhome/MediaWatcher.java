package com.gathin.tvhome;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds Watched (our own Continue Watching) from the TV's media sessions. It is a notification
 * listener only because that is what lets MediaSessionManager.getActiveSessions() answer an
 * ordinary app; it never reads, stores or logs a notification (onNotificationPosted is left as the
 * no-op it is). Granted over adb (`cmd notification allow_listener`), and rebound by the system
 * after a reboot. Event driven: session and controller callbacks, no polling.
 */
public class MediaWatcher extends NotificationListenerService {
    private static final String TAG = "MillsMediaWatcher";

    private static MediaWatcher live;                      // set while bound, main thread only
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, Tracker> trackers = new HashMap<>();
    private MediaSessionManager msm;

    static ComponentName component(Context c) { return new ComponentName(c, MediaWatcher.class); }

    /** Whether the listener grant is in place (the adb step in the README). */
    static boolean granted(Context c) {
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(component(c));
        }
        String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(component(c).flattenToString());
    }

    /** Theater calls this before reading Watched, so a title still playing shows its latest position. */
    static void flush() {
        MediaWatcher w = live;
        if (w == null) return;
        for (Tracker t : w.trackers.values()) t.record();
    }

    /** Something on the TV is playing right now (the moving backdrops hold still meanwhile). Main thread. */
    static boolean playing() {
        MediaWatcher w = live;
        if (w == null) return false;
        for (Tracker t : w.trackers.values()) {
            if (t.state != null && t.state.getState() == PlaybackState.STATE_PLAYING) return true;
        }
        return false;
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    @Override
    public void onListenerConnected() {
        live = this;
        msm = getSystemService(MediaSessionManager.class);
        try {
            msm.addOnActiveSessionsChangedListener(changed, component(this), main);
            track(msm.getActiveSessions(component(this)));
            Log.i(TAG, "watching media sessions");
        } catch (SecurityException e) {
            Log.w(TAG, "media sessions refused: " + e.getMessage());
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(changed);
        msm = null;
        for (Tracker t : trackers.values()) { t.record(); t.c.unregisterCallback(t); }
        trackers.clear();
        if (live == this) live = null;
    }

    @Override
    public void onDestroy() {
        onListenerDisconnected();
        super.onDestroy();
    }

    private void track(List<MediaController> sessions) {
        if (sessions == null) sessions = new ArrayList<>();
        Map<MediaSession.Token, MediaController> now = new HashMap<>();
        for (MediaController c : sessions) {
            if (getPackageName().equals(c.getPackageName())) continue;
            now.put(c.getSessionToken(), c);
        }
        for (Map.Entry<MediaSession.Token, Tracker> e : new ArrayList<>(trackers.entrySet())) {
            if (now.containsKey(e.getKey())) continue;
            Log.i(TAG, e.getValue().c.getPackageName() + " session gone");
            e.getValue().record();
            e.getValue().c.unregisterCallback(e.getValue());
            trackers.remove(e.getKey());
        }
        for (Map.Entry<MediaSession.Token, MediaController> e : now.entrySet()) {
            if (trackers.containsKey(e.getKey())) continue;
            Tracker t = new Tracker(e.getValue());
            trackers.put(e.getKey(), t);
            e.getValue().registerCallback(t, main);
            Log.i(TAG, "session from " + e.getValue().getPackageName());
            t.onMetadataChanged(e.getValue().getMetadata());
            t.onPlaybackStateChanged(e.getValue().getPlaybackState());
        }
    }

    /**
     * One session. It remembers the last metadata and state it saw, so when the session moves on
     * to the next title, the one before is recorded with where it really stopped.
     */
    private final class Tracker extends MediaController.Callback {
        final MediaController c;
        MediaMetadata meta;
        PlaybackState state;
        boolean played;                                    // this title has been seen playing

        Tracker(MediaController c) { this.c = c; }

        @Override public void onMetadataChanged(MediaMetadata m) {
            if (m != null && meta != null && !sameTitle(m, meta)) { record(); played = false; }
            meta = m;
            record();
        }

        @Override public void onPlaybackStateChanged(PlaybackState s) {
            record();                                      // where the old state left off
            state = s;
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) played = true;
            if (s != null) Log.i(TAG, c.getPackageName() + " state " + s.getState() + " at " + s.getPosition() / 1000 + " s");   // never titles
            record();
        }

        @Override public void onSessionDestroyed() { Log.i(TAG, c.getPackageName() + " session ended"); record(); }

        void record() {
            if (meta == null || state == null || !played) return;
            String title = text(meta, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null || placeholder(c.getPackageName(), title)) return;
            String show = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM,
                    MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            long dur = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
            // Shorter than the 2 minutes a card needs: an ad (YouTube's pre-rolls can carry the
            // video's own title) or a short. Never shown, so never let it overwrite the real item.
            if (dur > 0 && dur < Watched.MIN_WATCHED_MS) return;
            Watched.record(MediaWatcher.this, c.getPackageName(), title, show, position(state), dur,
                    c.getSessionActivity(), art(meta), artUri(meta));
        }
    }

    /** Idle sessions often carry the app's own name as the title (Prime Video: "PrimeVideo"). */
    private boolean placeholder(String pkg, String title) {
        String t = title.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        try {
            CharSequence label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0));
            return t.equals(label.toString().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sameTitle(MediaMetadata a, MediaMetadata b) {
        String x = text(a, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String y = text(b, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return x == null ? y == null : x.equals(y);
    }

    /** The position now: the state's last report, run forward at its speed while playing. */
    private static long position(PlaybackState s) {
        long pos = s.getPosition();
        if (pos < 0) return 0;
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            long since = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
            if (since > 0) pos += (long) (since * s.getPlaybackSpeed());
        }
        return pos;
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    private static Bitmap art(MediaMetadata m) {
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    private static String artUri(MediaMetadata m) {
        return text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
    }
}
