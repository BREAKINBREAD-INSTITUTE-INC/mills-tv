package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/** Settings > Screensaver > Preview now: the real screensaver scene, full screen. Any key closes it. */
public class DreamPreviewActivity extends Activity {

    private DreamScene scene;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        scene = new DreamScene(this, root, false);
        scene.build();
        scene.start();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { finish(); overridePendingTransition(0, 0); return true; }
    @Override protected void onDestroy() { scene.stop(); super.onDestroy(); }
}
