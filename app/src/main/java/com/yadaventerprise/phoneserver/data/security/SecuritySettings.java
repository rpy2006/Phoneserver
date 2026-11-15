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

    public SecuritySettings copy() {
        SecuritySettings copy = new SecuritySettings();
        copy.autoBlockEnabled = autoBlockEnabled;
        copy.accessLogEnabled = accessLogEnabled;
        copy.failedLoginThreshold = failedLoginThreshold;
        copy.failedLoginWindowMinutes = failedLoginWindowMinutes;
        copy.blockDurationMinutes = blockDurationMinutes;
        copy.suspiciousPathThreshold = suspiciousPathThreshold;
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
        return settings;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
