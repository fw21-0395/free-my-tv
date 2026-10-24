package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.Calendar;
import java.util.Locale;

/**
 * weekly_restart in config.properties ("sun 4": Sundays around 4 AM): restarts the TV once a week through its own ADB,
 * which clears what builds up over days on low-memory TVs. Scheduled again at every boot and app update.
 */
final class WeeklyRestart {
    private WeeklyRestart() {}

    static final String ACTION = "io.freemytv.WEEKLY_RESTART";
    private static final String[] DAYS = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};

    /** The next restart as a Calendar, or null without weekly_restart (or with one that doesn't parse). */
    private static Calendar next() {
        String[] parts = Config.WEEKLY_RESTART.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length != 2) return null;
        int day = -1;
        for (int i = 0; i < DAYS.length; i++) if (parts[0].startsWith(DAYS[i])) day = Calendar.SUNDAY + i;
        int hour;
        try {
            hour = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (day < 0 || hour < 0 || hour > 23) return null;
        Calendar at = Calendar.getInstance();
        at.set(Calendar.DAY_OF_WEEK, day);
        at.set(Calendar.HOUR_OF_DAY, hour);
        at.set(Calendar.MINUTE, 0);
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        while (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.WEEK_OF_YEAR, 1);
        return at;
    }

    static void schedule(Context c) {
        Calendar at = next();
        if (at == null) return;
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent intent = new Intent(ACTION).setClass(c, HomeReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // A 30-minute window: no exact-alarm permission needed, and 4:00 or 4:20 makes no difference.
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), 30 * 60_000L, pi);
        AppLog.w("weekly restart: next " + at.getTime());
    }

    /** The alarm went off: restart, unless the TV started less than 12 hours ago (then just schedule the next one). */
    static void run(Context c, BroadcastReceiver.PendingResult result) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (SystemClock.elapsedRealtime() < 12 * 3_600_000L) {
                    schedule(app);
                    return;
                }
                AppLog.w("weekly restart: restarting the TV");
                try (Adb adb = Adb.connect(app, 10_000, null)) {
                    adb.shell("reboot");
                }
            } catch (Exception e) {
                AppLog.w("weekly restart: " + e.getMessage());
                schedule(app);
            } finally {
                result.finish();
            }
        }, "weekly-restart").start();
    }
}
