package com.yadaventerprise.phoneserver.data.tasks;

import org.json.JSONObject;

public class ScheduledTask {
    public static final String ACTION_BACKUP = "backup";
    public static final String ACTION_WEBHOOK = "webhook";

    public String id = "";
    public String name = "";
    public String action = ACTION_BACKUP;
    public String projectId = "";
    public String webhookId = "";
    public boolean includeFiles = true;
    public boolean enabled = true;
    public int intervalMinutes = 1440;
    public long createdAt;
    public long lastRunAt;
    public long nextRunAt;
    public String lastStatus = "";
    public String lastError = "";
    public int failureCount;

    public boolean isBackup() {
        return ACTION_BACKUP.equals(action);
    }

    public boolean isWebhook() {
        return ACTION_WEBHOOK.equals(action);
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("action", action);
        json.put("projectId", projectId);
        json.put("webhookId", webhookId);
        json.put("includeFiles", includeFiles);
        json.put("enabled", enabled);
        json.put("intervalMinutes", intervalMinutes);
        json.put("createdAt", createdAt);
        json.put("lastRunAt", lastRunAt);
        json.put("nextRunAt", nextRunAt);
        json.put("lastStatus", lastStatus);
        json.put("lastError", lastError);
        json.put("failureCount", failureCount);
        return json;
    }

    public static ScheduledTask fromJson(JSONObject json) {
        ScheduledTask task = new ScheduledTask();
        task.id = json.optString("id", "");
        task.name = json.optString("name", "");
        task.action = json.optString("action", ACTION_BACKUP);
        task.projectId = json.optString("projectId", "");
        task.webhookId = json.optString("webhookId", "");
        task.includeFiles = json.optBoolean("includeFiles", true);
        task.enabled = json.optBoolean("enabled", true);
        task.intervalMinutes = Math.max(1, json.optInt("intervalMinutes", 1440));
        task.createdAt = json.optLong("createdAt", 0);
        task.lastRunAt = json.optLong("lastRunAt", 0);
        task.nextRunAt = json.optLong("nextRunAt", 0);
        task.lastStatus = json.optString("lastStatus", "");
        task.lastError = json.optString("lastError", "");
        task.failureCount = json.optInt("failureCount", 0);
        return task;
    }

    public static ScheduledTask copy(ScheduledTask source) {
        ScheduledTask copy = new ScheduledTask();
        copy.id = source.id;
        copy.name = source.name;
        copy.action = source.action;
        copy.projectId = source.projectId;
        copy.webhookId = source.webhookId;
        copy.includeFiles = source.includeFiles;
        copy.enabled = source.enabled;
        copy.intervalMinutes = source.intervalMinutes;
        copy.createdAt = source.createdAt;
        copy.lastRunAt = source.lastRunAt;
        copy.nextRunAt = source.nextRunAt;
        copy.lastStatus = source.lastStatus;
        copy.lastError = source.lastError;
        copy.failureCount = source.failureCount;
        return copy;
    }
}
