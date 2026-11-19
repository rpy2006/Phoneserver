package com.yadaventerprise.phoneserver.data.remote;

import com.yadaventerprise.phoneserver.data.system.SystemLogSanitizer;
import org.json.JSONObject;

public class RemoteDevice {
    public String id = "";
    public String deviceUid = "";
    public String name = "";
    public String model = "";
    public String manufacturer = "";
    public String osVersion = "";
    public String appVersion = "";
    public String scheme = "https";
    public String host = "";
    public int port = 8443;
    public String fingerprint = "";
    public long registeredAt;
    public long lastSeenAt;
    public long lastReportAt;
    public boolean revoked;
    public boolean disconnected;
    public boolean offlineNotified;
    public boolean onlineNotified = true;
    public String sessionId = "";
    public long lastUptimeMillis = -1;
    public long storageTotalBytes = -1;
    public long storageFreeBytes = -1;
    public int activeConnections = -1;
    public String healthStatus = "";

    public boolean isOnline(long now, long thresholdMillis) {
        return !revoked && !disconnected && lastSeenAt > 0 && now - lastSeenAt <= thresholdMillis;
    }

    public boolean hasReport() {
        return lastReportAt > 0;
    }

    public boolean isReportStale(long now, long thresholdMillis) {
        return lastReportAt <= 0 || now - lastReportAt > thresholdMillis;
    }

    public void applyHeartbeat(JSONObject report) {
        if (report == null) return;
        lastReportAt = System.currentTimeMillis();
        lastSeenAt = lastReportAt;
        if (report.has("appVersion")) appVersion = sanitize(report.optString("appVersion", ""));
        if (report.has("model")) model = sanitize(report.optString("model", ""));
        if (report.has("manufacturer")) manufacturer = sanitize(report.optString("manufacturer", ""));
        if (report.has("osVersion")) osVersion = sanitize(report.optString("osVersion", ""));
        if (report.has("uptimeMillis")) lastUptimeMillis = Math.max(0, report.optLong("uptimeMillis", -1));
        if (report.has("storageTotalBytes")) storageTotalBytes = Math.max(0, report.optLong("storageTotalBytes", -1));
        if (report.has("storageFreeBytes")) storageFreeBytes = Math.max(0, report.optLong("storageFreeBytes", -1));
        if (report.has("activeConnections")) activeConnections = Math.max(0, report.optInt("activeConnections", -1));
        if (report.has("healthStatus")) healthStatus = sanitize(report.optString("healthStatus", ""));
    }

    public RemoteDevice copy() {
        return fromJson(toJson());
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        RemoteJson.put(json, "id", id);
        RemoteJson.put(json, "deviceUid", deviceUid);
        RemoteJson.put(json, "name", name);
        RemoteJson.put(json, "model", model);
        RemoteJson.put(json, "manufacturer", manufacturer);
        RemoteJson.put(json, "osVersion", osVersion);
        RemoteJson.put(json, "appVersion", appVersion);
        RemoteJson.put(json, "scheme", "https".equals(scheme) ? "https" : "http");
        RemoteJson.put(json, "host", host);
        RemoteJson.put(json, "port", port);
        RemoteJson.put(json, "fingerprint", fingerprint);
        RemoteJson.put(json, "registeredAt", registeredAt);
        RemoteJson.put(json, "lastSeenAt", lastSeenAt);
        RemoteJson.put(json, "lastReportAt", lastReportAt);
        RemoteJson.put(json, "revoked", revoked);
        RemoteJson.put(json, "disconnected", disconnected);
        RemoteJson.put(json, "offlineNotified", offlineNotified);
        RemoteJson.put(json, "onlineNotified", onlineNotified);
        RemoteJson.put(json, "sessionId", sessionId);
        RemoteJson.put(json, "lastUptimeMillis", lastUptimeMillis);
        RemoteJson.put(json, "storageTotalBytes", storageTotalBytes);
        RemoteJson.put(json, "storageFreeBytes", storageFreeBytes);
        RemoteJson.put(json, "activeConnections", activeConnections);
        RemoteJson.put(json, "healthStatus", healthStatus);
        return json;
    }

    public static RemoteDevice fromJson(JSONObject json) {
        RemoteDevice device = new RemoteDevice();
        if (json == null) return device;
        device.id = json.optString("id", "");
        device.deviceUid = json.optString("deviceUid", "");
        device.name = json.optString("name", "");
        device.model = json.optString("model", "");
        device.manufacturer = json.optString("manufacturer", "");
        device.osVersion = json.optString("osVersion", "");
        device.appVersion = json.optString("appVersion", "");
        device.scheme = "http".equals(json.optString("scheme", "https")) ? "http" : "https";
        device.host = json.optString("host", "");
        device.port = json.optInt("port", 8443);
        device.fingerprint = json.optString("fingerprint", "");
        device.registeredAt = json.optLong("registeredAt", 0);
        device.lastSeenAt = json.optLong("lastSeenAt", 0);
        device.lastReportAt = json.optLong("lastReportAt", 0);
        device.revoked = json.optBoolean("revoked", false);
        device.disconnected = json.optBoolean("disconnected", false);
        device.offlineNotified = json.optBoolean("offlineNotified", false);
        device.onlineNotified = json.optBoolean("onlineNotified", true);
        device.sessionId = json.optString("sessionId", "");
        device.lastUptimeMillis = json.optLong("lastUptimeMillis", -1);
        device.storageTotalBytes = json.optLong("storageTotalBytes", -1);
        device.storageFreeBytes = json.optLong("storageFreeBytes", -1);
        device.activeConnections = json.optInt("activeConnections", -1);
        device.healthStatus = json.optString("healthStatus", "");
        return device;
    }

    public static String friendlyName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (name.length() > 60) name = name.substring(0, 60);
        return name.isEmpty() ? "PhoneServer " + System.currentTimeMillis() : name;
    }

    private static String sanitize(String value) {
        return SystemLogSanitizer.sanitize(value == null ? "" : value);
    }
}
