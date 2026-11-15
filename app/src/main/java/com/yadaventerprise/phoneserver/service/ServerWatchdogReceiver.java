package com.yadaventerprise.phoneserver.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;

public class ServerWatchdogReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ServerWatchdog.ACTION_CHECK.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        try {
            if (ContentStore.getServerDesiredRunning(app) && !ServerService.isServerRunning()) {
                Intent start = new Intent(app, ServerService.class);
                start.setAction(ServerService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(start);
                } else {
                    app.startService(start);
                }
            }
        } catch (Exception e) {
            Log.w("ServerWatchdog", "could not restart server", e);
        } finally {
            if (ContentStore.getServerDesiredRunning(app)) ServerWatchdog.scheduleNext(app);
        }
    }
}
