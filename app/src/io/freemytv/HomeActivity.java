package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        StayOn.watch(this);
        // The first home screen after the TV starts (a restart, a power cut): straight to the cameras, once.
        if (Config.START_ON_CAMERAS && SystemClock.elapsedRealtime() < 5 * 60_000L && firstSinceBoot(this)) {
            StayOn.showCameras(this);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** True only the first time it's asked after the TV booted (a file keeps the boot's time, to the minute). */
    private static boolean firstSinceBoot(Context c) {
        long boot = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L;
        File f = new File(c.getFilesDir(), "cameras-boot.txt");
        try {
            String last = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (Math.abs(Long.parseLong(last) - boot) <= 1) return false;
        } catch (IOException | NumberFormatException ignored) {
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(String.valueOf(boot).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        return true;
    }


    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
