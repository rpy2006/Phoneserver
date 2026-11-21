package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.ai.AiActionAuditStore;
import com.yadaventerprise.phoneserver.data.ai.AiAssistantService;
import com.yadaventerprise.phoneserver.data.ai.AiConversationStore;
import com.yadaventerprise.phoneserver.data.ai.AiRedactor;
import com.yadaventerprise.phoneserver.data.ai.AiSettings;
import com.yadaventerprise.phoneserver.data.ai.AiSettingsStore;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import java.io.OutputStream;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AiApi {
    private AiApi() {}

    static void handle(Context context, SimpleHttpServer.Request req, OutputStream out,
                       DataApi.Auth auth, List<String> parts) throws Exception {
        String method = req.method == null ? "GET" : req.method.toUpperCase(java.util.Locale.US);
        String section = parts.size() > 1 ? parts.get(1) : "";
        if (section.isEmpty()) {
            DataApi.requireMethod(method, "GET");
            DataApi.requirePermission(auth, TokenPolicy.AI_USE);
            DataApi.sendJson(context, req, out, 200, DataApi.ok().put("sections", new JSONArray()
                    .put("settings").put("chat").put("insights").put("analytics").put("security").put("automation").put("actions").put("history").put("catalog")));
            return;
        }
        switch (section) {
            case "settings":
                handleSettings(context, req, out, auth, method);
                return;
            case "chat":
                handleChat(context, req, out, auth, method);
                return;
            case "insights":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.ANALYTICS_READ);
                DataApi.sendJson(context, req, out, 200, DataApi.ok().put("insights", AiAssistantService.insights(context)));
                return;
            case "analytics":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.ANALYTICS_READ);
                DataApi.sendJson(context, req, out, 200, DataApi.ok().put("analytics", AiAssistantService.analytics(context)));
                return;
            case "security":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.SYSTEM_READ);
                DataApi.sendJson(context, req, out, 200, DataApi.ok().put("security", AiAssistantService.securityInsights(context)));
                return;
            case "automation":
                handleAutomation(context, req, out, auth, method);
                return;
            case "actions":
                handleActions(context, req, out, auth, method);
                return;
            case "history":
                handleHistory(context, req, out, auth, method);
                return;
            case "catalog":
                DataApi.requireMethod(method, "GET");
                DataApi.requirePermission(auth, TokenPolicy.AI_USE);
                DataApi.sendJson(context, req, out, 200, DataApi.ok().put("actions", com.yadaventerprise.phoneserver.data.ai.AiActionCatalog.describe()));
                return;
            default:
                throw DataException.notFound("endpoint");
        }
    }

    private static void handleSettings(Context context, SimpleHttpServer.Request req, OutputStream out, DataApi.Auth auth, String method) throws Exception {
        if (method.equals("GET")) {
            DataApi.requirePermission(auth, TokenPolicy.AI_SETTINGS_READ);
            AiSettings s = AiSettingsStore.get(context).get();
            JSONObject json = DataApi.ok();
            json.put("enabled", s.enabled);
            json.put("provider", s.provider);
            json.put("endpoint", s.endpoint);
            json.put("model", s.model);
            json.put("timeoutSeconds", s.timeoutSeconds);
            json.put("localInference", s.localInference);
            json.put("shareLogs", s.shareLogs);
            json.put("shareSecurityEvents", s.shareSecurityEvents);
            json.put("shareDiagnostics", s.shareDiagnostics);
            json.put("historyEnabled", s.saveHistory);
            json.put("saveHistory", s.saveHistory);
            json.put("consentExternalSharing", s.consentExternalSharing);
            json.put("hasApiKey", s.apiKey != null && !s.apiKey.isEmpty());
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            DataApi.requirePermission(auth, TokenPolicy.AI_SETTINGS_WRITE);
            JSONObject body = DataApi.readJson(req);
            AiSettingsStore store = AiSettingsStore.get(context);
            AiSettings s = store.get();
            if (body.has("enabled")) s.enabled = body.optBoolean("enabled", s.enabled);
            if (body.has("provider")) s.provider = body.optString("provider", s.provider);
            if (body.has("endpoint")) s.endpoint = body.optString("endpoint", s.endpoint);
            if (body.has("model")) s.model = body.optString("model", s.model);
            if (body.has("timeoutSeconds")) s.timeoutSeconds = Math.max(5, Math.min(120, body.optInt("timeoutSeconds", s.timeoutSeconds)));
            if (body.has("localInference")) s.localInference = body.optBoolean("localInference", s.localInference);
            if (body.has("shareLogs")) s.shareLogs = body.optBoolean("shareLogs", s.shareLogs);
            if (body.has("shareSecurityEvents")) s.shareSecurityEvents = body.optBoolean("shareSecurityEvents", s.shareSecurityEvents);
            if (body.has("shareDiagnostics")) s.shareDiagnostics = body.optBoolean("shareDiagnostics", s.shareDiagnostics);
            if (body.has("historyEnabled")) s.saveHistory = body.optBoolean("historyEnabled", s.saveHistory);
            if (body.has("saveHistory")) s.saveHistory = body.optBoolean("saveHistory", s.saveHistory);
            if (body.has("consentExternalSharing")) s.consentExternalSharing = body.optBoolean("consentExternalSharing", s.consentExternalSharing);
            if (body.has("apiKey")) s.apiKey = body.optString("apiKey", s.apiKey);
            if (body.has("clearApiKey") && body.optBoolean("clearApiKey", false)) s.apiKey = "";
            store.save(s);
            DataApi.sendJson(context, req, out, 200, DataApi.ok().put("saved", true));
            return;
        }
        throw new DataException("method not allowed", 405);
    }

    private static void handleChat(Context context, SimpleHttpServer.Request req, OutputStream out, DataApi.Auth auth, String method) throws Exception {
        DataApi.requireMethod(method, "POST");
        DataApi.requirePermission(auth, TokenPolicy.AI_USE);
        JSONObject body = DataApi.readJson(req);
        String question = body.optString("question", body.optString("message", "")).trim();
        if (question.isEmpty()) throw new DataException("question is required", 400);
        boolean consentForRequest = body.optBoolean("consentForRequest", false);
        AiSettings settings = AiSettingsStore.get(context).get();
        if (settings.saveHistory) AiConversationStore.get(context).append("user", AiRedactor.redact(question));
        AiAssistantService.Result result = AiAssistantService.ask(context, question, consentForRequest);
        if (settings.saveHistory) AiConversationStore.get(context).append("assistant", AiRedactor.redact(result.answer));
        JSONObject json = DataApi.ok();
        json.put("answer", result.answer);
        json.put("externalProvider", result.externalProvider);
        json.put("requiresConsent", result.requiresConsent);
        json.put("proposedAction", result.proposedAction == null ? JSONObject.NULL : result.proposedAction);
        DataApi.sendJson(context, req, out, 200, json);
    }

    private static void handleActions(Context context, SimpleHttpServer.Request req, OutputStream out, DataApi.Auth auth, String method) throws Exception {
        if (method.equals("GET")) {
            DataApi.requirePermission(auth, TokenPolicy.AI_USE);
            JSONArray arr = new JSONArray();
            for (JSONObject e : AiActionAuditStore.get(context).list(100)) arr.put(e);
            DataApi.sendJson(context, req, out, 200, DataApi.wrap("actions", arr));
            return;
        }
        DataApi.requireMethod(method, "POST");
        DataApi.requirePermission(auth, TokenPolicy.AI_ACTIONS);
        JSONObject body = DataApi.readJson(req);
        JSONObject action = body.optJSONObject("action");
        boolean confirmed = body.optBoolean("confirmed", false);
        String actor = auth.remoteSession != null ? auth.remoteSession.subjectId : (auth.tokenId != null ? auth.tokenId : "admin");
        JSONObject normalized = com.yadaventerprise.phoneserver.data.ai.AiActionCatalog.validate(action, context);
        String perm = com.yadaventerprise.phoneserver.data.ai.AiActionCatalog.requiredPermission(normalized);
        DataApi.requirePermission(auth, perm);
        if (com.yadaventerprise.phoneserver.data.ai.AiActionCatalog.requiresConfirmation(normalized) && !confirmed) {
            throw new DataException("confirmation required", 400);
        }
        String result = AiAssistantService.executeAction(context, normalized, confirmed, actor);
        DataApi.sendJson(context, req, out, 200, DataApi.ok().put("result", result));
    }

    private static void handleAutomation(Context context, SimpleHttpServer.Request req, OutputStream out, DataApi.Auth auth, String method) throws Exception {
        DataApi.requirePermission(auth, TokenPolicy.TASKS_READ);
        if (method.equals("GET")) {
            JSONObject json = DataApi.ok();
            json.put("suggestion", AiAssistantService.automationSuggestion(context));
            JSONArray arr = new JSONArray();
            for (com.yadaventerprise.phoneserver.data.tasks.ScheduledTask t : com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore.get(context).all()) arr.put(t.toJson());
            json.put("tasks", arr);
            DataApi.sendJson(context, req, out, 200, json);
            return;
        }
        DataApi.requireMethod(method, "POST");
        DataApi.requirePermission(auth, TokenPolicy.TASKS_WRITE);
        JSONObject body = DataApi.readJson(req);
        if (!body.optBoolean("confirmed", false)) throw new DataException("confirmation required before creating automation", 400);
        com.yadaventerprise.phoneserver.data.tasks.ScheduledTask task = AiAssistantService.createAutomation(context, body);
        DataApi.sendJson(context, req, out, 201, DataApi.ok().put("task", task.toJson()));
    }

    private static void handleHistory(Context context, SimpleHttpServer.Request req, OutputStream out, DataApi.Auth auth, String method) throws Exception {
        DataApi.requirePermission(auth, TokenPolicy.AI_USE);
        if (method.equals("DELETE")) {
            AiConversationStore.get(context).clear();
            DataApi.sendJson(context, req, out, 200, DataApi.ok().put("cleared", true));
            return;
        }
        DataApi.requireMethod(method, "GET");
        JSONArray arr = new JSONArray();
        for (JSONObject e : AiConversationStore.get(context).list(100)) arr.put(e);
        DataApi.sendJson(context, req, out, 200, DataApi.wrap("history", arr));
    }
}
