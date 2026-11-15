package com.yadaventerprise.phoneserver.data.sharing;

import org.json.JSONObject;

public class ShareAccessEvent {

    public static final String CREATED = "created";
    public static final String UPDATED = "updated";
    public static final String REVOKED = "revoked";
    public static final String RESTORED = "restored";
    public static final String DELETED = "deleted";
    public static final String ROTATED = "rotated";
    public static final String VISIT = "visit";
    public static final String VIEW = "view";
    public static final String DOWNLOAD = "download";
    public static final String UNLOCKED = "unlocked";
    public static final String INVALID_TOKEN = "invalid_token";
    public static final String DENIED_PASSWORD = "denied_password";
    public static final String DENIED_EXPIRED = "denied_expired";
    public static final String DENIED_REVOKED = "denied_revoked";
    public static final String DENIED_LIMIT = "denied_limit";
    public static final String DENIED_PERMISSION = "denied_permission";
    public static final String DENIED_RATE_LIMIT = "denied_rate_limit";

    public long time;
    public String linkId = "";
    public String linkName = "";
    public String event = "";
    public int status;
    public String detail = "";
    public String ip = "";
    public String path = "";
    public String userAgent = "";

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("time", time);
            json.put("linkId", linkId == null ? "" : linkId);
            json.put("linkName", linkName == null ? "" : linkName);
            json.put("event", event == null ? "" : event);
            json.put("status", status);
            json.put("detail", detail == null ? "" : detail);
            json.put("ip", ip == null ? "" : ip);
            json.put("path", path == null ? "" : path);
            json.put("userAgent", userAgent == null ? "" : userAgent);
        } catch (Exception ignored) {
        }
        return json;
    }

    public static ShareAccessEvent fromJson(JSONObject json) {
        ShareAccessEvent event = new ShareAccessEvent();
        if (json == null) return event;
        event.time = json.optLong("time", 0);
        event.linkId = json.optString("linkId", "");
        event.linkName = json.optString("linkName", "");
        event.event = json.optString("event", "");
        event.status = json.optInt("status", 0);
        event.detail = json.optString("detail", "");
        event.ip = json.optString("ip", "");
        event.path = json.optString("path", "");
        event.userAgent = json.optString("userAgent", "");
        return event;
    }
}
