package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageEntry;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageStore;
import com.yadaventerprise.phoneserver.data.developer.ExtensionRecord;
import com.yadaventerprise.phoneserver.data.developer.ExtensionStore;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.security.TokenPolicyStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryEntry;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DeveloperApi {
    private DeveloperApi() {}

    static void handle(Context context, SimpleHttpServer.Request req, OutputStream out,
                       DataApi.Auth auth, List<String> parts) throws Exception {
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);
        String section = parts.size() > 1 ? parts.get(1) : "";
        if (section.isEmpty()) {
            DataApi.requireMethod(method, "GET");
            DataApi.requirePermission(auth, TokenPolicy.DEVELOPER_READ);
            JSONObject json = DataApi.ok();
            json.put("sections", new JSONArray()
                    .put("overview").put("api-keys").put("usage").put("webhooks")
                    .put("extensions").put("sandbox").put("sdk"));
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        switch (section) {
            case "overview":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.DEVELOPER_READ);
                DataApi.sendJson(context, req, out, 200, overview(context));
                return;
            case "api-keys":
                handleApiKeys(context, req, out, auth, method, parts);
                return;
            case "usage":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.ANALYTICS_READ);
                long since = 24L * 60L * 60L * 1000L;
                String sinceParam = req.query.get("sinceHours");
                if (sinceParam != null) {
                    try { since = Math.max(1, Long.parseLong(sinceParam)) * 60L * 60L * 1000L; } catch (Exception ignored) {}
                }
                JSONObject summary = ApiUsageStore.get(context).summary(System.currentTimeMillis() - since);
                summary.put("recent", usageRecent(context));
                DataApi.sendJson(context, req, out, 200, summary);
                return;
            case "webhooks":
                handleWebhooks(context, req, out, auth, method, parts);
                return;
            case "extensions":
                handleExtensions(context, req, out, auth, method, parts);
                return;
            case "sandbox":
                handleSandbox(context, req, out, auth, method);
                return;
            case "sdk":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.DEVELOPER_READ);
                DataApi.sendJson(context, req, out, 200, sdkInfo());
                return;
            default:
                throw DataException.notFound("endpoint");
        }
    }

    private static JSONObject overview(Context context) throws Exception {
        JSONObject json = DataApi.ok();
        List<Records.ApiTokenRecord> tokens = TokenStore.list(context);
        int active = 0, revoked = 0, suspended = 0;
        for (Records.ApiTokenRecord t : tokens) {
            if (t.revoked) revoked++; else if (t.suspended) suspended++; else active++;
        }
        json.put("activeKeys", active);
        json.put("revokedKeys", revoked);
        json.put("suspendedKeys", suspended);
        json.put("webhooks", WebhookStore.get(context).all().size());
        json.put("extensions", ExtensionStore.get(context).list().size());
        json.put("usage24h", ApiUsageStore.get(context).summary(System.currentTimeMillis() - 24L * 60L * 60L * 1000L));
        return json;
    }

    private static JSONArray usageRecent(Context context) throws Exception {
        JSONArray arr = new JSONArray();
        for (ApiUsageEntry e : ApiUsageStore.get(context).list(50)) arr.put(e.toJson());
        return arr;
    }

    private static void handleApiKeys(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      DataApi.Auth auth, String method, List<String> parts) throws Exception {
        TokenPolicyStore policyStore = TokenPolicyStore.get(context);
        if (method.equals("GET")) DataApi.requirePermission(auth, TokenPolicy.TOKENS_MANAGE);
        else DataApi.requireWrite(auth, TokenPolicy.TOKENS_MANAGE);
        if (parts.size() == 2) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (Records.ApiTokenRecord t : TokenStore.list(context)) arr.put(tokenJson(t, policyStore.get(t.id, t)));
                DataApi.sendJson(context, req, out, 200, DataApi.wrap("tokens", arr));
                return;
            }
            if (method.equals("POST")) {
                JSONObject body = DataApi.readJson(req);
                String projectId = body.has("projectId") && !body.isNull("projectId") ? body.optString("projectId") : null;
                String role = body.optString("role", TokenPolicy.ROLE_VIEWER);
                if (!TokenPolicy.isValidRole(role)) throw new DataException("unknown token role", 400);
                TokenPolicy policy = TokenPolicy.preset(role);
                DataApi.applyPolicyBody(policy, body);
                long expiresAt = body.optLong("expiresAt", policy.expiresAt);
                long dailyQuota = Math.max(0, body.optLong("dailyQuota", 0));
                long monthlyQuota = Math.max(0, body.optLong("monthlyQuota", 0));
                int concurrentLimit = Math.max(0, body.optInt("concurrentLimit", 0));
                TokenStore.Created created = TokenStore.create(context, body.optString("name", null), projectId, policy.canWrite(), expiresAt, dailyQuota, monthlyQuota, concurrentLimit);
                policy.tokenId = created.record.id;
                policy.expiresAt = expiresAt;
                policyStore.save(policy);
                JSONObject json = tokenJson(created.record, policy);
                json.put("token", created.token);
                json.put("warning", "store this token now; it cannot be shown again");
                DataApi.sendJson(context, req, out, 201, json);
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        String id = parts.get(2);
        Records.ApiTokenRecord record = TokenStore.findById(context, id);
        if (record == null) throw DataException.notFound("token");
        if (parts.size() == 4 && parts.get(3).equals("regenerate")) {
            DataApi.requireMethod(method, "POST");
            TokenStore.Created created = TokenStore.regenerate(context, id);
            TokenPolicy policy = policyStore.get(id, created.record);
            JSONObject json = tokenJson(created.record, policy);
            json.put("token", created.token);
            json.put("warning", "store this token now; it cannot be shown again");
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        if (method.equals("PATCH") || method.equals("PUT")) {
            JSONObject body = DataApi.readJson(req);
            TokenPolicy policy = policyStore.get(id, record);
            if (body.has("name")) TokenStore.rename(context, id, body.optString("name", record.name));
            if (body.has("suspended")) TokenStore.setSuspended(context, id, body.optBoolean("suspended", false));
            if (body.has("revoked") && body.optBoolean("revoked", false)) TokenStore.revoke(context, id);
            if (body.has("expiresAt")) TokenStore.setExpiresAt(context, id, Math.max(0, body.optLong("expiresAt", 0)));
            if (body.has("dailyQuota") || body.has("monthlyQuota") || body.has("concurrentLimit")) {
                TokenStore.updateQuotas(context, id, Math.max(0, body.optLong("dailyQuota", record.dailyQuota)), Math.max(0, body.optLong("monthlyQuota", record.monthlyQuota)), Math.max(0, body.optInt("concurrentLimit", record.concurrentLimit)));
            }
            DataApi.applyPolicyBody(policy, body);
            if (body.has("expiresAt")) policy.expiresAt = Math.max(0, body.optLong("expiresAt", 0));
            policyStore.save(policy);
            Records.ApiTokenRecord updated = TokenStore.findById(context, id);
            DataApi.sendJson(context, req, out, 200, tokenJson(updated == null ? record : updated, policy));
            return;
        }
        if (method.equals("DELETE")) {
            TokenStore.revoke(context, id);
            DataApi.sendJson(context, req, out, 200, DataApi.ok());
            return;
        }
        if (method.equals("GET")) {
            DataApi.sendJson(context, req, out, 200, tokenJson(record, policyStore.get(id, record)));
            return;
        }
        throw new DataException("method not allowed", 405);
    }

    private static JSONObject tokenJson(Records.ApiTokenRecord token, TokenPolicy policy) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", token.id);
        json.put("name", token.name);
        json.put("projectId", token.projectId == null ? JSONObject.NULL : token.projectId);
        json.put("canWrite", policy.canWrite());
        json.put("role", policy.role);
        JSONArray permissions = new JSONArray();
        for (String permission : policy.permissions) permissions.put(permission);
        json.put("permissions", permissions);
        json.put("rateLimitPerMinute", policy.rateLimitPerMinute);
        json.put("dailyQuota", token.dailyQuota);
        json.put("monthlyQuota", token.monthlyQuota);
        json.put("concurrentLimit", token.concurrentLimit);
        json.put("expiresAt", token.expiresAt > 0 ? token.expiresAt : policy.expiresAt);
        json.put("createdAt", token.createdAt);
        json.put("lastUsedAt", token.lastUsedAt);
        json.put("lastUsedIp", token.lastUsedIp == null ? "" : token.lastUsedIp);
        json.put("keyPrefix", token.keyPrefix == null ? "" : token.keyPrefix);
        json.put("suspended", token.suspended);
        json.put("revoked", token.revoked);
        json.put("status", token.revoked ? "revoked" : (token.suspended ? "suspended" : "active"));
        return json;
    }

    private static void handleWebhooks(Context context, SimpleHttpServer.Request req, OutputStream out,
                                       DataApi.Auth auth, String method, List<String> parts) throws Exception {
        if (method.equals("GET")) DataApi.requirePermission(auth, TokenPolicy.WEBHOOKS_READ);
        else DataApi.requireWrite(auth, TokenPolicy.WEBHOOKS_WRITE);
        WebhookStore store = WebhookStore.get(context);
        if (parts.size() == 2) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (WebhookConfig h : store.all()) arr.put(safeWebhookJson(h));
                DataApi.sendJson(context, req, out, 200, DataApi.wrap("webhooks", arr));
                return;
            }
            if (method.equals("POST")) {
                WebhookConfig hook = new WebhookConfig();
                hook.id = java.util.UUID.randomUUID().toString();
                hook.createdAt = System.currentTimeMillis();
                applyWebhook(DataApi.readJson(req), hook);
                validateWebhook(hook);
                store.save(hook);
                DataApi.sendJson(context, req, out, 201, safeWebhookJson(store.get(hook.id)));
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        if (parts.size() == 3 && parts.get(2).equals("history")) {
            DataApi.requireMethod(method, "GET");
            JSONArray arr = new JSONArray();
            for (WebhookHistoryEntry e : WebhookHistoryStore.get(context).list()) arr.put(e.toJson());
            DataApi.sendJson(context, req, out, 200, DataApi.wrap("history", arr));
            return;
        }
        if (parts.size() == 3 && parts.get(2).equals("verify")) {
            DataApi.requireMethod(method, "POST");
            JSONObject body = DataApi.readJson(req);
            String id = body.optString("webhookId", "");
            WebhookConfig hook = store.get(id);
            if (hook == null) throw DataException.notFound("webhook");
            String ts = body.optString("timestamp", req.headers.get("x-phoneserver-timestamp"));
            String delivery = body.optString("deliveryId", req.headers.get("x-phoneserver-delivery-id"));
            String sig = body.optString("signature", req.headers.get("x-phoneserver-signature"));
            String payload = body.has("body") ? body.optString("body", "") : body.optString("payload", "");
            boolean ok = WebhookDispatcher.verifyIncoming(context, hook.secret, ts, delivery, payload, sig, System.currentTimeMillis());
            if (!ok) throw DataException.forbidden("invalid webhook signature, timestamp, or replayed delivery");
            DataApi.sendJson(context, req, out, 200, DataApi.ok());
            return;
        }
        String id = parts.get(2);
        WebhookConfig hook = store.get(id);
        if (hook == null) throw DataException.notFound("webhook");
        if (parts.size() == 4 && parts.get(3).equals("test")) {
            DataApi.requireMethod(method, "POST");
            JSONObject data = DataApi.readJson(req);
            WebhookHistoryEntry result = WebhookDispatcher.deliverNow(context, hook, "developer.test", data.optJSONObject("data"));
            JSONObject json = DataApi.ok();
            json.put("success", result != null && result.success);
            json.put("responseCode", result == null ? 0 : result.responseCode);
            json.put("attempts", result == null ? 0 : result.attempts);
            if (result != null && !result.error.isEmpty()) json.put("error", com.yadaventerprise.phoneserver.data.system.SystemLogSanitizer.sanitize(result.error));
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        if (parts.size() == 4 && parts.get(3).equals("history")) {
            DataApi.requireMethod(method, "GET");
            JSONArray arr = new JSONArray();
            for (WebhookHistoryEntry e : WebhookHistoryStore.get(context).list()) {
                if (id.equals(e.webhookId)) arr.put(e.toJson());
            }
            DataApi.sendJson(context, req, out, 200, DataApi.wrap("history", arr));
            return;
        }
        if (parts.size() != 3) throw DataException.notFound("endpoint");
        if (method.equals("GET")) { DataApi.sendJson(context, req, out, 200, safeWebhookJson(hook)); return; }
        if (method.equals("PATCH") || method.equals("PUT")) {
            applyWebhook(DataApi.readJson(req), hook);
            validateWebhook(hook);
            store.save(hook);
            DataApi.sendJson(context, req, out, 200, safeWebhookJson(store.get(id)));
            return;
        }
        if (method.equals("DELETE")) { store.delete(id); DataApi.sendJson(context, req, out, 200, DataApi.ok()); return; }
        throw new DataException("method not allowed", 405);
    }

    private static JSONObject safeWebhookJson(WebhookConfig hook) throws Exception {
        JSONObject json = hook.toJson();
        json.remove("secret");
        json.put("hasSecret", hook.secret != null && !hook.secret.isEmpty());
        return json;
    }

    private static void applyWebhook(JSONObject body, WebhookConfig hook) {
        if (body.has("name")) hook.name = body.optString("name", hook.name).trim();
        if (body.has("url")) hook.url = body.optString("url", hook.url).trim();
        if (body.has("method")) hook.method = body.optString("method", hook.method).trim().toUpperCase(Locale.US);
        if (body.has("secret")) hook.secret = body.optString("secret", hook.secret);
        if (body.has("headersJson")) hook.headersJson = body.optString("headersJson", hook.headersJson);
        if (body.has("enabled")) hook.enabled = body.optBoolean("enabled", hook.enabled);
        if (body.has("timeoutSeconds")) hook.timeoutSeconds = Math.max(1, Math.min(60, body.optInt("timeoutSeconds", hook.timeoutSeconds)));
        if (body.has("maxAttempts")) hook.maxAttempts = Math.max(1, Math.min(5, body.optInt("maxAttempts", hook.maxAttempts)));
        if (body.has("events")) {
            hook.events.clear();
            JSONArray arr = body.optJSONArray("events");
            if (arr != null) for (int i = 0; i < arr.length(); i++) hook.events.add(arr.optString(i, ""));
        }
    }

    private static void validateWebhook(WebhookConfig hook) throws Exception {
        if (hook.name.isEmpty()) throw new DataException("webhook name is required", 400);
        if (hook.url.isEmpty()) throw new DataException("webhook URL is required", 400);
        java.net.URL url = new java.net.URL(hook.url);
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) throw new DataException("webhook URL must use http or https", 400);
        if (!hook.method.equals("GET") && !hook.method.equals("POST") && !hook.method.equals("PUT") && !hook.method.equals("PATCH") && !hook.method.equals("DELETE"))
            throw new DataException("unsupported webhook method", 400);
        if (hook.events.isEmpty()) throw new DataException("at least one webhook event is required", 400);
    }

    private static void handleExtensions(Context context, SimpleHttpServer.Request req, OutputStream out,
                                         DataApi.Auth auth, String method, List<String> parts) throws Exception {
        if (method.equals("GET")) DataApi.requirePermission(auth, TokenPolicy.DEVELOPER_READ);
        else DataApi.requireWrite(auth, TokenPolicy.DEVELOPER_WRITE);
        ExtensionStore store = ExtensionStore.get(context);
        if (parts.size() == 2) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (ExtensionRecord r : store.list()) arr.put(r.toJson());
                DataApi.sendJson(context, req, out, 200, DataApi.wrap("extensions", arr));
                return;
            }
            if (method.equals("POST")) {
                JSONObject body = DataApi.readJson(req);
                JSONObject manifest = body.optJSONObject("manifest");
                if (manifest == null) manifest = body;
                ExtensionRecord r = store.install(manifest);
                DataApi.sendJson(context, req, out, 201, r.toJson());
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        String id = parts.get(2);
        if (parts.size() == 4) {
            String action = parts.get(3);
            if (action.equals("enable") || action.equals("disable")) {
                DataApi.requireMethod(method, "POST");
                boolean approved = DataApi.readJson(req).optBoolean("approved", false);
                ExtensionRecord r = store.setEnabled(id, action.equals("enable"), approved);
                if (r == null) throw DataException.notFound("extension");
                DataApi.sendJson(context, req, out, 200, r.toJson());
                return;
            }
        }
        if (parts.size() != 3) throw DataException.notFound("endpoint");
        if (method.equals("GET")) {
            ExtensionRecord r = store.get(id);
            if (r == null) throw DataException.notFound("extension");
            DataApi.sendJson(context, req, out, 200, r.toJson());
            return;
        }
        if (method.equals("DELETE")) {
            if (!store.remove(id)) throw DataException.notFound("extension");
            DataApi.sendJson(context, req, out, 200, DataApi.ok());
            return;
        }
        throw new DataException("method not allowed", 405);
    }

    private static void handleSandbox(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      DataApi.Auth auth, String method) throws Exception {
        if (method.equals("GET")) {
            DataApi.requirePermission(auth, TokenPolicy.DEVELOPER_READ);
            JSONObject json = DataApi.ok();
            json.put("mode", "sandbox");
            json.put("isolated", true);
            json.put("productionAccess", false);
            json.put("supported", new JSONArray().put("echo").put("validate-json").put("webhook-preview").put("error-simulation"));
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        DataApi.requireMethod(method, "POST");
        DataApi.requirePermission(auth, TokenPolicy.DEVELOPER_WRITE);
        JSONObject body = DataApi.readJson(req);
        String action = body.optString("action", "echo").trim().toLowerCase(Locale.US);
        JSONObject result = new JSONObject();
        result.put("mode", "sandbox");
        result.put("action", action);
        result.put("productionAccess", false);
        if (action.equals("echo")) {
            result.put("ok", true);
            result.put("data", body.opt("data"));
        } else if (action.equals("validate-json")) {
            String raw = body.optString("json", "");
            try { new JSONObject(raw); result.put("ok", true); result.put("type", "object"); }
            catch (Exception e1) { try { new JSONArray(raw); result.put("ok", true); result.put("type", "array"); } catch (Exception e2) { result.put("ok", false); result.put("error", "invalid JSON"); } }
        } else if (action.equals("webhook-preview")) {
            JSONObject payload = new JSONObject();
            payload.put("event", body.optString("event", "developer.test"));
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("data", body.optJSONObject("data") == null ? new JSONObject() : body.optJSONObject("data"));
            result.put("ok", true);
            result.put("payload", payload);
        } else if (action.equals("error-simulation")) {
            int status = Math.max(400, Math.min(599, body.optInt("status", 429)));
            result.put("ok", false);
            result.put("simulatedStatus", status);
            result.put("error", "simulated error response");
        } else {
            throw new DataException("unsupported sandbox action", 400);
        }
        DataApi.sendJson(context, req, out, 200, result);
    }

    private static JSONObject sdkInfo() throws Exception {
        JSONObject json = DataApi.ok();
        json.put("name", "@phoneserver/sdk");
        json.put("version", "1.0.0");
        json.put("path", "/assets/developer/phoneserver-sdk.js");
        json.put("capabilities", new JSONArray()
                .put("auth").put("files.upload").put("files.download")
                .put("sharing").put("ads.track").put("webhooks.register")
                .put("errors").put("timeout"));
        return json;
    }
}
