package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Sleep sounds: one looping ambient track from a foreground service, so it keeps playing with the
 * screen black. The tracks are plain files in the app's own folder,
 * /sdcard/Android/data/com.gathin.tvhome/files/sleep/ (listed fresh, named by file: rain-on-roof.m4a
 * is "Rain On Roof"). The volume fades in over the first 20 s and out over the last 5 minutes;
 * when the timer ends the sound stops, the service ends, and nothing is scheduled after that, so
 * it can't wake the TV or play again. Losing audio focus for good (another app starts playing)
 * stops it too.
 */
public class SleepService extends Service {

    static final String TAG = "MillsSleep";
    static final String ACTION_START = "com.gathin.tvhome.sleep.START";
    static final String ACTION_STOP = "com.gathin.tvhome.sleep.STOP";
    static final String EXTRA_FILE = "file", EXTRA_END = "end";   // EXTRA_END: elapsedRealtime when it stops

    static final long[] TIMERS_MIN = {30, 60, 120, 240, 600};     // the last is "All night": 10 hours at most
    static final String[] TIMER_LABELS = {"30 min", "1 hour", "2 hours", "4 hours", "All night"};
    private static final long FADE_IN_MS = 20_000, FADE_OUT_MS = 5 * 60_000, STEP_MS = 1000;
    private static final float MAX_VOL = 1f;
    private static final String CHANNEL = "sleep";

    // what is playing, for the Sleep screen and the screensaver (main thread)
    private static File playing;
    private static long endAt, startedAt;
    static boolean running() { return playing != null; }
    static File playing() { return playing; }
    static long msLeft() { return playing == null ? 0 : Math.max(0, endAt - SystemClock.elapsedRealtime()); }

    interface Listener { void changed(); }
    private static final List<Listener> listeners = new ArrayList<>();
    static void listen(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    static void unlisten(Listener l) { listeners.remove(l); }
    private static void notifyChanged() { for (Listener l : new ArrayList<>(listeners)) l.changed(); }

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private AudioManager am;
    private AudioFocusRequest focus;

    // ---- the tracks -------------------------------------------------------------------------------

    static File dir(Context c) { return c.getExternalFilesDir("sleep"); }

    static List<File> sounds(Context c) {
        List<File> out = new ArrayList<>();
        File d = dir(c);
        File[] fs = d == null ? null : d.listFiles();
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && f.length() > 0 && (n.endsWith(".m4a") || n.endsWith(".mp3") || n.endsWith(".ogg")
                    || n.endsWith(".opus") || n.endsWith(".aac") || n.endsWith(".flac") || n.endsWith(".wav")))
                out.add(f);
        }
        return out;
    }

    static String label(File f) { return f == null ? "" : VideoBackgrounds.label(f.getName()); }

    static void start(Context c, File f, long endAtElapsed) {
        Intent i = new Intent(c, SleepService.class).setAction(ACTION_START)
                .putExtra(EXTRA_FILE, f.getAbsolutePath()).putExtra(EXTRA_END, endAtElapsed);
        c.startForegroundService(i);
    }

    static void stop(Context c) {
        if (!running()) return;
        c.startService(new Intent(c, SleepService.class).setAction(ACTION_STOP));
    }

    // ---- the service --------------------------------------------------------------------------------

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        String act = i == null ? null : i.getAction();
        if (ACTION_START.equals(act)) {
            startForeground(1, notification());
            File f = new File(i.getStringExtra(EXTRA_FILE));
            long end = i.getLongExtra(EXTRA_END, SystemClock.elapsedRealtime() + 30 * 60_000L);
            begin(f, end);
        } else {
            finish("stopped");
        }
        return START_NOT_STICKY;                            // never restarted on its own
    }

    private void begin(File f, long end) {
        releasePlayer();
        boolean change = playing != null;                   // switching sound: keep the timer, skip the fade-in
        playing = f;
        endAt = end;
        if (!change) startedAt = SystemClock.elapsedRealtime();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(aa)
                    .setOnAudioFocusChangeListener(ch -> { if (ch == AudioManager.AUDIOFOCUS_LOSS) finish("another app took the audio"); }, h)
                    .build();
            am.requestAudioFocus(focus);
        }
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setAudioAttributes(aa);
            p.setDataSource(f.getAbsolutePath());
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnErrorListener((m, what, extra) -> { finish(f.getName() + " error " + what + "/" + extra); return true; });
            p.setOnPreparedListener(m -> { if (m == mp) { applyVolume(); m.start(); } });
            p.prepareAsync();
        } catch (Exception e) {
            finish(f.getName() + " " + e.getMessage());
            return;
        }
        Log.i(TAG, "playing " + f.getName() + " for " + (end - SystemClock.elapsedRealtime()) / 60_000 + " min");
        h.removeCallbacks(tick);
        h.post(tick);
        notifyChanged();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (playing == null) return;
            if (SystemClock.elapsedRealtime() >= endAt) { finish("timer ended"); return; }
            applyVolume();
            h.postDelayed(this, STEP_MS);
        }
    };

    /** Fade in over 20 s, full, then down to silence over the last 5 minutes. */
    private void applyVolume() {
        if (mp == null) return;
        long now = SystemClock.elapsedRealtime();
        float in = Math.min(1f, (now - startedAt) / (float) FADE_IN_MS);
        float out = Math.min(1f, Math.max(0f, (endAt - now) / (float) FADE_OUT_MS));
        float v = MAX_VOL * in * in * out * out;           // squared: even to the ear, not just the meter
        try { mp.setVolume(v, v); } catch (Exception ignored) {}
    }

    private void finish(String why) {
        h.removeCallbacks(tick);
        releasePlayer();
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
        focus = null;
        if (playing != null) Log.i(TAG, "stop: " + why);
        if ("timer ended".equals(why)) Prefs.prefs(this).edit().putLong("sleep_ended_at", System.currentTimeMillis()).apply();
        playing = null;
        stopForeground(true);
        stopSelf();
        notifyChanged();
    }

    private void releasePlayer() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try { p.reset(); } catch (Exception ignored) {}
        try { p.release(); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        releasePlayer();
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
        if (playing != null) { playing = null; notifyChanged(); }
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sleep sounds", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SleepActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Sleep sounds")
                .setContentText("Playing until the timer ends")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
