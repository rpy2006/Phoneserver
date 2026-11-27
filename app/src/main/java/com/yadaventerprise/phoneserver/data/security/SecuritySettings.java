package com.yadaventerprise.phoneserver.data.security;

import org.json.JSONException;
import org.json.JSONObject;

/** User-tunable intrusion-detection and logging settings. */
public class SecuritySettings {
    public boolean autoBlockEnabled = true;
    public boolean accessLogEnabled = true;
    public int failedLoginThreshold = 5;
    public int failedLoginWindowMinutes = 10;
    public int blockDurationMinutes = 15;
    public int suspiciousPathThreshold = 3;
    public boolean authFailureAlerts = true;
    public boolean suspiciousActivityAlerts = true;
    public String alertMinimumSeverity = "medium";
    public int maxAlertsPerHour = 20;

    public SecuritySettings copy() {
        SecuritySettings copy = new SecuritySettings();
        copy.autoBlockEnabled = autoBlockEnabled;
        copy.accessLogEnabled = accessLogEnabled;
        copy.failedLoginThreshold = failedLoginThreshold;
        copy.failedLoginWindowMinutes = failedLoginWindowMinutes;
        copy.blockDurationMinutes = blockDurationMinutes;
        copy.suspiciousPathThreshold = suspiciousPathThreshold;
        copy.authFailureAlerts = authFailureAlerts;
        copy.suspiciousActivityAlerts = suspiciousActivityAlerts;
        copy.alertMinimumSeverity = alertMinimumSeverity;
        copy.maxAlertsPerHour = maxAlertsPerHour;
        return copy;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("autoBlockEnabled", autoBlockEnabled);
        json.put("accessLogEnabled", accessLogEnabled);
        json.put("failedLoginThreshold", failedLoginThreshold);
        json.put("failedLoginWindowMinutes", failedLoginWindowMinutes);
        json.put("blockDurationMinutes", blockDurationMinutes);
        json.put("suspiciousPathThreshold", suspiciousPathThreshold);
        json.put("authFailureAlerts", authFailureAlerts);
        json.put("suspiciousActivityAlerts", suspiciousActivityAlerts);
        json.put("alertMinimumSeverity", alertMinimumSeverity);
        json.put("maxAlertsPerHour", maxAlertsPerHour);
        return json;
    }

    public static SecuritySettings fromJson(JSONObject json) {
        SecuritySettings settings = new SecuritySettings();
        settings.autoBlockEnabled = json.optBoolean("autoBlockEnabled", true);
        settings.accessLogEnabled = json.optBoolean("accessLogEnabled", true);
        settings.failedLoginThreshold = clamp(json.optInt("failedLoginThreshold", 5), 2, 50);
        settings.failedLoginWindowMinutes = clamp(json.optInt("failedLoginWindowMinutes", 10), 1, 1440);
        settings.blockDurationMinutes = clamp(json.optInt("blockDurationMinutes", 15), 1, 10080);
        settings.suspiciousPathThreshold = clamp(json.optInt("suspiciousPathThreshold", 3), 1, 50);
        settings.authFailureAlerts = json.optBoolean("authFailureAlerts", true);
        settings.suspiciousActivityAlerts = json.optBoolean("suspiciousActivityAlerts", true);
        String severity = json.optString("alertMinimumSeverity", "medium").toLowerCase();
        settings.alertMinimumSeverity = severity.equals("low") || severity.equals("high") ? severity : "medium";
        settings.maxAlertsPerHour = clamp(json.optInt("maxAlertsPerHour", 20), 1, 500);
        return settings;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
