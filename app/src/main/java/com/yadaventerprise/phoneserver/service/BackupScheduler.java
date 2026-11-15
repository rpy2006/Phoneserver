package com.yadaventerprise.phoneserver.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.storage.BackupManager;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.data.storage.BackupSchedule;
import com.yadaventerprise.phoneserver.data.storage.BackupScheduleStore;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.network.http.DataException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Runs automatic project backups with AlarmManager (no WorkManager dependency). */
public final class BackupScheduler {

    private static final String TAG = "BackupScheduler";
    public static final String ACTION_RUN = "com.yadaventerprise.phoneserver.BACKUP_RUN";
    public static final int REQUEST_CODE = 9101;
    private static final String SCHEDULED_NOTE = "Scheduled backup";

    private BackupScheduler() {
    }

    public static void scheduleNext(Context context) {
        Context app = context.getApplicationContext();
        BackupSchedule schedule = BackupScheduleStore.get(app).get();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        PendingIntent pending = pendingIntent(app);
        if (!schedule.enabled) {
            alarmManager.cancel(pending);
            schedule.nextRunAt = 0;
            BackupScheduleStore.get(app).save(schedule);
            return;
        }
        long now = System.currentTimeMillis();
        long next = BackupSchedule.computeNextRun(schedule.lastRunAt, schedule.intervalHours, now);
        schedule.nextRunAt = next;
        BackupScheduleStore.get(app).save(schedule);
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
        } catch (SecurityException e) {
            Log.w(TAG, "alarm permission denied; using inexact alarm", e);
            alarmManager.set(AlarmManager.RTC_WAKEUP, next, pending);
        }
    }

    public static void cancel(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(pendingIntent(app));
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, BackupAlarmReceiver.class);
        intent.setAction(ACTION_RUN);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    /** Runs a backup pass immediately. Safe to call from a background thread. */
    public static RunResult runNow(Context context) {
        Context app = context.getApplicationContext();
        BackupSchedule schedule = BackupScheduleStore.get(app).get();
        RunResult result = new RunResult();
        long started = System.currentTimeMillis();
        List<Records.ProjectRecord> projects;
        try {
            projects = ProjectStore.list(app);
        } catch (Exception e) {
            result.failureCount = 1;
            result.errors.add("could not list projects: " + e.getMessage());
            finish(app, schedule, result, started);
            return result;
        }

        for (Records.ProjectRecord project : projects) {
            result.projectCount++;
            try {
                BackupManager.create(app, project.id, schedule.includeFiles, SCHEDULED_NOTE);
                result.successCount++;
                rotate(app, project.id, schedule.retainPerProject);
                JSONObject hookData = new JSONObject();
                try {
                    hookData.put("projectId", project.id);
                    hookData.put("projectName", project.name);
                    hookData.put("includeFiles", schedule.includeFiles);
                } catch (Exception ignored) {
                }
                WebhookDispatcher.emit(app, WebhookDispatcher.EVENT_BACKUP_COMPLETED, hookData);
            } catch (Exception e) {
                result.failureCount++;
                String message = project.name + ": " + safeMessage(e);
                if (result.errors.size() < 10) result.errors.add(message);
            }
        }
        finish(app, schedule, result, started);
        return result;
    }

    private static void finish(Context context, BackupSchedule schedule, RunResult result, long started) {
        schedule.lastRunAt = System.currentTimeMillis();
        schedule.lastSuccessCount = result.successCount;
        schedule.lastFailureCount = result.failureCount;
        schedule.lastResult = result.failureCount == 0
                ? (result.projectCount == 0 ? "no-projects" : "success")
                : (result.successCount == 0 ? "failed" : "partial");
        schedule.lastError = result.errors.isEmpty() ? "" : result.errors.get(0);
        BackupScheduleStore.get(context).save(schedule);
        scheduleNext(context);
        result.durationMs = System.currentTimeMillis() - started;
        Log.i(TAG, "backup pass finished: " + result.successCount + " ok, "
                + result.failureCount + " failed in " + result.durationMs + "ms");
    }

    private static void rotate(Context context, String projectId, int retain) {
        try {
            List<Records.BackupRecord> backups = BackupManager.list(context, projectId);
            int scheduled = 0;
            for (Records.BackupRecord backup : backups) {
                if (!SCHEDULED_NOTE.equals(backup.note)) continue;
                scheduled++;
                if (scheduled <= Math.max(1, retain)) continue;
                try {
                    BackupManager.delete(context, projectId, backup.id);
                } catch (DataException e) {
                    Log.w(TAG, "could not delete old scheduled backup", e);
                }
            }
        } catch (DataException e) {
            Log.w(TAG, "could not list backups for rotation", e);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    public static class RunResult {
        public int projectCount;
        public int successCount;
        public int failureCount;
        public long durationMs;
        public final List<String> errors = new ArrayList<>();

        public String summary() {
            if (projectCount == 0) return "No projects to back up";
            String text = successCount + " backed up";
            if (failureCount > 0) text += ", " + failureCount + " failed";
            if (!errors.isEmpty()) text += ": " + errors.get(0);
            return text;
        }
    }
}
