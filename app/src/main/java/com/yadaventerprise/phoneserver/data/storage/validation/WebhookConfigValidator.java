package com.yadaventerprise.phoneserver.data.storage.validation;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WebhookConfigValidator {
    private WebhookConfigValidator() {}

    public static ImportPlan validate(JSONArray webhooks, Set<String> existingWebhookIds, String strategy) {
        ImportPlan plan = new ImportPlan("webhooks");
        if (webhooks == null) {
            plan.addIssue(ValidationIssue.error("WEBHOOKS_REQUIRED", "webhooks", "Webhooks array is required.", "Provide webhooks array."));
            return plan;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < webhooks.length(); i++) {
            JSONObject raw = webhooks.optJSONObject(i);
            String ref = "webhooks[" + i + "]";
            if (raw == null) {
                plan.addIssue(ValidationIssue.error("INVALID_RECORD", ref, "Webhook record must be object.", "Provide object."));
                plan.addRecord(new JSONObject(), ImportRecordAction.REJECT);
                continue;
            }
            boolean ok = true;
            String id = raw.optString("id", "").trim();
            String url = raw.optString("url", "").trim();
            if (id.isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".id", "Webhook id is required.", "Provide unique id.")); ok = false; }
            if (!id.isEmpty() && !seen.add(id)) { plan.addIssue(ValidationIssue.error("DUPLICATE_ID", ref + ".id", "Duplicate webhook id: " + id, "Use unique id.")); ok = false; }
            if (url.isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".url", "Webhook URL is required.", "Provide URL.")); ok = false; }
            else if (!isSafeUrl(url)) { plan.addIssue(ValidationIssue.error("UNSAFE_URL", ref + ".url", "Unsafe or invalid webhook URL.", "Use https URL for external endpoints; localhost http is allowed only for local dev.")); ok = false; }
            JSONArray events = raw.optJSONArray("events");
            if (events == null || events.length() == 0) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".events", "At least one event is required.", "Provide events array.")); ok = false; }
            else {
                for (int e = 0; e < events.length(); e++) {
                    String ev = events.optString(e, "");
                    if (ev.isEmpty()) { plan.addIssue(ValidationIssue.error("INVALID_EVENT", ref + ".events[" + e + "]", "Event must not be empty.", "Provide supported event name.")); ok = false; }
                }
            }
            if (raw.has("secret") && !(raw.opt("secret") instanceof String)) {
                plan.addIssue(ValidationIssue.error("INVALID_TYPE", ref + ".secret", "secret must be string.", "Provide string secret or omit."));
                ok = false;
            }
            ImportRecordAction actionResult = ok ? UserConfigValidator.resolveAction(strategy, existingWebhookIds.contains(id), ref, plan) : ImportRecordAction.REJECT;
            plan.addRecord(raw, actionResult);
        }
        return plan;
    }

    static boolean isSafeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if ("https".equals(scheme)) return true;
            if ("http".equals(scheme)) {
                return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "10.0.2.2".equals(host);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
