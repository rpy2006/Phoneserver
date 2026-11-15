package com.yadaventerprise.phoneserver.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import java.util.List;

public final class ScheduledTaskScheduler {

    public static final String ACTION_RUN = "com.yadaventerprise.phoneserver.RUN_SCHEDULED_TASK";
    private static final int REQUEST_CODE = 4301;

    private ScheduledTaskScheduler() {
    }

    public static void scheduleNext(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = pendingIntent(app);
        if (manager == null) return;
        manager.cancel(pending);

        List<ScheduledTask> tasks = ScheduledTaskStore.get(app).enabled();
        long earliest = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (ScheduledTask task : tasks) {
            long next = task.nextRunAt > 0 ? task.nextRunAt : now + task.intervalMinutes * 60_000L;
            if (next < earliest) earliest = next;
        }
        if (earliest == Long.MAX_VALUE) return;
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                Math.max(earliest, now + 1000L), pending);
    }

    public static void cancel(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.cancel(pendingIntent(app));
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, ScheduledTaskReceiver.class);
        intent.setAction(ACTION_RUN);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
