package com.yadaventerprise.phoneserver.data.security;

import org.json.JSONException;
import org.json.JSONObject;

/** One completed HTTP request, recorded for the in-app access log. */
public class AccessLogEntry {
    public long time;
    public String ip = "";
    public String method = "";
    public String path = "";
    public int status;
    public String actor = "anonymous";
    public String tokenId = "";
    public String userAgent = "";
    public long bytesIn;
    public long bytesOut;
    public long durationMs;
    public boolean blocked;

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("time", time);
        json.put("ip", ip);
        json.put("method", method);
        json.put("path", path);
        json.put("status", status);
        json.put("actor", actor);
        if (tokenId != null && !tokenId.isEmpty()) json.put("tokenId", tokenId);
        if (userAgent != null && !userAgent.isEmpty()) json.put("userAgent", userAgent);
        json.put("bytesIn", bytesIn);
        json.put("bytesOut", bytesOut);
        json.put("durationMs", durationMs);
        json.put("blocked", blocked);
        return json;
    }

    public static AccessLogEntry fromJson(JSONObject json) {
        AccessLogEntry entry = new AccessLogEntry();
        entry.time = json.optLong("time", 0);
        entry.ip = json.optString("ip", "");
        entry.method = json.optString("method", "");
        entry.path = json.optString("path", "");
        entry.status = json.optInt("status", 0);
        entry.actor = json.optString("actor", "anonymous");
        entry.tokenId = json.optString("tokenId", "");
        entry.userAgent = json.optString("userAgent", "");
        entry.bytesIn = json.optLong("bytesIn", 0);
        entry.bytesOut = json.optLong("bytesOut", 0);
        entry.durationMs = json.optLong("durationMs", 0);
        entry.blocked = json.optBoolean("blocked", false);
        return entry;
    }
}
