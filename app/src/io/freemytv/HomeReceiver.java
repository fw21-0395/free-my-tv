package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, this app was updated, the weekly restart or the auto wake is due. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            WeeklyRestart.schedule(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            WeeklyRestart.schedule(context);
        } else if (WeeklyRestart.ACTION.equals(action)) {
            WeeklyRestart.run(context, goAsync());
        } else if (StayOn.ACTION.equals(action)) {
            StayOn.wake(context, goAsync());
        }
    }
}
