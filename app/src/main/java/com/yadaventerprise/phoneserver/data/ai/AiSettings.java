package com.yadaventerprise.phoneserver.data.ai;

import org.json.JSONObject;

public class AiSettings {
    public static final String PROVIDER_DISABLED = "disabled";
    public static final String PROVIDER_OPENAI_COMPATIBLE = "openai_compatible";
    public static final String PROVIDER_LOCAL = "local";

    public boolean enabled;
    public String provider = PROVIDER_DISABLED;
    public String model = "";
    public String endpoint = "";
    public String apiKey = "";
    public boolean localInference;
    public int timeoutSeconds = 30;
    public boolean shareLogs;
    public boolean shareSecurityEvents;
    public boolean shareDiagnostics;
    public boolean saveHistory = true;
    public boolean consentExternalSharing = false;
    public long updatedAt;

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("enabled", enabled);
        json.put("provider", provider);
        json.put("model", model);
        json.put("endpoint", endpoint);
        json.put("apiKey", apiKey);
        json.put("localInference", localInference);
        json.put("timeoutSeconds", timeoutSeconds);
        json.put("shareLogs", shareLogs);
        json.put("shareSecurityEvents", shareSecurityEvents);
        json.put("shareDiagnostics", shareDiagnostics);
        json.put("saveHistory", saveHistory);
        json.put("consentExternalSharing", consentExternalSharing);
        json.put("updatedAt", updatedAt);
        return json;
    }

    public static AiSettings fromJson(JSONObject json) {
        AiSettings s = new AiSettings();
        s.enabled = json.optBoolean("enabled", false);
        s.provider = json.optString("provider", PROVIDER_DISABLED);
        s.model = json.optString("model", "");
        s.endpoint = json.optString("endpoint", "");
        s.apiKey = json.optString("apiKey", "");
        s.localInference = json.optBoolean("localInference", false);
        s.timeoutSeconds = Math.max(5, Math.min(120, json.optInt("timeoutSeconds", 30)));
        s.shareLogs = json.optBoolean("shareLogs", false);
        s.shareSecurityEvents = json.optBoolean("shareSecurityEvents", false);
        s.shareDiagnostics = json.optBoolean("shareDiagnostics", false);
        s.saveHistory = json.optBoolean("saveHistory", true);
        s.consentExternalSharing = json.optBoolean("consentExternalSharing", false);
        s.updatedAt = json.optLong("updatedAt", 0);
        if (!PROVIDER_OPENAI_COMPATIBLE.equals(s.provider) && !PROVIDER_LOCAL.equals(s.provider)) s.provider = PROVIDER_DISABLED;
        return s;
    }

    public AiSettings copy() {
        AiSettings c = new AiSettings();
        c.enabled = enabled; c.provider = provider; c.model = model; c.endpoint = endpoint; c.apiKey = apiKey;
        c.localInference = localInference; c.timeoutSeconds = timeoutSeconds; c.shareLogs = shareLogs;
        c.shareSecurityEvents = shareSecurityEvents; c.shareDiagnostics = shareDiagnostics; c.saveHistory = saveHistory; c.consentExternalSharing = consentExternalSharing; c.updatedAt = updatedAt;
        return c;
    }
}
