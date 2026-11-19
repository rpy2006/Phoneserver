package com.yadaventerprise.phoneserver.data.remote;

import org.json.JSONObject;

public class RemoteSettings {
    public boolean enabled;
    public boolean requireHttps = true;
    public boolean allowRemoteFiles;
    public boolean allowServerControl;
    public boolean allowPairing = true;
    public int sessionTtlMinutes = 720;
    public int deviceSessionTtlDays = 7;
    public int pairingTtlMinutes = 10;
    public int offlineThresholdSeconds = 180;
    public int maxFailedLoginAttempts = 5;
    public int lockoutMinutes = 15;

    public RemoteSettings copy() {
        return fromJson(toJson());
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        RemoteJson.put(json, "enabled", enabled);
        RemoteJson.put(json, "requireHttps", requireHttps);
        RemoteJson.put(json, "allowRemoteFiles", allowRemoteFiles);
        RemoteJson.put(json, "allowServerControl", allowServerControl);
        RemoteJson.put(json, "allowPairing", allowPairing);
        RemoteJson.put(json, "sessionTtlMinutes", sessionTtlMinutes);
        RemoteJson.put(json, "deviceSessionTtlDays", deviceSessionTtlDays);
        RemoteJson.put(json, "pairingTtlMinutes", pairingTtlMinutes);
        RemoteJson.put(json, "offlineThresholdSeconds", offlineThresholdSeconds);
        RemoteJson.put(json, "maxFailedLoginAttempts", maxFailedLoginAttempts);
        RemoteJson.put(json, "lockoutMinutes", lockoutMinutes);
        return json;
    }

    public static RemoteSettings fromJson(JSONObject json) {
        RemoteSettings settings = new RemoteSettings();
        if (json == null) return settings;
        settings.enabled = json.optBoolean("enabled", false);
        settings.requireHttps = json.optBoolean("requireHttps", true);
        settings.allowRemoteFiles = json.optBoolean("allowRemoteFiles", false);
        settings.allowServerControl = json.optBoolean("allowServerControl", false);
        settings.allowPairing = json.optBoolean("allowPairing", true);
        settings.sessionTtlMinutes = clamp(json.optInt("sessionTtlMinutes", 720), 15, 1440, 720);
        settings.deviceSessionTtlDays = clamp(json.optInt("deviceSessionTtlDays", 7), 1, 30, 7);
        settings.pairingTtlMinutes = clamp(json.optInt("pairingTtlMinutes", 10), 2, 60, 10);
        settings.offlineThresholdSeconds = clamp(json.optInt("offlineThresholdSeconds", 180), 30, 86400, 180);
        settings.maxFailedLoginAttempts = clamp(json.optInt("maxFailedLoginAttempts", 5), 3, 20, 5);
        settings.lockoutMinutes = clamp(json.optInt("lockoutMinutes", 15), 1, 240, 15);
        return settings;
    }

    public void apply(JSONObject json) {
        if (json == null) return;
        if (json.has("enabled")) enabled = json.optBoolean("enabled", enabled);
        if (json.has("requireHttps")) requireHttps = json.optBoolean("requireHttps", requireHttps);
        if (json.has("allowRemoteFiles")) allowRemoteFiles = json.optBoolean("allowRemoteFiles", allowRemoteFiles);
        if (json.has("allowServerControl")) allowServerControl = json.optBoolean("allowServerControl", allowServerControl);
        if (json.has("allowPairing")) allowPairing = json.optBoolean("allowPairing", allowPairing);
        if (json.has("sessionTtlMinutes")) {
            sessionTtlMinutes = clamp(json.optInt("sessionTtlMinutes", sessionTtlMinutes), 15, 1440, sessionTtlMinutes);
        }
        if (json.has("deviceSessionTtlDays")) {
            deviceSessionTtlDays = clamp(json.optInt("deviceSessionTtlDays", deviceSessionTtlDays), 1, 30, deviceSessionTtlDays);
        }
        if (json.has("pairingTtlMinutes")) {
            pairingTtlMinutes = clamp(json.optInt("pairingTtlMinutes", pairingTtlMinutes), 2, 60, pairingTtlMinutes);
        }
        if (json.has("offlineThresholdSeconds")) {
            offlineThresholdSeconds = clamp(json.optInt("offlineThresholdSeconds", offlineThresholdSeconds),
                    30, 86400, offlineThresholdSeconds);
        }
        if (json.has("maxFailedLoginAttempts")) {
            maxFailedLoginAttempts = clamp(json.optInt("maxFailedLoginAttempts", maxFailedLoginAttempts),
                    3, 20, maxFailedLoginAttempts);
        }
        if (json.has("lockoutMinutes")) {
            lockoutMinutes = clamp(json.optInt("lockoutMinutes", lockoutMinutes), 1, 240, lockoutMinutes);
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) return fallback;
        return value;
    }
}
