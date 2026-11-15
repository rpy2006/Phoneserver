package com.yadaventerprise.phoneserver.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;

/** Starts the server automatically on device boot, if the user has enabled that setting. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        BackupScheduler.scheduleNext(context);
        ScheduledTaskScheduler.scheduleNext(context);
        if (!ContentStore.getStartOnBoot(context)) {
            ContentStore.setServerDesiredRunning(context, false);
            ServerWatchdog.cancel(context);
            return;
        }

        ContentStore.setServerDesiredRunning(context, true);
        ServerWatchdog.scheduleNext(context);
        Intent start = new Intent(context, ServerService.class);
        start.setAction(ServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(start);
        } else {
            context.startService(start);
        }
    }
}
