package com.yadaventerprise.phoneserver.data.security;

import org.json.JSONException;
import org.json.JSONObject;

/** A security-relevant event derived from an actual request or configuration. */
public class SecurityEvent {
    public static final String TYPE_AUTH_FAILURE = "auth_failure";
    public static final String TYPE_SUSPICIOUS_PATH = "suspicious_path";
    public static final String TYPE_RATE_LIMIT = "rate_limit";
    public static final String TYPE_BLOCKED_REQUEST = "blocked_request";

    public static final String SEVERITY_LOW = "low";
    public static final String SEVERITY_MEDIUM = "medium";
    public static final String SEVERITY_HIGH = "high";

    public long time;
    public String type = TYPE_SUSPICIOUS_PATH;
    public String severity = SEVERITY_LOW;
    public String ip = "";
    public String method = "";
    public String path = "";
    public String detail = "";
    public boolean autoBlocked;
    public boolean alerted;

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("time", time);
        json.put("type", type);
        json.put("severity", severity);
        json.put("ip", ip);
        json.put("method", method);
        json.put("path", path);
        json.put("detail", detail);
        json.put("autoBlocked", autoBlocked);
        json.put("alerted", alerted);
        return json;
    }

    public static SecurityEvent fromJson(JSONObject json) {
        SecurityEvent event = new SecurityEvent();
        event.time = json.optLong("time", 0);
        event.type = json.optString("type", TYPE_SUSPICIOUS_PATH);
        event.severity = json.optString("severity", SEVERITY_LOW);
        event.ip = json.optString("ip", "");
        event.method = json.optString("method", "");
        event.path = json.optString("path", "");
        event.detail = json.optString("detail", "");
        event.autoBlocked = json.optBoolean("autoBlocked", false);
        event.alerted = json.optBoolean("alerted", false);
        return event;
    }
}
