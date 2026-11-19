package com.yadaventerprise.phoneserver.data.remote;

import com.yadaventerprise.phoneserver.data.system.SystemLogSanitizer;
import org.json.JSONObject;

public class RemotePairingRequest {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_CONSUMED = "consumed";

    public String id = "";
    public String codeHash = "";
    public String requestedName = "";
    public String deviceName = "";
    public String deviceInfo = "";
    public long createdAt;
    public long expiresAt;
    public int attempts;
    public String status = STATUS_PENDING;
    public String approvedBy = "";
    public long approvedAt;
    public String deviceId = "";
    public String sessionTokenHash = "";

    public boolean isActive(long now) {
        return STATUS_PENDING.equals(status) && expiresAt > now && attempts < 8;
    }

    public RemotePairingRequest copy() {
        return fromJson(toJson());
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        RemoteJson.put(json, "id", id);
        RemoteJson.put(json, "codeHash", codeHash);
        RemoteJson.put(json, "requestedName", requestedName);
        RemoteJson.put(json, "deviceName", deviceName);
        RemoteJson.put(json, "deviceInfo", deviceInfo);
        RemoteJson.put(json, "createdAt", createdAt);
        RemoteJson.put(json, "expiresAt", expiresAt);
        RemoteJson.put(json, "attempts", attempts);
        RemoteJson.put(json, "status", status);
        RemoteJson.put(json, "approvedBy", approvedBy);
        RemoteJson.put(json, "approvedAt", approvedAt);
        RemoteJson.put(json, "deviceId", deviceId);
        RemoteJson.put(json, "sessionTokenHash", sessionTokenHash);
        return json;
    }

    public JSONObject publicJson() {
        JSONObject json = new JSONObject();
        RemoteJson.put(json, "id", id);
        RemoteJson.put(json, "requestedName", requestedName);
        RemoteJson.put(json, "deviceName", deviceName);
        RemoteJson.put(json, "deviceInfo", deviceInfo);
        RemoteJson.put(json, "createdAt", createdAt);
        RemoteJson.put(json, "expiresAt", expiresAt);
        RemoteJson.put(json, "status", status);
        RemoteJson.put(json, "approvedBy", approvedBy);
        RemoteJson.put(json, "approvedAt", approvedAt);
        RemoteJson.put(json, "deviceId", deviceId);
        return json;
    }

    public static RemotePairingRequest fromJson(JSONObject json) {
        RemotePairingRequest request = new RemotePairingRequest();
        if (json == null) return request;
        request.id = json.optString("id", "");
        request.codeHash = json.optString("codeHash", "");
        request.requestedName = json.optString("requestedName", "");
        request.deviceName = json.optString("deviceName", "");
        request.deviceInfo = json.optString("deviceInfo", "");
        request.createdAt = json.optLong("createdAt", 0);
        request.expiresAt = json.optLong("expiresAt", 0);
        request.attempts = json.optInt("attempts", 0);
        request.status = json.optString("status", STATUS_PENDING);
        request.approvedBy = json.optString("approvedBy", "");
        request.approvedAt = json.optLong("approvedAt", 0);
        request.deviceId = json.optString("deviceId", "");
        request.sessionTokenHash = json.optString("sessionTokenHash", "");
        return request;
    }

    public static String sanitize(String value) {
        return SystemLogSanitizer.sanitize(value == null ? "" : value);
    }
}
