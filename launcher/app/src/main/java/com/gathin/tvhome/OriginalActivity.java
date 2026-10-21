package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * "Original" — the one-button way back to the stock Sony home.
 *
 * It opens Android's real Home-app picker so the factory home can be set as default. This is
 * the manual twin of the watchdog's safe-mode fallback; both call Watchdog.restoreStockHome.
 * Exported so it can be triggered for testing (adb am start) and, later, from a settings
 * button or a remote macro.
 */
public class OriginalActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean ok = Watchdog.restoreStockHome(this);
        if (!ok) {
            Toast.makeText(this, "Could not open the Home picker on this TV", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
