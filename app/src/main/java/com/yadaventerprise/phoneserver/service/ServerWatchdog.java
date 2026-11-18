package com.yadaventerprise.phoneserver.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.system.AutomationSettings;
import com.yadaventerprise.phoneserver.data.system.AutomationSettingsStore;

public final class ServerWatchdog {

    public static final String ACTION_CHECK = "com.yadaventerprise.phoneserver.WATCHDOG_CHECK";
    private static final int REQUEST_CODE = 9201;

    private ServerWatchdog() {
    }

    public static void scheduleNext(Context context) {
        Context app = context.getApplicationContext();
        AutomationSettings settings = AutomationSettingsStore.get(app).get();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        if (!settings.autoRestartEnabled) {
            alarmManager.cancel(pendingIntent(app));
            return;
        }
        long interval = Math.max(1, settings.watchdogIntervalMinutes) * 60L * 1000L;
        long next = System.currentTimeMillis() + interval;
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent(app));
        } catch (SecurityException e) {
            Log.w("ServerWatchdog", "could not schedule exact watchdog", e);
            alarmManager.set(AlarmManager.RTC_WAKEUP, next, pendingIntent(app));
        }
    }

    public static void cancel(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(pendingIntent(context));
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, ServerWatchdogReceiver.class);
        intent.setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
