package com.yadaventerprise.phoneserver.data.webhooks;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class WebhookConfig {
    public String id = "";
    public String name = "";
    public String url = "";
    public String secret = "";
    public boolean enabled = true;
    public final Set<String> events = new LinkedHashSet<>();
    public long createdAt;
    public long lastDeliveryAt;
    public String lastStatus = "";
    public String lastError = "";
    public int failureCount;

    public boolean matches(String event) {
        if (!enabled || event == null) return false;
        if (events.isEmpty() || events.contains("*")) return true;
        return events.contains(event);
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("url", url);
        json.put("secret", secret);
        json.put("enabled", enabled);
        JSONArray array = new JSONArray();
        for (String event : events) array.put(event);
        json.put("events", array);
        json.put("createdAt", createdAt);
        json.put("lastDeliveryAt", lastDeliveryAt);
        json.put("lastStatus", lastStatus);
        json.put("lastError", lastError);
        json.put("failureCount", failureCount);
        return json;
    }

    public static WebhookConfig fromJson(JSONObject json) {
        WebhookConfig config = new WebhookConfig();
        config.id = json.optString("id", "");
        config.name = json.optString("name", "");
        config.url = json.optString("url", "");
        config.secret = json.optString("secret", "");
        config.enabled = json.optBoolean("enabled", true);
        JSONArray array = json.optJSONArray("events");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String event = array.optString(i, "");
                if (!event.isEmpty()) config.events.add(event);
            }
        }
        config.createdAt = json.optLong("createdAt", 0);
        config.lastDeliveryAt = json.optLong("lastDeliveryAt", 0);
        config.lastStatus = json.optString("lastStatus", "");
        config.lastError = json.optString("lastError", "");
        config.failureCount = json.optInt("failureCount", 0);
        return config;
    }

    public static WebhookConfig copy(WebhookConfig source) {
        WebhookConfig copy = new WebhookConfig();
        copy.id = source.id;
        copy.name = source.name;
        copy.url = source.url;
        copy.secret = source.secret;
        copy.enabled = source.enabled;
        copy.events.addAll(source.events);
        copy.createdAt = source.createdAt;
        copy.lastDeliveryAt = source.lastDeliveryAt;
        copy.lastStatus = source.lastStatus;
        copy.lastError = source.lastError;
        copy.failureCount = source.failureCount;
        return copy;
    }
}
