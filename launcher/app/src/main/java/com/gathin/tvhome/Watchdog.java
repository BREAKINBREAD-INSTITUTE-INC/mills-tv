package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MILLS TV safety net.
 *
 * Installs a last-resort crash handler so a bad build can never strand the TV on a black
 * screen. Every uncaught crash is written to a log we can read, and counted inside a rolling
 * 5-minute window. Three crashes in that window trips "safe mode": instead of letting the
 * home keep crashing on every boot, we open Android's own Home-app picker so the stock Sony
 * home can be chosen, and we stop relaunching ourselves.
 *
 * Nothing here is rooted or hidden. restoreStockHome() just opens the real Settings screen,
 * which is also what the one-button "Original" return does. The watchdog and the button share
 * the exact same, honest code path.
 */
final class Watchdog {

    private static final String TAG = "MillsWatchdog";

    // Rolling window.
    private static final long WINDOW_MS = 5 * 60 * 1000L;   // 5 minutes
    private static final int  TRIP_COUNT = 3;               // crashes in the window that trip safe mode
    // How long the home must run without crashing before we call it healthy and clear the count.
    static final long STABLE_AFTER_MS = 20 * 1000L;         // 20 seconds

    private static final String PREFS = "mills_watchdog";
    private static final String K_COUNT  = "crash_count";
    private static final String K_FIRST  = "window_start";
    private static final String K_SAFE   = "safe_mode";
    private static final String K_LAST   = "last_crash_ms";

    private Watchdog() {}

    /** Arm the handler. Call once, as early as possible (Application.onCreate). */
    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                record(app, thread, ex);
            } catch (Throwable ignored) {
                // A watchdog must never crash while handling a crash.
            }
            // Hand back to the platform so the crash is still reported normally.
            if (prev != null) prev.uncaughtException(thread, ex);
            else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /** Record one crash, roll the window, and trip safe mode if we've crashed too often. */
    private static void record(Context ctx, Thread thread, Throwable ex) {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences p = prefs(ctx);
        long start = p.getLong(K_FIRST, 0L);
        int count = p.getInt(K_COUNT, 0);

        if (start == 0L || now - start > WINDOW_MS) {
            start = now;        // open a fresh window
            count = 0;
        }
        count++;

        android.content.SharedPreferences.Editor e = p.edit();
        e.putLong(K_FIRST, start);
        e.putInt(K_COUNT, count);
        e.putLong(K_LAST, now);
        if (count >= TRIP_COUNT) e.putBoolean(K_SAFE, true);
        e.commit();            // commit, not apply — we are about to die

        writeLog(ctx, thread, ex, count);
        Log.e(TAG, "uncaught crash #" + count + " in window", ex);
    }

    /** True if we tripped the crash loop and have not recovered since. */
    static boolean inSafeMode(Context ctx) {
        return prefs(ctx).getBoolean(K_SAFE, false);
    }

    /** The home ran long enough without crashing — clear the window and leave safe mode. */
    static void markStable(Context ctx) {
        prefs(ctx).edit()
                .putInt(K_COUNT, 0)
                .putLong(K_FIRST, 0L)
                .putBoolean(K_SAFE, false)
                .apply();
    }

    /**
     * The one-button "Original" return, and the safe-mode fallback. Opens Android's Home-app
     * picker so the stock Sony home can be set as default. Returns false if the TV has no such
     * screen (then the caller can fall back to plain Settings).
     */
    static boolean restoreStockHome(Context ctx) {
        if (tryStart(ctx, Settings.ACTION_HOME_SETTINGS)) return true;
        return tryStart(ctx, Settings.ACTION_SETTINGS);
    }

    private static boolean tryStart(Context ctx, String action) {
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cannot open " + action, t);
            return false;
        }
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void writeLog(Context ctx, Thread thread, Throwable ex, int count) {
        try {
            File dir = new File(ctx.getFilesDir(), "crash");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "last_crash.txt");
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            try (PrintWriter w = new PrintWriter(new FileWriter(f, false))) {
                w.println("MILLS TV crash");
                w.println("time:   " + when);
                w.println("count:  " + count + " in current 5-min window");
                w.println("thread: " + (thread != null ? thread.getName() : "?"));
                w.println("uptime: " + SystemClock.elapsedRealtime() + " ms");
                w.println();
                ex.printStackTrace(w);
            }
        } catch (Throwable ignored) {
        }
    }
}
