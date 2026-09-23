package com.yadaventerprise.phoneserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts the server automatically on device boot, if the user has enabled that setting. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!ContentStore.getStartOnBoot(context)) return;

        Intent start = new Intent(context, ServerService.class);
        start.setAction(ServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(start);
        } else {
            context.startService(start);
        }
    }
}
