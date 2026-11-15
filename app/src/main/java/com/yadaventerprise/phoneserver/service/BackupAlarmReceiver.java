package com.yadaventerprise.phoneserver.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Receives the scheduled backup alarm and runs backups off the main thread. */
public class BackupAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "BackupAlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !BackupScheduler.ACTION_RUN.equals(intent.getAction())) return;
        if (!com.yadaventerprise.phoneserver.data.storage.BackupScheduleStore.get(context).get().enabled) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                BackupScheduler.runNow(app);
            } catch (Exception e) {
                Log.e(TAG, "scheduled backup failed", e);
            } finally {
                pending.finish();
            }
        }, "PhoneServer-Backup").start();
    }
}
