package com.yadaventerprise.phoneserver.data.tasks;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.storage.BackupManager;
import com.yadaventerprise.phoneserver.data.system.SystemEvent;
import com.yadaventerprise.phoneserver.data.system.SystemEventStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryEntry;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import com.yadaventerprise.phoneserver.service.ScheduledTaskScheduler;
import com.yadaventerprise.phoneserver.service.SystemNotifier;
import java.util.List;
import org.json.JSONObject;

public final class ScheduledTaskRunner {

    private ScheduledTaskRunner() {
    }

    public static int runDue(Context context) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        int ran = 0;
        List<ScheduledTask> tasks = ScheduledTaskStore.get(app).enabled();
        for (ScheduledTask task : tasks) {
            if (task.nextRunAt == 0 || task.nextRunAt <= now) {
                execute(app, task);
                ran++;
            }
        }
        return ran;
    }

    public static TaskResult runNow(Context context, String id) {
        Context app = context.getApplicationContext();
        ScheduledTask task = ScheduledTaskStore.get(app).get(id);
        TaskResult result = new TaskResult();
        if (task == null) {
            result.error = "Task not found";
            return result;
        }
        result.success = execute(app, task);
        ScheduledTask updated = ScheduledTaskStore.get(app).get(id);
        result.error = updated == null ? task.lastError : updated.lastError;
        result.retryScheduled = updated != null && "retry".equals(updated.lastStatus);
        result.nextRunAt = updated == null ? 0 : updated.nextRunAt;
        return result;
    }

    private static boolean execute(Context context, ScheduledTask task) {
        long started = System.currentTimeMillis();
        boolean success = false;
        String error = "";
        try {
            if (task.isBackup()) {
                if (task.projectId == null || task.projectId.isEmpty()) {
                    throw new IllegalArgumentException("No project selected");
                }
                BackupManager.create(context, task.projectId, task.includeFiles,
                        "Scheduled task: " + task.name);
                success = true;
            } else if (task.isWebhook()) {
                WebhookConfig hook = WebhookStore.get(context).get(task.webhookId);
                if (hook == null) throw new IllegalArgumentException("Webhook not found");
                JSONObject data = new JSONObject();
                try {
                    data.put("taskId", task.id);
                    data.put("taskName", task.name);
                } catch (Exception ignored) {
                }
                WebhookHistoryEntry entry = WebhookDispatcher.deliverNow(context, hook, "task.run", data);
                success = entry != null && entry.success;
                if (!success) error = entry == null ? "Delivery failed" : entry.error;
            } else {
                throw new IllegalArgumentException("Unknown task action");
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.w("ScheduledTaskRunner", "task failed: " + task.name, e);
        }

        long now = System.currentTimeMillis();
        int attempt = Math.max(1, task.currentAttempt + 1);
        int maxAttempts = Math.max(1, task.maxAttempts);
        boolean retry = !success && attempt < maxAttempts;
        long duration = now - started;

        TaskHistoryEntry history = new TaskHistoryEntry();
        history.time = now;
        history.taskId = task.id;
        history.taskName = task.name;
        history.action = task.action;
        history.success = success;
        history.retryScheduled = retry;
        history.attempt = attempt;
        history.maxAttempts = maxAttempts;
        history.durationMs = duration;
        history.error = retry ? error : error;
        TaskHistoryStore.get(context).append(history);

        task.lastRunAt = now;
        task.failureCount = success ? 0 : task.failureCount + 1;
        if (retry) {
            task.currentAttempt = attempt;
            task.lastStatus = "retry";
            task.lastError = "Attempt " + attempt + "/" + maxAttempts + " failed: " + error;
            task.nextRunAt = now + Math.max(1, task.retryDelayMinutes) * 60_000L;
        } else {
            task.currentAttempt = 0;
            task.lastStatus = success ? "success" : "failed";
            task.lastError = error == null ? "" : error;
            if (task.isOneTime()) {
                task.enabled = false;
                task.nextRunAt = 0;
            } else {
                task.nextRunAt = task.computeNextRunAt(now);
            }
        }
        ScheduledTaskStore.get(context).save(task);
        ScheduledTaskScheduler.scheduleNext(context);

        SystemEventStore events = SystemEventStore.get(context);
        if (retry) {
            events.warn("tasks", "Task '" + task.name + "' attempt " + attempt + "/" + maxAttempts
                    + " failed; retry scheduled");
        } else if (success) {
            events.info("tasks", "Task '" + task.name + "' completed in " + duration + " ms");
        } else {
            events.error("tasks", "Task '" + task.name + "' failed: " + task.lastError);
        }

        if (!retry) {
            JSONObject data = new JSONObject();
            try {
                data.put("taskId", task.id);
                data.put("taskName", task.name);
                data.put("action", task.action);
                data.put("success", success);
                data.put("error", task.lastError);
                data.put("durationMs", duration);
            } catch (Exception ignored) {
            }
            WebhookDispatcher.emit(context,
                    success ? WebhookDispatcher.EVENT_TASK_COMPLETED : WebhookDispatcher.EVENT_TASK_FAILED,
                    data);
            SystemNotifier.taskResult(context, task, success, task.lastError);
        }
        return success;
    }

    public static class TaskResult {
        public boolean success;
        public boolean retryScheduled;
        public long nextRunAt;
        public String error = "";
    }
}
