package com.yadaventerprise.phoneserver.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;
import com.yadaventerprise.phoneserver.data.system.AutomationSettings;
import com.yadaventerprise.phoneserver.data.system.AutomationSettingsStore;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.ui.system.SystemManagementActivity;

public final class SystemNotifier {

    private static final String CHANNEL_ALERTS = "phone_server_alerts";
    private static final String CHANNEL_ROUTINE = "phone_server_routine";
    private static final int ID_SERVER = 3001;
    private static final int ID_TASK = 3002;
    private static final int ID_STORAGE = 3003;
    private static final int ID_MAINTENANCE = 3004;

    private SystemNotifier() {
    }

    public static void serverFailure(Context context, String detail) {
        if (context == null) return;
        AutomationSettings settings = AutomationSettingsStore.get(context).get();
        if (!settings.notifyServerFailures) return;
        notify(context, CHANNEL_ALERTS, ID_SERVER, "Server needs attention",
                detail == null || detail.trim().isEmpty() ? "The server could not start." : detail,
                android.R.drawable.stat_notify_error);
    }

    public static void serverRecovered(Context context) {
        if (context == null) return;
        AutomationSettings settings = AutomationSettingsStore.get(context).get();
        if (!settings.notifyServerRecovery) return;
        notify(context, CHANNEL_ALERTS, ID_SERVER, "Server recovered",
                "PhoneServer is running again.", android.R.drawable.presence_online);
    }

    public static void taskResult(Context context, ScheduledTask task, boolean success, String error) {
        if (context == null || task == null) return;
        AutomationSettings settings = AutomationSettingsStore.get(context).get();
        if (!settings.notifyTaskResults) return;
        String message = success
                ? task.name + " completed successfully."
                : task.name + " failed" + (error == null || error.trim().isEmpty() ? "." : ": " + error);
        notify(context, CHANNEL_ROUTINE, ID_TASK, success ? "Task completed" : "Task failed",
                message, success ? android.R.drawable.presence_online : android.R.drawable.stat_notify_error);
    }

    public static void storageWarning(Context context, long freeBytes) {
        if (context == null) return;
        AutomationSettings settings = AutomationSettingsStore.get(context).get();
        if (!settings.notifyStorageWarnings) return;
        notify(context, CHANNEL_ALERTS, ID_STORAGE, "Low storage",
                "Only " + com.yadaventerprise.phoneserver.data.stats.ServerStats.formatBytes(freeBytes)
                        + " is available on the data volume.",
                android.R.drawable.stat_sys_warning);
    }

    public static void maintenanceFinished(Context context, int issueCount) {
        if (context == null) return;
        notify(context, CHANNEL_ROUTINE, ID_MAINTENANCE, "Maintenance finished",
                issueCount == 0 ? "No issues were found." : issueCount + " issue(s) were found.",
                android.R.drawable.ic_dialog_info);
    }

    private static void notify(Context context, String channel, int id, String title,
                               String message, int smallIcon) {
        Context app = context.getApplicationContext();
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return;
        createChannels(app);
        Intent open = new Intent(app, SystemManagementActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(app, id, open, flags);
        Notification notification = new Notification.Builder(app, channel)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(smallIcon)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        NotificationManagerCompat.from(app).notify(id, notification);
    }

    private static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS, "System alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Server failures, recovery and storage warnings");
        manager.createNotificationChannel(alerts);
        NotificationChannel routine = new NotificationChannel(CHANNEL_ROUTINE, "Automation results",
                NotificationManager.IMPORTANCE_LOW);
        routine.setDescription("Scheduled task and maintenance results");
        manager.createNotificationChannel(routine);
    }
}
