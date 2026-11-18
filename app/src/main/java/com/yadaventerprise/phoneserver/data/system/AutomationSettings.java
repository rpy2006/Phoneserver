package com.yadaventerprise.phoneserver.data.system;

import org.json.JSONObject;

public class AutomationSettings {
    public boolean autoRestartEnabled = true;
    public int watchdogIntervalMinutes = 5;
    public boolean notifyServerFailures = true;
    public boolean notifyServerRecovery = true;
    public boolean notifyTaskResults = true;
    public boolean notifyStorageWarnings = true;
    public int storageWarningMb = 500;
    public int logRetentionDays = 30;
    public boolean serverFailureActive;

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("autoRestartEnabled", autoRestartEnabled);
        json.put("watchdogIntervalMinutes", watchdogIntervalMinutes);
        json.put("notifyServerFailures", notifyServerFailures);
        json.put("notifyServerRecovery", notifyServerRecovery);
        json.put("notifyTaskResults", notifyTaskResults);
        json.put("notifyStorageWarnings", notifyStorageWarnings);
        json.put("storageWarningMb", storageWarningMb);
        json.put("logRetentionDays", logRetentionDays);
        json.put("serverFailureActive", serverFailureActive);
        return json;
    }

    public static AutomationSettings fromJson(JSONObject json) {
        AutomationSettings settings = new AutomationSettings();
        if (json == null) return settings;
        settings.autoRestartEnabled = json.optBoolean("autoRestartEnabled", true);
        settings.watchdogIntervalMinutes = Math.max(1, json.optInt("watchdogIntervalMinutes", 5));
        settings.notifyServerFailures = json.optBoolean("notifyServerFailures", true);
        settings.notifyServerRecovery = json.optBoolean("notifyServerRecovery", true);
        settings.notifyTaskResults = json.optBoolean("notifyTaskResults", true);
        settings.notifyStorageWarnings = json.optBoolean("notifyStorageWarnings", true);
        settings.storageWarningMb = Math.max(10, json.optInt("storageWarningMb", 500));
        settings.logRetentionDays = Math.max(1, json.optInt("logRetentionDays", 30));
        settings.serverFailureActive = json.optBoolean("serverFailureActive", false);
        return settings;
    }

    public static AutomationSettings copy(AutomationSettings source) {
        return fromJson(source == null ? null : toJsonOrNull(source));
    }

    private static JSONObject toJsonOrNull(AutomationSettings source) {
        try {
            return source.toJson();
        } catch (Exception e) {
            return null;
        }
    }
}
