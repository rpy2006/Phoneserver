package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;
import com.yadaventerprise.phoneserver.data.reliability.DiagnosticRedactor;
import com.yadaventerprise.phoneserver.data.security.SecurityEvent;
import com.yadaventerprise.phoneserver.data.security.SecurityEventStore;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.data.system.AutomationSettings;
import com.yadaventerprise.phoneserver.data.system.AutomationSettingsStore;
import com.yadaventerprise.phoneserver.data.system.CrashDiagnosticStore;
import com.yadaventerprise.phoneserver.data.system.DiagnosticsCenter;
import com.yadaventerprise.phoneserver.data.system.MaintenanceReport;
import com.yadaventerprise.phoneserver.data.system.SystemDiagnostics;
import com.yadaventerprise.phoneserver.data.system.SystemLogCollector;
import com.yadaventerprise.phoneserver.data.system.SystemLogSanitizer;
import com.yadaventerprise.phoneserver.data.system.SystemMaintenance;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskRunner;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import com.yadaventerprise.phoneserver.data.tasks.TaskHistoryEntry;
import com.yadaventerprise.phoneserver.data.tasks.TaskHistoryStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryEntry;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import com.yadaventerprise.phoneserver.service.ScheduledTaskScheduler;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.service.ServerWatchdog;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SystemApi {

    private static final Set<String> SUPPORTED_METHODS = new HashSet<>(
            Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE"));
    private static final Set<String> SUPPORTED_EVENTS = new HashSet<>(
            Arrays.asList(WebhookDispatcher.ALL_EVENTS));

    private SystemApi() {
    }

    static void handle(Context context, SimpleHttpServer.Request req, OutputStream out,
                       DataApi.Auth auth, List<String> parts) throws Exception {
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);
        String section = parts.size() > 1 ? parts.get(1) : "";
        if (section.isEmpty()) throw DataException.notFound("endpoint");

        if (section.equals("start") || section.equals("stop") || section.equals("restart")) {
            DataApi.requireMethod(method, "POST");
            requireAdmin(auth);
            startService(context, section);
            DataApi.sendJson(context, req, out, 200, DataApi.ok());
            return;
        }

        switch (section) {
            case "monitor":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                DataApi.sendJson(context, req, out, 200, monitor(context));
                return;
            case "diagnostics":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                if (parts.size() > 2 && "report".equals(parts.get(2))) {
                    DataApi.sendJson(context, req, out, 200, DiagnosticsCenter.collect(context, 200));
                } else {
                    DataApi.sendJson(context, req, out, 200, SystemDiagnostics.collect(context));
                }
                return;
            case "reliability":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                DataApi.sendJson(context, req, out, 200, DiagnosticsCenter.collectStatus(context).toJson());
                return;
            case "crashes":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                DataApi.sendJson(context, req, out, 200,
                        wrap("crashes", CrashDiagnosticStore.get(context).toJson(100)));
                return;
            case "security-events":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                DataApi.sendJson(context, req, out, 200,
                        wrap("events", securityEventsJson(context)));
                return;
            case "automation":
                handleAutomation(context, req, out, auth, method);
                return;
            case "notifications":
                handleNotifications(context, req, out, auth, method);
                return;
            case "tasks":
                handleTasks(context, req, out, auth, method, parts);
                return;
            case "webhooks":
                handleWebhooks(context, req, out, auth, method, parts);
                return;
            case "logs":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                DataApi.sendJson(context, req, out, 200, logs(context, req));
                return;
            case "maintenance":
                handleMaintenance(context, req, out, auth, method, parts);
                return;
            default:
                throw DataException.notFound("endpoint");
        }
    }

    private static void handleAutomation(Context context, SimpleHttpServer.Request req, OutputStream out,
                                         DataApi.Auth auth, String method) throws Exception {
        if (method.equals("GET")) {
            DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
            DataApi.sendJson(context, req, out, 200, automationJson(context));
            return;
        }
        DataApi.requireMethod(method, "PATCH");
        requireAdmin(auth);
        AutomationSettings settings = AutomationSettingsStore.get(context).get();
        applyAutomation(DataApi.readJson(req), settings);
        AutomationSettingsStore.get(context).save(settings);
        ContentStore.setStartOnBoot(context, DataApi.readJson(req).optBoolean("startOnBoot",
                ContentStore.getStartOnBoot(context)));
        ServerWatchdog.scheduleNext(context);
        DataApi.sendJson(context, req, out, 200, automationJson(context));
    }

    private static void handleNotifications(Context context, SimpleHttpServer.Request req, OutputStream out,
                                            DataApi.Auth auth, String method) throws Exception {
        if (method.equals("GET")) {
            DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
            JSONObject json = new JSONObject();
            AutomationSettings settings = AutomationSettingsStore.get(context).get();
            json.put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled());
            json.put("notifyServerFailures", settings.notifyServerFailures);
            json.put("notifyServerRecovery", settings.notifyServerRecovery);
            json.put("notifyTaskResults", settings.notifyTaskResults);
            json.put("notifyStorageWarnings", settings.notifyStorageWarnings);
            json.put("storageWarningMb", settings.storageWarningMb);
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        DataApi.requireMethod(method, "PATCH");
        requireAdmin(auth);
        JSONObject body = DataApi.readJson(req);
        AutomationSettings settings = AutomationSettingsStore.get(context).get();
        applyAutomation(body, settings);
        AutomationSettingsStore.get(context).save(settings);
        JSONObject json = DataApi.ok();
        json.put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled());
        DataApi.sendJson(context, req, out, 200, json);
    }

    private static void handleTasks(Context context, SimpleHttpServer.Request req, OutputStream out,
                                    DataApi.Auth auth, String method, List<String> parts) throws Exception {
        ScheduledTaskStore store = ScheduledTaskStore.get(context);
        if (parts.size() == 2) {
            if (method.equals("GET")) {
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                JSONArray array = new JSONArray();
                for (ScheduledTask task : store.all()) array.put(taskJson(task));
                DataApi.sendJson(context, req, out, 200, wrap("tasks", array));
                return;
            }
            DataApi.requireMethod(method, "POST");
            requireAdmin(auth);
            ScheduledTask task = new ScheduledTask();
            task.id = UUID.randomUUID().toString();
            task.createdAt = System.currentTimeMillis();
            applyTask(DataApi.readJson(req), task, context);
            validateTask(task, context);
            task.nextRunAt = task.computeNextRunAt(System.currentTimeMillis());
            store.save(task);
            ScheduledTaskScheduler.scheduleNext(context);
            DataApi.sendJson(context, req, out, 201, taskJson(store.get(task.id)));
            return;
        }

        String id = parts.get(2);
        ScheduledTask task = store.get(id);
        if (task == null) throw DataException.notFound("task");
        if (parts.size() == 4 && parts.get(3).equals("run")) {
            DataApi.requireMethod(method, "POST");
            requireAdmin(auth);
            ScheduledTaskRunner.TaskResult result = ScheduledTaskRunner.runNow(context, id);
            JSONObject json = DataApi.ok();
            json.put("success", result.success);
            json.put("retryScheduled", result.retryScheduled);
            json.put("nextRunAt", result.nextRunAt);
            if (!result.error.isEmpty()) json.put("error", result.error);
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        if (parts.size() == 4 && (parts.get(3).equals("pause") || parts.get(3).equals("resume"))) {
            DataApi.requireMethod(method, "POST");
            requireAdmin(auth);
            task.enabled = parts.get(3).equals("resume");
            if (task.enabled) task.nextRunAt = task.computeNextRunAt(System.currentTimeMillis());
            store.save(task);
            ScheduledTaskScheduler.scheduleNext(context);
            DataApi.sendJson(context, req, out, 200, taskJson(store.get(id)));
            return;
        }
        if (parts.size() == 4 && parts.get(3).equals("history")) {
            DataApi.requireMethod(method, "GET");
            DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
            JSONArray array = new JSONArray();
            for (TaskHistoryEntry entry : TaskHistoryStore.get(context).list(id, 50)) {
                array.put(taskHistoryJson(entry));
            }
            DataApi.sendJson(context, req, out, 200, wrap("history", array));
            return;
        }
        if (parts.size() != 3) throw DataException.notFound("endpoint");

        if (method.equals("GET")) {
            DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
            JSONObject json = taskJson(task);
            JSONArray history = new JSONArray();
            for (TaskHistoryEntry entry : TaskHistoryStore.get(context).list(id, 20)) {
                history.put(taskHistoryJson(entry));
            }
            json.put("history", history);
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        if (method.equals("PATCH")) {
            requireAdmin(auth);
            applyTask(DataApi.readJson(req), task, context);
            validateTask(task, context);
            task.nextRunAt = task.computeNextRunAt(System.currentTimeMillis());
            store.save(task);
            ScheduledTaskScheduler.scheduleNext(context);
            DataApi.sendJson(context, req, out, 200, taskJson(store.get(id)));
            return;
        }
        DataApi.requireMethod(method, "DELETE");
        requireAdmin(auth);
        store.delete(id);
        ScheduledTaskScheduler.scheduleNext(context);
        DataApi.sendJson(context, req, out, 200, DataApi.ok());
    }

    private static void handleWebhooks(Context context, SimpleHttpServer.Request req, OutputStream out,
                                       DataApi.Auth auth, String method, List<String> parts) throws Exception {
        WebhookStore store = WebhookStore.get(context);
        if (parts.size() == 2) {
            if (method.equals("GET")) {
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                JSONArray array = new JSONArray();
                for (WebhookConfig hook : store.all()) array.put(webhookJson(hook));
                DataApi.sendJson(context, req, out, 200, wrap("webhooks", array));
                return;
            }
            DataApi.requireMethod(method, "POST");
            requireAdmin(auth);
            WebhookConfig hook = new WebhookConfig();
            hook.id = UUID.randomUUID().toString();
            hook.createdAt = System.currentTimeMillis();
            applyWebhook(DataApi.readJson(req), hook);
            validateWebhook(hook);
            store.save(hook);
            DataApi.sendJson(context, req, out, 201, webhookJson(store.get(hook.id)));
            return;
        }

        String id = parts.get(2);
        WebhookConfig hook = store.get(id);
        if (hook == null) throw DataException.notFound("webhook");
        if (parts.size() == 4 && parts.get(3).equals("test")) {
            DataApi.requireMethod(method, "POST");
            requireAdmin(auth);
            WebhookHistoryEntry result = WebhookDispatcher.deliverNow(context, hook, "test", new JSONObject());
            JSONObject json = DataApi.ok();
            json.put("success", result != null && result.success);
            if (result != null && !result.error.isEmpty()) json.put("error", result.error);
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        if (parts.size() != 3) throw DataException.notFound("endpoint");

        if (method.equals("GET")) {
            DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
            DataApi.sendJson(context, req, out, 200, webhookJson(hook));
            return;
        }
        if (method.equals("PATCH")) {
            requireAdmin(auth);
            applyWebhook(DataApi.readJson(req), hook);
            validateWebhook(hook);
            store.save(hook);
            DataApi.sendJson(context, req, out, 200, webhookJson(store.get(id)));
            return;
        }
        DataApi.requireMethod(method, "DELETE");
        requireAdmin(auth);
        store.delete(id);
        DataApi.sendJson(context, req, out, 200, DataApi.ok());
    }

    private static void handleMaintenance(Context context, SimpleHttpServer.Request req, OutputStream out,
                                          DataApi.Auth auth, String method, List<String> parts)
            throws Exception {
        if (parts.size() == 2) {
            DataApi.requireMethod(method, "GET");
            DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
            DataApi.sendJson(context, req, out, 200, SystemMaintenance.inspect(context).toJson());
            return;
        }
        DataApi.requireMethod(method, "POST");
        requireAdmin(auth);
        String action = parts.get(2);
        if (action.equals("clean")) {
            JSONObject body = DataApi.readJson(req);
            if (!body.optBoolean("confirm", false)) {
                throw new DataException("confirm=true is required before removing temporary files", 400);
            }
            DataApi.sendJson(context, req, out, 200, SystemMaintenance.cleanTempFiles(context).toJson());
            return;
        }
        if (action.equals("verify-backups")) {
            DataApi.sendJson(context, req, out, 200, SystemMaintenance.verifyBackups(context).toJson());
            return;
        }
        throw DataException.notFound("endpoint");
    }

    private static JSONObject monitor(Context context) throws Exception {
        JSONObject diagnostics = SystemDiagnostics.collect(context);
        JSONObject json = new JSONObject();
        json.put("generatedAt", diagnostics.get("generatedAt"));
        json.put("server", diagnostics.get("server"));
        json.put("resources", diagnostics.get("resources"));
        json.put("content", diagnostics.get("content"));
        json.put("checks", diagnostics.get("checks"));
        JSONArray activity = new JSONArray();
        for (com.yadaventerprise.phoneserver.data.stats.ServerStats.ActivityEvent event
                : com.yadaventerprise.phoneserver.data.stats.ServerStats.get().getRecentActivity()) {
            JSONObject item = new JSONObject();
            item.put("time", event.timeMillis);
            item.put("type", event.type);
            item.put("message", SystemLogSanitizer.sanitize(event.message));
            activity.put(item);
        }
        json.put("activity", activity);
        return json;
    }

    private static JSONObject logs(Context context, SimpleHttpServer.Request req) throws Exception {
        SystemLogCollector.Query query = new SystemLogCollector.Query();
        query.source = req.query.get("source");
        if (query.source == null) query.source = "";
        query.level = req.query.get("level");
        if (query.level == null) query.level = "";
        query.search = req.query.get("search");
        if (query.search == null) query.search = "";
        query.limit = parseInt(req.query.get("limit"), 200);
        JSONArray array = new JSONArray();
        for (SystemLogCollector.SystemLogEntry entry
                : SystemLogCollector.collect(context, query)) array.put(entry.toJson());
        return wrap("entries", array);
    }

    private static JSONArray securityEventsJson(Context context) throws Exception {
        SecurityEventStore.Query query = new SecurityEventStore.Query();
        query.limit = 200;
        JSONArray array = new JSONArray();
        for (SecurityEvent event : SecurityEventStore.get(context).read(query)) {
            JSONObject json = event.toJson();
            json.put("ip", DiagnosticRedactor.maskIp(event.ip));
            json.put("detail", DiagnosticRedactor.sanitize(event.detail));
            json.put("path", DiagnosticRedactor.sanitize(event.path));
            array.put(json);
        }
        return array;
    }

    private static JSONObject automationJson(Context context) throws Exception {
        AutomationSettings settings = AutomationSettingsStore.get(context).get();
        JSONObject json = settings.toJson();
        json.put("startOnBoot", ContentStore.getStartOnBoot(context));
        return json;
    }

    private static void applyAutomation(JSONObject body, AutomationSettings settings) throws DataException {
        DataApi.requireBody(body);
        if (body.has("autoRestartEnabled")) settings.autoRestartEnabled = body.optBoolean("autoRestartEnabled");
        if (body.has("watchdogIntervalMinutes")) {
            settings.watchdogIntervalMinutes = boundedInt(body, "watchdogIntervalMinutes", 1, 1440);
        }
        if (body.has("notifyServerFailures")) settings.notifyServerFailures = body.optBoolean("notifyServerFailures");
        if (body.has("notifyServerRecovery")) settings.notifyServerRecovery = body.optBoolean("notifyServerRecovery");
        if (body.has("notifyTaskResults")) settings.notifyTaskResults = body.optBoolean("notifyTaskResults");
        if (body.has("notifyStorageWarnings")) {
            settings.notifyStorageWarnings = body.optBoolean("notifyStorageWarnings");
        }
        if (body.has("storageWarningMb")) {
            settings.storageWarningMb = boundedInt(body, "storageWarningMb", 10, 1024 * 1024);
        }
        if (body.has("logRetentionDays")) {
            settings.logRetentionDays = boundedInt(body, "logRetentionDays", 1, 365);
        }
    }

    private static void applyTask(JSONObject body, ScheduledTask task, Context context) throws DataException {
        DataApi.requireBody(body);
        if (body.has("name")) task.name = body.optString("name", "").trim();
        if (body.has("action")) task.action = body.optString("action", task.action).trim();
        if (body.has("projectId")) task.projectId = body.optString("projectId", "").trim();
        if (body.has("webhookId")) task.webhookId = body.optString("webhookId", "").trim();
        if (body.has("includeFiles")) task.includeFiles = body.optBoolean("includeFiles", task.includeFiles);
        if (body.has("enabled")) task.enabled = body.optBoolean("enabled", task.enabled);
        if (body.has("scheduleType")) task.scheduleType = body.optString("scheduleType", task.scheduleType).trim();
        if (body.has("intervalMinutes")) {
            task.intervalMinutes = boundedInt(body, "intervalMinutes", 1, 525600);
        }
        if (body.has("runAt")) task.runAt = body.optLong("runAt", task.runAt);
        if (body.has("maxAttempts")) task.maxAttempts = boundedInt(body, "maxAttempts", 1, 5);
        if (body.has("retryDelayMinutes")) {
            task.retryDelayMinutes = boundedInt(body, "retryDelayMinutes", 1, 1440);
        }
    }

    private static void validateTask(ScheduledTask task, Context context) throws DataException {
        if (task.name.isEmpty()) throw new DataException("task name is required", 400);
        if (!task.isBackup() && !task.isWebhook()) throw new DataException("unknown task action", 400);
        if (!ScheduledTask.SCHEDULE_ONCE.equals(task.scheduleType)
                && !ScheduledTask.SCHEDULE_RECURRING.equals(task.scheduleType)) {
            throw new DataException("scheduleType must be recurring or once", 400);
        }
        if (task.isOneTime() && task.runAt <= 0) throw new DataException("runAt is required for one-time tasks", 400);
        if (task.isBackup()) {
            try {
                ProjectStore.require(context, task.projectId);
            } catch (Exception e) {
                throw new DataException("a valid project is required for backup tasks", 400);
            }
        }
        if (task.isWebhook() && WebhookStore.get(context).get(task.webhookId) == null) {
            throw new DataException("a valid webhook is required for webhook tasks", 400);
        }
    }

    private static void applyWebhook(JSONObject body, WebhookConfig hook) throws DataException {
        DataApi.requireBody(body);
        if (body.has("name")) hook.name = body.optString("name", "").trim();
        if (body.has("url")) hook.url = body.optString("url", "").trim();
        if (body.has("enabled")) hook.enabled = body.optBoolean("enabled", hook.enabled);
        if (body.has("method")) hook.method = body.optString("method", "POST").trim().toUpperCase(Locale.US);
        if (body.has("headersJson")) hook.headersJson = body.optString("headersJson", "").trim();
        if (body.has("secret")) hook.secret = body.optString("secret", "").trim();
        if (body.has("timeoutSeconds")) hook.timeoutSeconds = boundedInt(body, "timeoutSeconds", 1, 60);
        if (body.has("maxAttempts")) hook.maxAttempts = boundedInt(body, "maxAttempts", 1, 5);
        if (body.has("events")) {
            hook.events.clear();
            JSONArray array = body.optJSONArray("events");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    String event = array.optString(i, "").trim();
                    if (!event.isEmpty()) hook.events.add(event);
                }
            }
        }
    }

    private static void validateWebhook(WebhookConfig hook) throws DataException {
        if (hook.name.isEmpty()) throw new DataException("webhook name is required", 400);
        try {
            URL url = new URL(hook.url);
            if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
                throw new DataException("webhook URL must use http or https", 400);
            }
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("invalid webhook URL", 400);
        }
        if (!SUPPORTED_METHODS.contains(hook.method)) {
            throw new DataException("unsupported webhook method", 400);
        }
        if (hook.events.isEmpty()) throw new DataException("at least one event is required", 400);
        for (String event : hook.events) {
            if (!event.equals("*") && !SUPPORTED_EVENTS.contains(event)) {
                throw new DataException("unsupported webhook event: " + event, 400);
            }
        }
        if (!hook.headersJson.isEmpty()) {
            try {
                new JSONObject(hook.headersJson);
            } catch (Exception e) {
                throw new DataException("headersJson must be a JSON object", 400);
            }
        }
    }

    private static JSONObject taskJson(ScheduledTask task) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", task.id);
        json.put("name", task.name);
        json.put("action", task.action);
        json.put("projectId", task.projectId);
        json.put("webhookId", task.webhookId);
        json.put("includeFiles", task.includeFiles);
        json.put("enabled", task.enabled);
        json.put("scheduleType", task.scheduleType);
        json.put("intervalMinutes", task.intervalMinutes);
        json.put("runAt", task.runAt);
        json.put("maxAttempts", task.maxAttempts);
        json.put("retryDelayMinutes", task.retryDelayMinutes);
        json.put("currentAttempt", task.currentAttempt);
        json.put("createdAt", task.createdAt);
        json.put("lastRunAt", task.lastRunAt);
        json.put("nextRunAt", task.nextRunAt);
        json.put("lastStatus", task.lastStatus);
        json.put("lastError", SystemLogSanitizer.sanitize(task.lastError));
        json.put("failureCount", task.failureCount);
        return json;
    }

    private static JSONObject taskHistoryJson(TaskHistoryEntry entry) throws Exception {
        JSONObject json = new JSONObject();
        json.put("time", entry.time);
        json.put("taskId", entry.taskId);
        json.put("taskName", entry.taskName);
        json.put("action", entry.action);
        json.put("success", entry.success);
        json.put("retryScheduled", entry.retryScheduled);
        json.put("attempt", entry.attempt);
        json.put("maxAttempts", entry.maxAttempts);
        json.put("durationMs", entry.durationMs);
        json.put("error", SystemLogSanitizer.sanitize(entry.error));
        return json;
    }

    private static JSONObject webhookJson(WebhookConfig hook) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", hook.id);
        json.put("name", hook.name);
        json.put("url", SystemLogSanitizer.sanitize(hook.url));
        json.put("enabled", hook.enabled);
        json.put("method", hook.method);
        json.put("hasSecret", hook.secret != null && !hook.secret.isEmpty());
        json.put("hasHeaders", hook.headersJson != null && !hook.headersJson.isEmpty());
        json.put("timeoutSeconds", hook.timeoutSeconds);
        json.put("maxAttempts", hook.maxAttempts);
        json.put("createdAt", hook.createdAt);
        json.put("lastDeliveryAt", hook.lastDeliveryAt);
        json.put("lastStatus", hook.lastStatus);
        json.put("lastError", SystemLogSanitizer.sanitize(hook.lastError));
        json.put("failureCount", hook.failureCount);
        JSONArray events = new JSONArray();
        for (String event : hook.events) events.put(event);
        json.put("events", events);
        return json;
    }

    private static void requireAdmin(DataApi.Auth auth) throws DataException {
        if (auth == null || !auth.admin) throw new DataException("administrator access required", 403);
    }

    private static void startService(Context context, String action) {
        Intent intent = new Intent(context, ServerService.class);
        if (action.equals("start")) intent.setAction(ServerService.ACTION_START);
        else if (action.equals("stop")) intent.setAction(ServerService.ACTION_STOP);
        else intent.setAction(ServerService.ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private static int boundedInt(JSONObject body, String key, int min, int max) throws DataException {
        Object value = body.opt(key);
        long parsed;
        try {
            if (value instanceof Number) parsed = ((Number) value).longValue();
            else parsed = Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            throw new DataException(key + " must be a number", 400);
        }
        if (parsed < min || parsed > max) {
            throw new DataException(key + " must be between " + min + " and " + max, 400);
        }
        return (int) parsed;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Math.max(1, Math.min(1000, Integer.parseInt(value.trim())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static JSONObject wrap(String key, Object value) throws Exception {
        JSONObject json = new JSONObject();
        json.put(key, value);
        return json;
    }

    private static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        if (path == null) return result;
        for (String part : path.split("/")) {
            if (!part.trim().isEmpty()) result.add(part);
        }
        return result;
    }
}
