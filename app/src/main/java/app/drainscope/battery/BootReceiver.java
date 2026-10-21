package app.drainscope.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Keeps the periodic sampler alive across reboots. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            SampleJob.schedule(context);
        }
    }
}
