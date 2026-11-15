package com.yadaventerprise.phoneserver.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskRunner;

public class ScheduledTaskReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ScheduledTaskScheduler.ACTION_RUN.equals(intent.getAction())) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                ScheduledTaskRunner.runDue(app);
                ScheduledTaskScheduler.scheduleNext(app);
            } finally {
                pending.finish();
            }
        }, "PhoneServer-ScheduledTask").start();
    }
}
