package com.gathin.tvhome;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

/**
 * The quiet status line under the clock. REAL data only:
 *   - outdoor temperature + sky from Open-Meteo (no key); its sunrise/sunset also drives Auto light
 *   - the AI team line from Mission Control's /api/status: "AI team · 1 up · 3 partial" when it answers,
 *     "AI team not connected" when it doesn't. The dot takes the team's worst state from the hub
 *     (green up, orange partial, red down), and is grey whenever that feed isn't fresh.
 * Never shows a healthy state it can't prove.
 */
final class HomeStatus {

    private HomeStatus() {}

    interface Ready { void ready(); }

    static void refresh(Context ctx, final TextView tv, final View dot, final Ui.T t, final Ready after) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            final Live.Weather w = Live.fetchWeatherBlocking(app);
            Live.fetchHouseBlocking(app);
            tv.post(() -> Live.fetchMission(app, m -> {
                tv.setText(line(w, m));
                if (dot != null) dot.getBackground().setTint(t.state(m.teamState()));
                if (after != null) after.ready();
            }));
        }, "mills-status").start();
    }

    static String line(Live.Weather w, Live.Mission m) {
        StringBuilder sb = new StringBuilder();
        if (w.has()) {
            sb.append(w.tempF).append('°');
            String sky = w.sky();
            if (!sky.isEmpty()) sb.append(' ').append(sky);
        }
        if (sb.length() > 0) sb.append("   ·   ");
        if (m.ok && !m.stale()) sb.append("AI team · ").append(m.summary(false));
        else if (m.at > 0) sb.append("AI team · last seen ").append(Live.ago(m.at));
        else sb.append("AI team not connected");
        return sb.toString();
    }
}
