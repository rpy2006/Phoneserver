package com.yadaventerprise.phoneserver.data.developer;

import org.json.JSONObject;

public class ApiUsageEntry {
    public long time;
    public String tokenId = "";
    public String tokenName = "";
    public String keyPrefix = "";
    public String method = "";
    public String path = "";
    public int status;
    public long durationMs;
    public long bytesIn;
    public long bytesOut;
    public boolean rateLimited;
    public String ip = "";

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("time", time);
        json.put("tokenId", tokenId);
        json.put("tokenName", tokenName);
        json.put("keyPrefix", keyPrefix);
        json.put("method", method);
        json.put("path", path);
        json.put("status", status);
        json.put("durationMs", durationMs);
        json.put("bytesIn", bytesIn);
        json.put("bytesOut", bytesOut);
        json.put("rateLimited", rateLimited);
        json.put("ip", ip);
        return json;
    }

    public static ApiUsageEntry fromJson(JSONObject json) {
        ApiUsageEntry e = new ApiUsageEntry();
        e.time = json.optLong("time", 0);
        e.tokenId = json.optString("tokenId", "");
        e.tokenName = json.optString("tokenName", "");
        e.keyPrefix = json.optString("keyPrefix", "");
        e.method = json.optString("method", "");
        e.path = json.optString("path", "");
        e.status = json.optInt("status", 0);
        e.durationMs = json.optLong("durationMs", 0);
        e.bytesIn = json.optLong("bytesIn", 0);
        e.bytesOut = json.optLong("bytesOut", 0);
        e.rateLimited = json.optBoolean("rateLimited", false);
        e.ip = json.optString("ip", "");
        return e;
    }
}
