package com.yadaventerprise.phoneserver.data.storage;

import org.json.JSONObject;

/** Automatic backup configuration and last-run status. */
public class BackupSchedule {
    public boolean enabled;
    public int intervalHours = 24;
    public boolean includeFiles = true;
    public int retainPerProject = 10;
    public long lastRunAt;
    public long nextRunAt;
    public int lastSuccessCount;
    public int lastFailureCount;
    public String lastResult = "";
    public String lastError = "";

    public static long computeNextRun(long lastRunAt, int intervalHours, long now) {
        int hours = Math.max(1, Math.min(intervalHours, 168));
        long interval = hours * 60L * 60L * 1000L;
        if (lastRunAt <= 0) return now + interval;
        long next = lastRunAt + interval;
        return next <= now ? now + 60_000L : next;
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("enabled", enabled);
        json.put("intervalHours", intervalHours);
        json.put("includeFiles", includeFiles);
        json.put("retainPerProject", retainPerProject);
        json.put("lastRunAt", lastRunAt);
        json.put("nextRunAt", nextRunAt);
        json.put("lastSuccessCount", lastSuccessCount);
        json.put("lastFailureCount", lastFailureCount);
        json.put("lastResult", lastResult);
        json.put("lastError", lastError);
        return json;
    }

    public static BackupSchedule fromJson(JSONObject json) {
        BackupSchedule schedule = new BackupSchedule();
        schedule.enabled = json.optBoolean("enabled", false);
        schedule.intervalHours = clamp(json.optInt("intervalHours", 24), 1, 168);
        schedule.includeFiles = json.optBoolean("includeFiles", true);
        schedule.retainPerProject = clamp(json.optInt("retainPerProject", 10), 1,
                BackupManager.MAX_BACKUPS_PER_PROJECT);
        schedule.lastRunAt = json.optLong("lastRunAt", 0);
        schedule.nextRunAt = json.optLong("nextRunAt", 0);
        schedule.lastSuccessCount = json.optInt("lastSuccessCount", 0);
        schedule.lastFailureCount = json.optInt("lastFailureCount", 0);
        schedule.lastResult = json.optString("lastResult", "");
        schedule.lastError = json.optString("lastError", "");
        return schedule;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
