package com.yadaventerprise.phoneserver.data.webhooks;

import org.json.JSONObject;

public class WebhookHistoryEntry {
    public String webhookId = "";
    public String webhookName = "";
    public String event = "";
    public String url = "";
    public boolean success;
    public int attempts;
    public int responseCode;
    public String error = "";
    public long createdAt;
    public long durationMs;

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("webhookId", webhookId);
        json.put("webhookName", webhookName);
        json.put("event", event);
        json.put("url", url);
        json.put("success", success);
        json.put("attempts", attempts);
        json.put("responseCode", responseCode);
        json.put("error", com.yadaventerprise.phoneserver.data.system.SystemLogSanitizer.sanitize(error));
        json.put("createdAt", createdAt);
        json.put("durationMs", durationMs);
        return json;
    }

    public static WebhookHistoryEntry fromJson(JSONObject json) {
        WebhookHistoryEntry entry = new WebhookHistoryEntry();
        entry.webhookId = json.optString("webhookId", "");
        entry.webhookName = json.optString("webhookName", "");
        entry.event = json.optString("event", "");
        entry.url = json.optString("url", "");
        entry.success = json.optBoolean("success", false);
        entry.attempts = json.optInt("attempts", 0);
        entry.responseCode = json.optInt("responseCode", 0);
        entry.error = json.optString("error", "");
        entry.createdAt = json.optLong("createdAt", 0);
        entry.durationMs = json.optLong("durationMs", 0);
        return entry;
    }
}
