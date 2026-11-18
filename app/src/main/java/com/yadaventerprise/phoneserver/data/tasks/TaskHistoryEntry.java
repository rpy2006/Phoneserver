package com.yadaventerprise.phoneserver.data.tasks;

import org.json.JSONObject;

public class TaskHistoryEntry {
    public long time;
    public String taskId = "";
    public String taskName = "";
    public String action = "";
    public boolean success;
    public boolean retryScheduled;
    public int attempt;
    public int maxAttempts;
    public long durationMs;
    public String error = "";

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("time", time);
        json.put("taskId", taskId);
        json.put("taskName", taskName);
        json.put("action", action);
        json.put("success", success);
        json.put("retryScheduled", retryScheduled);
        json.put("attempt", attempt);
        json.put("maxAttempts", maxAttempts);
        json.put("durationMs", durationMs);
        json.put("error", error);
        return json;
    }

    public static TaskHistoryEntry fromJson(JSONObject json) {
        TaskHistoryEntry entry = new TaskHistoryEntry();
        if (json == null) return entry;
        entry.time = json.optLong("time", 0);
        entry.taskId = json.optString("taskId", "");
        entry.taskName = json.optString("taskName", "");
        entry.action = json.optString("action", "");
        entry.success = json.optBoolean("success", false);
        entry.retryScheduled = json.optBoolean("retryScheduled", false);
        entry.attempt = json.optInt("attempt", 0);
        entry.maxAttempts = json.optInt("maxAttempts", 1);
        entry.durationMs = json.optLong("durationMs", 0);
        entry.error = json.optString("error", "");
        return entry;
    }
}
