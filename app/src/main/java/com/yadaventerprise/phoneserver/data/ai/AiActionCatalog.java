package com.yadaventerprise.phoneserver.data.ai;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.sharing.ShareLink;
import com.yadaventerprise.phoneserver.data.sharing.ShareStore;
import com.yadaventerprise.phoneserver.data.storage.BackupManager;
import com.yadaventerprise.phoneserver.data.storage.FileStore;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AiActionCatalog {
    private AiActionCatalog() {}

    public static final String SERVER_STATUS = "server.status";
    public static final String MONITORING_SUMMARY = "monitoring.summary";
    public static final String FILES_LIST = "files.list";
    public static final String FILES_CREATE_FOLDER = "files.create_folder";
    public static final String SHARING_EXPIRED = "sharing.expired";
    public static final String SHARING_REVOKE = "sharing.revoke";
    public static final String BACKUP_CREATE = "backup.create";
    public static final String BACKUP_LIST = "backup.list";
    public static final String WEBHOOKS_FAILED = "webhooks.failed";
    public static final String TASKS_LIST = "tasks.list";
    public static final String TASK_ENABLE = "tasks.enable";
    public static final String TASK_DISABLE = "tasks.disable";
    public static final String TASK_DELETE = "tasks.delete";
    public static final String ADS_ANALYTICS = "ads.analytics";
    public static final String API_USAGE = "api.usage";

    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            SERVER_STATUS, MONITORING_SUMMARY, FILES_LIST, FILES_CREATE_FOLDER,
            SHARING_EXPIRED, SHARING_REVOKE, BACKUP_CREATE, BACKUP_LIST,
            WEBHOOKS_FAILED, TASKS_LIST, TASK_ENABLE, TASK_DISABLE, TASK_DELETE,
            ADS_ANALYTICS, API_USAGE)));

    public static boolean isAllowed(String type) { return type != null && ALLOWED.contains(type); }

    public static JSONArray describe() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(d(SERVER_STATUS, "Show server status", false, TokenPolicy.SYSTEM_READ));
        arr.put(d(MONITORING_SUMMARY, "Show monitoring summary", false, TokenPolicy.ANALYTICS_READ));
        arr.put(d(FILES_LIST, "List project files", false, TokenPolicy.FILES_READ));
        arr.put(d(FILES_CREATE_FOLDER, "Create project folder", true, TokenPolicy.FILES_WRITE));
        arr.put(d(SHARING_EXPIRED, "Show expired sharing links", false, TokenPolicy.SHARING_READ));
        arr.put(d(SHARING_REVOKE, "Revoke sharing link", true, TokenPolicy.SHARING_WRITE));
        arr.put(d(BACKUP_CREATE, "Generate backup", true, TokenPolicy.BACKUPS_WRITE));
        arr.put(d(BACKUP_LIST, "List backups", false, TokenPolicy.BACKUPS_READ));
        arr.put(d(WEBHOOKS_FAILED, "Show failed webhook deliveries", false, TokenPolicy.WEBHOOKS_READ));
        arr.put(d(TASKS_LIST, "List scheduled tasks", false, TokenPolicy.TASKS_READ));
        arr.put(d(TASK_ENABLE, "Enable scheduled task", true, TokenPolicy.TASKS_WRITE));
        arr.put(d(TASK_DISABLE, "Disable scheduled task", true, TokenPolicy.TASKS_WRITE));
        arr.put(d(TASK_DELETE, "Delete scheduled task", true, TokenPolicy.TASKS_WRITE));
        arr.put(d(ADS_ANALYTICS, "Show Ad Network analytics", false, TokenPolicy.ADS_READ));
        arr.put(d(API_USAGE, "Show API usage report", false, TokenPolicy.ANALYTICS_READ));
        return arr;
    }

    private static JSONObject d(String type, String label, boolean confirm, String permission) throws Exception {
        return new JSONObject().put("type", type).put("label", label).put("confirm", confirm).put("permission", permission);
    }

    public static JSONObject propose(String question) {
        String q = question == null ? "" : question.toLowerCase();
        try {
            if (has(q, "server status", "is server running")) return d(SERVER_STATUS, "Show server status", false, TokenPolicy.SYSTEM_READ);
            if (has(q, "monitor", "insight", "unusual")) return d(MONITORING_SUMMARY, "Show monitoring summary", false, TokenPolicy.ANALYTICS_READ);
            if (has(q, "list files", "show files")) return d(FILES_LIST, "List project files", false, TokenPolicy.FILES_READ);
            if (has(q, "create folder", "new folder")) return d(FILES_CREATE_FOLDER, "Create project folder", true, TokenPolicy.FILES_WRITE);
            if (has(q, "expired sharing", "expired links")) return d(SHARING_EXPIRED, "Show expired sharing links", false, TokenPolicy.SHARING_READ);
            if (has(q, "revoke sharing", "revoke link")) return d(SHARING_REVOKE, "Revoke sharing link", true, TokenPolicy.SHARING_WRITE);
            if (has(q, "create backup", "generate backup", "run backup")) return d(BACKUP_CREATE, "Generate backup", true, TokenPolicy.BACKUPS_WRITE);
            if (has(q, "list backups", "show backups")) return d(BACKUP_LIST, "List backups", false, TokenPolicy.BACKUPS_READ);
            if (has(q, "failed webhook", "webhook failures")) return d(WEBHOOKS_FAILED, "Show failed webhook deliveries", false, TokenPolicy.WEBHOOKS_READ);
            if (has(q, "list tasks", "scheduled tasks")) return d(TASKS_LIST, "List scheduled tasks", false, TokenPolicy.TASKS_READ);
            if (has(q, "enable task")) return d(TASK_ENABLE, "Enable scheduled task", true, TokenPolicy.TASKS_WRITE);
            if (has(q, "disable task")) return d(TASK_DISABLE, "Disable scheduled task", true, TokenPolicy.TASKS_WRITE);
            if (has(q, "delete task")) return d(TASK_DELETE, "Delete scheduled task", true, TokenPolicy.TASKS_WRITE);
            if (has(q, "ad analytics", "ad network")) return d(ADS_ANALYTICS, "Show Ad Network analytics", false, TokenPolicy.ADS_READ);
            if (has(q, "api usage", "api report")) return d(API_USAGE, "Show API usage report", false, TokenPolicy.ANALYTICS_READ);
        } catch (Exception ignored) {}
        return null;
    }

    public static JSONObject validate(JSONObject action, Context context) throws Exception {
        if (action == null) throw new IllegalArgumentException("No action");
        String type = action.optString("type", "").trim();
        if (!isAllowed(type)) throw new IllegalArgumentException("Unsupported action");
        JSONObject n = new JSONObject().put("type", type).put("confirm", requiresConfirmation(type)).put("permission", requiredPermission(type)).put("label", labelFor(type));
        copyIfPresent(action, n, "projectId");
        copyIfPresent(action, n, "folderPath");
        copyIfPresent(action, n, "shareId");
        copyIfPresent(action, n, "taskId");
        if (FILES_LIST.equals(type) || FILES_CREATE_FOLDER.equals(type) || BACKUP_CREATE.equals(type) || BACKUP_LIST.equals(type)) {
            requireProject(context, n.optString("projectId", ""));
        }
        if (FILES_CREATE_FOLDER.equals(type) && n.optString("folderPath", "").trim().isEmpty()) throw new IllegalArgumentException("folderPath is required");
        if (SHARING_REVOKE.equals(type)) requireShare(context, n.optString("shareId", ""));
        if (TASK_ENABLE.equals(type) || TASK_DISABLE.equals(type) || TASK_DELETE.equals(type)) requireTask(context, n.optString("taskId", ""));
        return n;
    }

    public static String requiredPermission(String type) {
        if (BACKUP_CREATE.equals(type) || FILES_CREATE_FOLDER.equals(type)) return TokenPolicy.BACKUPS_WRITE.equals(requiredPermissionForWrite(type)) ? TokenPolicy.BACKUPS_WRITE : TokenPolicy.FILES_WRITE;
        if (BACKUP_CREATE.equals(type)) return TokenPolicy.BACKUPS_WRITE;
        if (FILES_CREATE_FOLDER.equals(type)) return TokenPolicy.FILES_WRITE;
        if (SHARING_REVOKE.equals(type)) return TokenPolicy.SHARING_WRITE;
        if (TASK_ENABLE.equals(type) || TASK_DISABLE.equals(type) || TASK_DELETE.equals(type)) return TokenPolicy.TASKS_WRITE;
        if (FILES_LIST.equals(type)) return TokenPolicy.FILES_READ;
        if (BACKUP_LIST.equals(type)) return TokenPolicy.BACKUPS_READ;
        if (SHARING_EXPIRED.equals(type)) return TokenPolicy.SHARING_READ;
        if (WEBHOOKS_FAILED.equals(type)) return TokenPolicy.WEBHOOKS_READ;
        if (TASKS_LIST.equals(type)) return TokenPolicy.TASKS_READ;
        if (ADS_ANALYTICS.equals(type)) return TokenPolicy.ADS_READ;
        if (API_USAGE.equals(type) || MONITORING_SUMMARY.equals(type)) return TokenPolicy.ANALYTICS_READ;
        if (SERVER_STATUS.equals(type)) return TokenPolicy.SYSTEM_READ;
        return TokenPolicy.AI_ACTIONS;
    }

    private static String requiredPermissionForWrite(String type) { return requiredPermission(type); }
    public static String requiredPermission(JSONObject action) { return action == null ? TokenPolicy.AI_ACTIONS : requiredPermission(action.optString("type", "")); }
    public static boolean requiresConfirmation(JSONObject action) { return action != null && requiresConfirmation(action.optString("type", "")); }
    public static boolean requiresConfirmation(String type) {
        return FILES_CREATE_FOLDER.equals(type) || SHARING_REVOKE.equals(type) || BACKUP_CREATE.equals(type)
                || TASK_ENABLE.equals(type) || TASK_DISABLE.equals(type) || TASK_DELETE.equals(type);
    }

    private static String labelFor(String type) {
        if (SERVER_STATUS.equals(type)) return "Show server status";
        if (MONITORING_SUMMARY.equals(type)) return "Show monitoring summary";
        if (FILES_LIST.equals(type)) return "List project files";
        if (FILES_CREATE_FOLDER.equals(type)) return "Create project folder";
        if (SHARING_EXPIRED.equals(type)) return "Show expired sharing links";
        if (SHARING_REVOKE.equals(type)) return "Revoke sharing link";
        if (BACKUP_CREATE.equals(type)) return "Generate backup";
        if (BACKUP_LIST.equals(type)) return "List backups";
        if (WEBHOOKS_FAILED.equals(type)) return "Show failed webhook deliveries";
        if (TASKS_LIST.equals(type)) return "List scheduled tasks";
        if (TASK_ENABLE.equals(type)) return "Enable scheduled task";
        if (TASK_DISABLE.equals(type)) return "Disable scheduled task";
        if (TASK_DELETE.equals(type)) return "Delete scheduled task";
        if (ADS_ANALYTICS.equals(type)) return "Show Ad Network analytics";
        if (API_USAGE.equals(type)) return "Show API usage report";
        return "Action";
    }

    private static void requireProject(Context context, String projectId) throws Exception {
        if (projectId == null || projectId.trim().isEmpty()) throw new IllegalArgumentException("projectId is required");
        for (Records.ProjectRecord p : ProjectStore.list(context)) if (projectId.equals(p.id)) return;
        throw new IllegalArgumentException("projectId is invalid");
    }
    private static void requireShare(Context context, String shareId) {
        if (shareId == null || shareId.trim().isEmpty()) throw new IllegalArgumentException("shareId is required");
        if (ShareStore.get(context).get(shareId) == null) throw new IllegalArgumentException("shareId is invalid");
    }
    private static void requireTask(Context context, String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) throw new IllegalArgumentException("taskId is required");
        if (ScheduledTaskStore.get(context).get(taskId) == null) throw new IllegalArgumentException("taskId is invalid");
    }
    private static void copyIfPresent(JSONObject src, JSONObject dst, String key) { String v = src.optString(key, "").trim(); if (!v.isEmpty()) try { dst.put(key, v); } catch (Exception ignored) {} }
    private static boolean has(String q, String... needles) { for (String n : needles) if (q.contains(n)) return true; return false; }
}
