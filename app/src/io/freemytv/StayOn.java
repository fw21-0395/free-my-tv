package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

/**
 * For a TV that should always show the cameras (camera_hdmi): auto_wake_minutes after it's switched off, it's switched
 * back on; with start_on_cameras, every time it's switched on it goes to the cameras. Both go through the TV's own
 * ADB: an app may not open another app's screen from the background, and the TV's shell may.
 */
final class StayOn {
    private StayOn() {}

    static final String ACTION = "io.freemytv.AUTO_WAKE";
    private static BroadcastReceiver screen;   // one per process (the home screen's)

    /** Listens for the screen going off and on (from the home screen's process, which is always running). */
    static void watch(Context c) {
        if (screen != null || (!Config.START_ON_CAMERAS && Config.AUTO_WAKE_MINUTES <= 0)) return;
        screen = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    scheduleWake(context);
                } else {
                    cancelWake(context);
                    if (Config.START_ON_CAMERAS) showCameras(context);
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        c.getApplicationContext().registerReceiver(screen, filter);
    }

    private static PendingIntent wakeIntent(Context c) {
        Intent intent = new Intent(ACTION).setClass(c, HomeReceiver.class);
        return PendingIntent.getBroadcast(c, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleWake(Context c) {
        if (Config.AUTO_WAKE_MINUTES <= 0) return;
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long at = System.currentTimeMillis() + Config.AUTO_WAKE_MINUTES * 60_000L;
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, wakeIntent(c));
        AppLog.w("switched off: back on in " + Config.AUTO_WAKE_MINUTES + " min");
    }

    private static void cancelWake(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(wakeIntent(c));
    }

    /** The alarm went off (main process): switch the TV on if it's still off; the screen-on notice shows the cameras. */
    static void wake(Context c, BroadcastReceiver.PendingResult result) {
        final Context app = c.getApplicationContext();
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        // Asleep, the TV suspends again within seconds: stay awake until the TV's ADB has answered.
        final PowerManager.WakeLock awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "freemytv:auto-wake");
        awake.acquire(90_000L);
        new Thread(() -> {
            try {
                for (int attempt = 0; attempt < 6 && !power.isInteractive(); attempt++) {
                    if (attempt == 0) AppLog.w("switching the TV back on");
                    try (Adb adb = Adb.connect(app, 10_000, null)) {
                        adb.shell("input keyevent " + android.view.KeyEvent.KEYCODE_WAKEUP);
                    } catch (Exception e) {
                        AppLog.w("auto wake: " + e.getMessage());
                        Thread.sleep(5_000);
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (awake.isHeld()) awake.release();
                result.finish();
            }
        }, "auto-wake").start();
    }

    /** Opens the cameras' input through the TV's shell (the screen just came on: no app is in front to allow it). */
    static void showCameras(Context c) {
        Intent cameras = HomeApps.cameras(c, false);   // also before the recorder sends a picture: it may start later
        if (cameras == null) return;
        final Context app = c.getApplicationContext();
        final String uri = cameras.getData().toString();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                adb.shell("am start -a android.intent.action.VIEW -d '" + uri + "'");
            } catch (Exception e) {
                AppLog.w("couldn't show the cameras: " + e.getMessage());
            }
        }, "cameras").start();
    }
}
