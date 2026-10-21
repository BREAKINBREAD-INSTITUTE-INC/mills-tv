package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.widget.FrameLayout;

/**
 * The MILLS screensaver. Everything it shows, and how, comes from Settings > Screensaver
 * (see DreamScene). Any key wakes the TV back to where it was.
 */
public class ClockDream extends DreamService {

    private DreamScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        if (SleepService.running() || SleepActivity.shown()) {   // Sleep: stay black until the TV goes to standby
            root.setBackgroundColor(0xFF000000);
            return;
        }
        scene = new DreamScene(this, root, false);
        scene.build();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (scene != null) scene.start();
    }

    @Override
    public void onDreamingStopped() {
        if (scene != null) scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
