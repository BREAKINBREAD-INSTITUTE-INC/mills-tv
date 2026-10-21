package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * The launcher's Application. Its one job is to arm the crash watchdog before any screen is
 * drawn, so the first thing alive in the process is the thing that keeps the TV from getting
 * stranded.
 */
public class MillsApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Watchdog.install(this);
        Prefs.ensureSeeded(this);
        maybeArmDebugCrashHook();
    }

    /**
     * TEST ONLY. Lets `adb shell am broadcast -a com.gathin.tvhome.DEBUG_CRASH` force a real
     * crash so the watchdog can be proven on the emulator. It is registered ONLY when the build
     * is debuggable — a release build (what ships to the TV) never arms it, so there is nothing
     * to fire. This is a deliberate, inert-in-production hook, not a shippable crash trigger.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void maybeArmDebugCrashHook() {
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    throw new RuntimeException("MILLS TV debug crash (test only)");
                });
            }
        };
        IntentFilter f = new IntentFilter("com.gathin.tvhome.DEBUG_CRASH");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }
}
