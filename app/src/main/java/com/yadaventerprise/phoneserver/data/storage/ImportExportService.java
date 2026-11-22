package com.yadaventerprise.phoneserver.data.storage;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.database.SystemDatabase;
import com.yadaventerprise.phoneserver.data.remote.RemoteUser;
import com.yadaventerprise.phoneserver.data.remote.RemoteUserStore;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.security.TokenPolicyStore;
import com.yadaventerprise.phoneserver.data.storage.validation.AdNetworkConfigValidator;
import com.yadaventerprise.phoneserver.data.storage.validation.ImportPlan;
import com.yadaventerprise.phoneserver.data.storage.validation.ImportPreview;
import com.yadaventerprise.phoneserver.data.storage.validation.ImportRecordAction;
import com.yadaventerprise.phoneserver.data.storage.validation.JsonConfigValidator;
import com.yadaventerprise.phoneserver.data.storage.validation.PermissionConfigValidator;
import com.yadaventerprise.phoneserver.data.storage.validation.ScheduledTaskConfigValidator;
import com.yadaventerprise.phoneserver.data.storage.validation.UserConfigValidator;
import com.yadaventerprise.phoneserver.data.storage.validation.ValidationIssue;
import com.yadaventerprise.phoneserver.data.storage.validation.ValidationResult;
import com.yadaventerprise.phoneserver.data.storage.validation.WebhookConfigValidator;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import com.yadaventerprise.phoneserver.network.http.DataException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ImportExportService {
    private ImportExportService() {}

    public static ImportPreview previewConfigImport(Context context, String projectId, JSONObject body) throws DataException {
        ProjectStore.require(context, projectId);
        ImportPreview preview = new ImportPreview();
        preview.packageFormat = body == null ? "" : body.optString("format", "phoneserver-config-import");
        preview.packageVersion = String.valueOf(body == null ? 0 : body.optInt("schemaVersion", 0));
        if (body == null) {
            preview.packageValidation.error("BODY_REQUIRED", "body", "Import body is required.", "Provide JSON body.");
            preview.finalizeStatus();
            return preview;
        }
        String strategy = normalizeStrategy(body.optString("strategy", "merge"));
        validateStrategy(strategy, preview.packageValidation);

        if (body.has("appConfig")) {
            JSONObject cfg = body.optJSONObject("appConfig");
            ValidationResult vr = JsonConfigValidator.validate(cfg, "app_config");
            ImportPlan plan = new ImportPlan("app_config");
            plan.issues.addAll(vr.getIssues());
            if (vr.isValid()) plan.addRecord(cfg, ImportRecordAction.UPDATE);
            else plan.addRecord(cfg == null ? new JSONObject() : cfg, ImportRecordAction.REJECT);
            preview.addPlan(plan);
        }
        if (body.has("users")) {
            JSONArray users = body.optJSONArray("users");
            Set<String> existingIds = new HashSet<>();
            Set<String> existingNames = new HashSet<>();
            for (RemoteUser u : RemoteUserStore.get(context).all()) { existingIds.add(u.id); existingNames.add(RemoteUser.normalizeUsername(u.username)); }
            preview.addPlan(UserConfigValidator.validate(users, existingIds, existingNames, strategy));
        }
        if (body.has("permissions")) {
            JSONArray perms = body.optJSONArray("permissions");
            Set<String> userIds = new HashSet<>();
            for (RemoteUser u : RemoteUserStore.get(context).all()) userIds.add(u.id);
            if (body.optJSONArray("users") != null) {
                JSONArray users = body.optJSONArray("users");
                for (int i = 0; i < users.length(); i++) {
                    JSONObject u = users.optJSONObject(i);
                    if (u != null && !u.optString("id", "").trim().isEmpty()) userIds.add(u.optString("id").trim());
                }
            }
            Set<String> existingPolicyIds = new HashSet<>();
            for (Records.ApiTokenRecord t : TokenStore.list(context)) existingPolicyIds.add(t.id);
            preview.addPlan(PermissionConfigValidator.validate(perms, userIds, existingPolicyIds, strategy));
        }
        if (body.has("ad_network")) {
            JSONObject ad = body.optJSONObject("ad_network");
            ValidationResult vr = AdNetworkConfigValidator.validate(ad);
            ImportPlan plan = new ImportPlan("ad_network");
            plan.issues.addAll(vr.getIssues());
            if (ad != null) {
                JSONObject existing = com.yadaventerprise.phoneserver.ads.AdNetworkManager.exportConfig(context);
                planAdTable(plan, ad.optJSONArray("users"), existing.optJSONArray("users"), strategy);
                planAdTable(plan, ad.optJSONArray("sites"), existing.optJSONArray("sites"), strategy);
                planAdTable(plan, ad.optJSONArray("placements"), existing.optJSONArray("placements"), strategy);
                planAdTable(plan, ad.optJSONArray("campaigns"), existing.optJSONArray("campaigns"), strategy);
                planAdTable(plan, ad.optJSONArray("creatives"), existing.optJSONArray("creatives"), strategy);
            }
            if (plan.records.isEmpty()) {
                plan.addRecord(ad == null ? new JSONObject() : ad, vr.isValid() ? ImportRecordAction.UPDATE : ImportRecordAction.REJECT);
            }
            preview.addPlan(plan);
        }
        if (body.has("scheduled_tasks")) {
            Set<String> ids = new HashSet<>();
            for (ScheduledTask t : ScheduledTaskStore.get(context).all()) ids.add(t.id);
            preview.addPlan(ScheduledTaskConfigValidator.validate(body.optJSONArray("scheduled_tasks"), ids, strategy));
        }
        if (body.has("webhooks")) {
            Set<String> ids = new HashSet<>();
            for (WebhookConfig w : WebhookStore.get(context).all()) ids.add(w.id);
            preview.addPlan(WebhookConfigValidator.validate(body.optJSONArray("webhooks"), ids, strategy));
        }
        if (preview.categories.isEmpty()) {
            preview.packageValidation.error("NO_CATEGORIES", "body", "No supported import categories found.", "Include at least one supported category.");
        }
        preview.finalizeStatus();
        return preview;
    }

    public static JSONObject applyConfigImport(Context context, String projectId, JSONObject body) throws DataException {
        ProjectStore.require(context, projectId);
        if (body == null) throw new DataException("Import body is required.", 400);
        String strategy = normalizeStrategy(body.optString("strategy", "merge"));
        ImportPreview preview = previewConfigImport(context, projectId, body);
        if (!preview.valid) {
            JSONObject out = preview.toJson();
            try { out.put("applied", false); out.put("error", "Import validation failed"); } catch (Exception ignored) {}
            throw new DataException(out.toString(), 422);
        }

        StorageLayout layout = ContentStoreBridge.layout(context);
        File stagingRoot = new File(StorageLayout.ensure(layout.tmpDir()), "cfg-import-" + StorageLayout.generateId());
        File recoveryRoot = new File(StorageLayout.ensure(layout.tmpDir()), "cfg-recovery-" + StorageLayout.generateId());
        StorageLayout.ensure(stagingRoot);
        StorageLayout.ensure(recoveryRoot);
        List<Runnable> rollbackActions = new ArrayList<>();
        JSONObject result = new JSONObject();
        try {
            writeJson(new File(stagingRoot, "import-body.json"), body);
            writeJson(new File(stagingRoot, "preview.json"), preview.toJson());
            createRecoveryPoint(context, layout, recoveryRoot);

            if (body.has("appConfig")) {
                applyAppConfig(layout, body.optJSONObject("appConfig"), recoveryRoot, rollbackActions);
            }
            if (body.has("users")) {
                applyUsers(context, body.optJSONArray("users"), strategy, recoveryRoot, rollbackActions);
            }
            if (body.has("permissions")) {
                applyPermissions(context, body.optJSONArray("permissions"), strategy, recoveryRoot, rollbackActions);
            }
            if (body.has("scheduled_tasks")) {
                applyScheduledTasks(context, body.optJSONArray("scheduled_tasks"), strategy, recoveryRoot, rollbackActions);
            }
            if (body.has("webhooks")) {
                applyWebhooks(context, body.optJSONArray("webhooks"), strategy, recoveryRoot, rollbackActions);
            }
            if (body.has("ad_network")) {
                applyAdNetwork(context, body.optJSONObject("ad_network"), strategy, recoveryRoot, rollbackActions);
            }

            verifyApplied(context, body, strategy);

            result.put("applied", true);
            result.put("strategy", strategy);
            result.put("preview", preview.toJson());
            result.put("recoveryPath", recoveryRoot.getAbsolutePath());
            deleteRecursively(stagingRoot);
            deleteRecursively(recoveryRoot);
            ProjectStore.logOperation(context, projectId, "config.import", strategy);
            return result;
        } catch (Exception e) {
            for (int i = rollbackActions.size() - 1; i >= 0; i--) {
                try { rollbackActions.get(i).run(); } catch (Exception ignored) {}
            }
            try { restoreRecoveryPoint(context, layout, recoveryRoot); } catch (Exception ignored) {}
            deleteRecursively(stagingRoot);
            if (e instanceof DataException) throw (DataException) e;
            throw new DataException("Configuration import failed: " + safeMessage(e), 500);
        }
    }

    public static JSONObject exportConfig(Context context, String projectId, JSONArray categories) throws DataException {
        ProjectStore.require(context, projectId);
        Set<String> requested = new HashSet<>();
        if (categories != null) for (int i = 0; i < categories.length(); i++) requested.add(categories.optString(i, ""));
        boolean all = requested.isEmpty();
        JSONObject out = new JSONObject();
        try {
            out.put("format", "phoneserver-config-export");
            out.put("schemaVersion", 1);
            out.put("projectId", projectId);
            out.put("exportedAt", System.currentTimeMillis());
            if (all || requested.contains("app_config")) {
                out.put("appConfig", readOrDefaultAppConfig(layoutFor(context)));
            }
            if (all || requested.contains("users")) {
                JSONArray arr = new JSONArray();
                for (RemoteUser u : RemoteUserStore.get(context).all()) {
                    JSONObject j = u.toJson();
                    j.remove("passwordHash");
                    arr.put(j);
                }
                out.put("users", arr);
            }
            if (all || requested.contains("permissions")) {
                JSONArray arr = new JSONArray();
                TokenPolicyStore store = TokenPolicyStore.get(context);
                for (Records.ApiTokenRecord t : TokenStore.list(context)) arr.put(store.get(t.id, t).toJson());
                out.put("permissions", arr);
            }
            if (all || requested.contains("ad_network")) {
                out.put("ad_network", exportAdNetwork(context));
            }
            if (all || requested.contains("scheduled_tasks")) {
                JSONArray arr = new JSONArray();
                for (ScheduledTask t : ScheduledTaskStore.get(context).all()) arr.put(t.toJson());
                out.put("scheduled_tasks", arr);
            }
            if (all || requested.contains("webhooks")) {
                JSONArray arr = new JSONArray();
                for (WebhookConfig w : WebhookStore.get(context).all()) {
                    JSONObject j = w.toJson();
                    j.remove("secret");
                    j.put("hasSecret", w.secret != null && !w.secret.isEmpty());
                    arr.put(j);
                }
                out.put("webhooks", arr);
            }
        } catch (Exception e) {
            throw new DataException("could not export configuration", 500);
        }
        return out;
    }

    private static void applyUsers(Context context, JSONArray users, String strategy, File recoveryRoot, List<Runnable> rollback) throws Exception {
        RemoteUserStore store = RemoteUserStore.get(context);
        File file = new File(recoveryRoot, "users.json");
        backupJsonFile(context, "users.json", file);
        rollback.add(() -> restoreJsonFile(context, "users.json", file));

        Map<String, RemoteUser> existingById = new LinkedHashMap<>();
        Map<String, RemoteUser> existingByName = new LinkedHashMap<>();
        for (RemoteUser u : store.all()) { existingById.put(u.id, u); existingByName.put(RemoteUser.normalizeUsername(u.username), u); }

        Map<String, RemoteUser> result = new LinkedHashMap<>();
        if ("merge".equals(strategy) || "create_only".equals(strategy) || "update_only".equals(strategy)) {
            for (RemoteUser u : existingById.values()) result.put(u.id, u.copy());
        }
        for (int i = 0; i < users.length(); i++) {
            JSONObject obj = users.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("id", "").trim();
            String username = RemoteUser.normalizeUsername(obj.optString("username", ""));
            RemoteUser existing = id.isEmpty() ? null : existingById.get(id);
            if (existing == null) existing = existingByName.get(username);
            ImportRecordAction action = resolveAction(strategy, existing != null);
            if (action == ImportRecordAction.SKIP || action == ImportRecordAction.REJECT) continue;
            RemoteUser target = existing == null ? RemoteUser.fromJson(obj) : mergeUser(existing, obj, "merge".equals(strategy));
            if (existing == null) {
                if (target.id == null || target.id.trim().isEmpty()) target.id = id;
                if (target.passwordHash == null || target.passwordHash.isEmpty()) {
                    target.passwordHash = obj.optString("passwordHash", "");
                }
            } else {
                target.id = existing.id;
                if ((target.passwordHash == null || target.passwordHash.isEmpty()) && existing.passwordHash != null) {
                    target.passwordHash = existing.passwordHash;
                }
            }
            if (target.username != null) target.username = RemoteUser.normalizeUsername(target.username);
            result.put(target.id, target);
        }
        replaceUsers(context, result);
    }

    private static void applyPermissions(Context context, JSONArray perms, String strategy, File recoveryRoot, List<Runnable> rollback) throws Exception {
        TokenPolicyStore store = TokenPolicyStore.get(context);
        File file = new File(recoveryRoot, "token-policies.json");
        backupSecurityFile(context, "token-policies.json", file);
        rollback.add(() -> restoreSecurityFile(context, "token-policies.json", file));

        Map<String, TokenPolicy> existing = store.all();
        Set<String> existingIds = existing.keySet();
        Map<String, TokenPolicy> result = new LinkedHashMap<>();
        if (!"replace".equals(strategy)) for (Map.Entry<String, TokenPolicy> e : existing.entrySet()) result.put(e.getKey(), copyPolicy(e.getValue()));
        for (int i = 0; i < perms.length(); i++) {
            JSONObject obj = perms.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("tokenId", obj.optString("id", "")).trim();
            boolean exists = existingIds.contains(id);
            ImportRecordAction action = resolveAction(strategy, exists);
            if (action == ImportRecordAction.SKIP || action == ImportRecordAction.REJECT) continue;
            TokenPolicy target = TokenPolicy.fromJson(obj);
            target.tokenId = id;
            if ("merge".equals(strategy) && exists) {
                TokenPolicy prev = existing.get(id);
                if (!obj.has("rateLimitPerMinute")) target.rateLimitPerMinute = prev.rateLimitPerMinute;
                if (!obj.has("expiresAt")) target.expiresAt = prev.expiresAt;
            }
            result.put(id, target);
        }
        replacePolicies(context, result);
    }

    private static void applyScheduledTasks(Context context, JSONArray tasks, String strategy, File recoveryRoot, List<Runnable> rollback) throws Exception {
        ScheduledTaskStore store = ScheduledTaskStore.get(context);
        File file = new File(recoveryRoot, "scheduled-tasks.json");
        backupAppFile(context, "tasks/scheduled-tasks.json", file);
        rollback.add(() -> restoreAppFile(context, "tasks/scheduled-tasks.json", file));

        Map<String, ScheduledTask> existing = byId(store.all());
        Map<String, ScheduledTask> result = new LinkedHashMap<>();
        if (!"replace".equals(strategy)) for (Map.Entry<String, ScheduledTask> e : existing.entrySet()) result.put(e.getKey(), ScheduledTask.copy(e.getValue()));
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject obj = tasks.optJSONObject(i);
            if (obj == null) continue;
            ScheduledTask incoming = ScheduledTask.fromJson(obj);
            boolean exists = existing.containsKey(incoming.id);
            ImportRecordAction action = resolveAction(strategy, exists);
            if (action == ImportRecordAction.SKIP || action == ImportRecordAction.REJECT) continue;
            if ("merge".equals(strategy) && exists) {
                ScheduledTask prev = existing.get(incoming.id);
                if (!obj.has("lastRunAt")) incoming.lastRunAt = prev.lastRunAt;
                if (!obj.has("nextRunAt")) incoming.nextRunAt = prev.nextRunAt;
            }
            result.put(incoming.id, incoming);
        }
        replaceScheduledTasks(context, result);
    }

    private static void applyWebhooks(Context context, JSONArray webhooks, String strategy, File recoveryRoot, List<Runnable> rollback) throws Exception {
        WebhookStore store = WebhookStore.get(context);
        File file = new File(recoveryRoot, "webhooks.json");
        backupAppFile(context, "webhooks/webhooks.json", file);
        rollback.add(() -> restoreAppFile(context, "webhooks/webhooks.json", file));

        Map<String, WebhookConfig> existing = byWebhookId(store.all());
        Map<String, WebhookConfig> result = new LinkedHashMap<>();
        if (!"replace".equals(strategy)) for (Map.Entry<String, WebhookConfig> e : existing.entrySet()) result.put(e.getKey(), WebhookConfig.copy(e.getValue()));
        for (int i = 0; i < webhooks.length(); i++) {
            JSONObject obj = webhooks.optJSONObject(i);
            if (obj == null) continue;
            WebhookConfig incoming = WebhookConfig.fromJson(obj);
            boolean exists = existing.containsKey(incoming.id);
            ImportRecordAction action = resolveAction(strategy, exists);
            if (action == ImportRecordAction.SKIP || action == ImportRecordAction.REJECT) continue;
            if ("merge".equals(strategy) && exists) {
                WebhookConfig prev = existing.get(incoming.id);
                if (!obj.has("secret") || incoming.secret == null || incoming.secret.isEmpty()) incoming.secret = prev.secret;
                if (!obj.has("failureCount")) incoming.failureCount = prev.failureCount;
                if (!obj.has("lastDeliveryAt")) incoming.lastDeliveryAt = prev.lastDeliveryAt;
            }
            result.put(incoming.id, incoming);
        }
        replaceWebhooks(context, result);
    }

    private static void applyAppConfig(StorageLayout layout, JSONObject cfg, File recoveryRoot, List<Runnable> rollback) throws Exception {
        File target = appConfigFile(layout);
        File backup = new File(recoveryRoot, "app-config.json");
        if (target.exists()) copyFile(target, backup);
        rollback.add(() -> {
            try {
                if (backup.exists()) copyFile(backup, target);
                else if (target.exists()) target.delete();
            } catch (Exception ignored) {}
        });
        writeJson(target, cfg == null ? new JSONObject() : cfg);
    }

    private static void applyAdNetwork(Context context, JSONObject adNetwork, String strategy, File recoveryRoot, List<Runnable> rollback) throws Exception {
        com.yadaventerprise.phoneserver.ads.AdNetworkManager.importConfig(context, adNetwork, strategy, recoveryRoot, rollback);
    }

    private static void verifyApplied(Context context, JSONObject body, String strategy) throws Exception {
        if (body.has("users")) {
            JSONArray users = body.optJSONArray("users");
            Set<String> ids = new HashSet<>();
            for (RemoteUser u : RemoteUserStore.get(context).all()) ids.add(u.id);
            for (int i = 0; i < users.length(); i++) {
                JSONObject obj = users.optJSONObject(i); if (obj == null) continue;
                String id = obj.optString("id", "");
                boolean exists = ids.contains(id);
                ImportRecordAction action = resolveAction(strategy, exists);
                if ((action == ImportRecordAction.CREATE || action == ImportRecordAction.UPDATE) && !exists) {
                    throw new DataException("Import verification failed for users", 500);
                }
            }
        }
        if (body.has("webhooks")) {
            Set<String> ids = new HashSet<>();
            for (WebhookConfig w : WebhookStore.get(context).all()) ids.add(w.id);
            JSONArray arr = body.optJSONArray("webhooks");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i); if (obj == null) continue;
                String id = obj.optString("id", "");
                boolean exists = ids.contains(id);
                ImportRecordAction action = resolveAction(strategy, exists);
                if ((action == ImportRecordAction.CREATE || action == ImportRecordAction.UPDATE) && !exists) {
                    throw new DataException("Import verification failed for webhooks", 500);
                }
            }
        }
    }

    private static void createRecoveryPoint(Context context, StorageLayout layout, File recoveryRoot) throws Exception {
        backupJsonFile(context, "users.json", new File(recoveryRoot, "users.json"));
        backupSecurityFile(context, "token-policies.json", new File(recoveryRoot, "token-policies.json"));
        backupAppFile(context, "tasks/scheduled-tasks.json", new File(recoveryRoot, "scheduled-tasks.json"));
        backupAppFile(context, "webhooks/webhooks.json", new File(recoveryRoot, "webhooks.json"));
        File appCfg = appConfigFile(layout);
        if (appCfg.exists()) copyFile(appCfg, new File(recoveryRoot, "app-config.json"));
        File ad = adNetworkFile(context);
        if (ad.exists()) copyFile(ad, new File(recoveryRoot, "ad-network.json"));
        writeJson(new File(recoveryRoot, "recovery-manifest.json"), new JSONObject().put("createdAt", System.currentTimeMillis()).put("format", "phoneserver-config-recovery"));
    }

    private static void restoreRecoveryPoint(Context context, StorageLayout layout, File recoveryRoot) {
        restoreJsonFile(context, "users.json", new File(recoveryRoot, "users.json"));
        restoreSecurityFile(context, "token-policies.json", new File(recoveryRoot, "token-policies.json"));
        restoreAppFile(context, "tasks/scheduled-tasks.json", new File(recoveryRoot, "scheduled-tasks.json"));
        restoreAppFile(context, "webhooks/webhooks.json", new File(recoveryRoot, "webhooks.json"));
        try {
            File appCfg = appConfigFile(layout);
            File b = new File(recoveryRoot, "app-config.json");
            if (b.exists()) copyFile(b, appCfg); else if (appCfg.exists()) appCfg.delete();
        } catch (Exception ignored) {}
        try {
            File ad = adNetworkFile(context);
            File b = new File(recoveryRoot, "ad-network.json");
            if (b.exists()) copyFile(b, ad); else if (ad.exists()) ad.delete();
        } catch (Exception ignored) {}
    }

    private static void replaceUsers(Context context, Map<String, RemoteUser> users) throws Exception {
        JSONArray array = new JSONArray();
        for (RemoteUser user : users.values()) array.put(user.toJson());
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("users", array);
        File target = com.yadaventerprise.phoneserver.data.remote.RemotePaths.file(context, "users.json");
        atomicWriteJson(target, root);
    }

    private static void replacePolicies(Context context, Map<String, TokenPolicy> policies) throws Exception {
        JSONObject map = new JSONObject();
        for (Map.Entry<String, TokenPolicy> e : policies.entrySet()) map.put(e.getKey(), e.getValue().toJson());
        JSONObject root = new JSONObject();
        root.put("policies", map);
        File target = new File(new File(context.getApplicationContext().getFilesDir(), "security"), "token-policies.json");
        atomicWriteJson(target, root);
        TokenPolicyStore.reset();
    }

    private static void replaceScheduledTasks(Context context, Map<String, ScheduledTask> tasks) throws Exception {
        JSONArray array = new JSONArray();
        for (ScheduledTask task : tasks.values()) array.put(task.toJson());
        File target = new File(new File(context.getApplicationContext().getFilesDir(), "tasks"), "scheduled-tasks.json");
        atomicWriteJsonArray(target, array);
        ScheduledTaskStore.reset();
    }

    private static void replaceWebhooks(Context context, Map<String, WebhookConfig> webhooks) throws Exception {
        JSONArray array = new JSONArray();
        for (WebhookConfig cfg : webhooks.values()) array.put(cfg.toJson());
        File target = new File(new File(context.getApplicationContext().getFilesDir(), "webhooks"), "webhooks.json");
        atomicWriteJsonArray(target, array);
        WebhookStore.reset();
    }

    private static RemoteUser mergeUser(RemoteUser existing, JSONObject incoming, boolean merge) {
        RemoteUser target = existing.copy();
        if (incoming.has("username")) target.username = RemoteUser.normalizeUsername(incoming.optString("username", target.username));
        if (incoming.has("displayName")) target.displayName = incoming.optString("displayName", target.displayName);
        if (incoming.has("role")) target.role = incoming.optString("role", target.role);
        if (incoming.has("status")) target.status = incoming.optString("status", target.status);
        if (incoming.has("passwordHash")) target.passwordHash = incoming.optString("passwordHash", target.passwordHash);
        return target;
    }

    private static TokenPolicy copyPolicy(TokenPolicy p) {
        TokenPolicy c = new TokenPolicy();
        c.tokenId = p.tokenId; c.role = p.role; c.permissions.addAll(p.permissions);
        c.rateLimitPerMinute = p.rateLimitPerMinute; c.expiresAt = p.expiresAt; c.updatedAt = p.updatedAt;
        return c;
    }

    private static Map<String, ScheduledTask> byId(List<ScheduledTask> tasks) {
        Map<String, ScheduledTask> map = new LinkedHashMap<>();
        for (ScheduledTask t : tasks) map.put(t.id, t);
        return map;
    }

    private static Map<String, WebhookConfig> byWebhookId(List<WebhookConfig> list) {
        Map<String, WebhookConfig> map = new LinkedHashMap<>();
        for (WebhookConfig w : list) map.put(w.id, w);
        return map;
    }

    private static ImportRecordAction resolveAction(String strategy, boolean exists) {
        if ("replace".equals(strategy)) return exists ? ImportRecordAction.UPDATE : ImportRecordAction.CREATE;
        if ("create_only".equals(strategy)) return exists ? ImportRecordAction.SKIP : ImportRecordAction.CREATE;
        if ("update_only".equals(strategy)) return exists ? ImportRecordAction.UPDATE : ImportRecordAction.SKIP;
        return exists ? ImportRecordAction.UPDATE : ImportRecordAction.CREATE;
    }

    private static void backupJsonFile(Context context, String name, File backup) throws Exception {
        File src = com.yadaventerprise.phoneserver.data.remote.RemotePaths.file(context, name);
        if (src.exists()) copyFile(src, backup);
    }

    private static void restoreJsonFile(Context context, String name, File backup) {
        try {
            File target = com.yadaventerprise.phoneserver.data.remote.RemotePaths.file(context, name);
            if (backup.exists()) copyFile(backup, target);
            else if (target.exists()) target.delete();
            RemoteUserStore.reset();
        } catch (Exception ignored) {}
    }

    private static void backupSecurityFile(Context context, String name, File backup) throws Exception {
        File src = new File(new File(context.getApplicationContext().getFilesDir(), "security"), name);
        if (src.exists()) copyFile(src, backup);
    }

    private static void restoreSecurityFile(Context context, String name, File backup) {
        try {
            File target = new File(new File(context.getApplicationContext().getFilesDir(), "security"), name);
            if (backup.exists()) copyFile(backup, target); else if (target.exists()) target.delete();
            TokenPolicyStore.reset();
        } catch (Exception ignored) {}
    }

    private static void backupAppFile(Context context, String rel, File backup) throws Exception {
        File src = new File(context.getApplicationContext().getFilesDir(), rel);
        if (src.exists()) copyFile(src, backup);
    }

    private static void restoreAppFile(Context context, String rel, File backup) {
        try {
            File target = new File(context.getApplicationContext().getFilesDir(), rel);
            if (backup.exists()) copyFile(backup, target); else if (target.exists()) target.delete();
            ScheduledTaskStore.reset();
            WebhookStore.reset();
        } catch (Exception ignored) {}
    }

    private static void atomicWriteJson(File target, JSONObject json) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        writeJson(tmp, json);
        if (!tmp.renameTo(target)) {
            if (target.exists() && !target.delete()) { tmp.delete(); throw new IOException("could not replace " + target.getName()); }
            if (!tmp.renameTo(target)) { tmp.delete(); throw new IOException("could not finalize " + target.getName()); }
        }
    }

    private static void atomicWriteJsonArray(File target, JSONArray array) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        if (!tmp.renameTo(target)) {
            if (target.exists() && !target.delete()) { tmp.delete(); throw new IOException("could not replace " + target.getName()); }
            if (!tmp.renameTo(target)) { tmp.delete(); throw new IOException("could not finalize " + target.getName()); }
        }
    }

    private static void writeJson(File target, JSONObject json) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (java.io.InputStream in = new java.io.FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.flush();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        file.delete();
    }

    private static void planAdTable(ImportPlan plan, JSONArray incoming, JSONArray existing, String strategy) {
        if (incoming == null) return;
        Set<String> existingIds = new HashSet<>();
        if (existing != null) {
            for (int i = 0; i < existing.length(); i++) {
                JSONObject o = existing.optJSONObject(i);
                if (o != null) existingIds.add(String.valueOf(o.optLong("id")));
            }
        }
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject o = incoming.optJSONObject(i);
            if (o == null) { plan.addRecord(new JSONObject(), ImportRecordAction.REJECT); continue; }
            String id = String.valueOf(o.optLong("id", -1));
            boolean exists = existingIds.contains(id);
            ImportRecordAction action;
            if ("replace".equals(strategy)) action = ImportRecordAction.UPDATE;
            else if ("create_only".equals(strategy)) action = exists ? ImportRecordAction.SKIP : ImportRecordAction.CREATE;
            else if ("update_only".equals(strategy)) action = exists ? ImportRecordAction.UPDATE : ImportRecordAction.SKIP;
            else action = exists ? ImportRecordAction.UPDATE : ImportRecordAction.CREATE;
            plan.addRecord(o, action);
        }
    }

    private static String normalizeStrategy(String strategy) {
        if (strategy == null) return "merge";
        String s = strategy.trim().toLowerCase(java.util.Locale.US);
        if ("create-only".equals(s) || "create".equals(s)) return "create_only";
        if ("update-only".equals(s) || "update".equals(s)) return "update_only";
        if ("selective_replace".equals(s) || "selective-replace".equals(s) || "replace_selected".equals(s)) return "replace";
        if ("merge".equals(s) || "create_only".equals(s) || "update_only".equals(s) || "replace".equals(s)) return s;
        return s;
    }

    private static void validateStrategy(String strategy, ValidationResult result) {
        if ("merge".equals(strategy) || "create_only".equals(strategy) || "update_only".equals(strategy) || "replace".equals(strategy)) return;
        result.error("INVALID_STRATEGY", "strategy", "Unsupported import strategy: " + strategy, "Use merge, create_only, update_only, or replace.");
    }

    private static StorageLayout layoutFor(Context context) { return ContentStoreBridge.layout(context); }

    private static File appConfigFile(StorageLayout layout) {
        File dir = new File(layout.root(), "config");
        StorageLayout.ensure(dir);
        return new File(dir, "app-config.json");
    }

    private static JSONObject readOrDefaultAppConfig(StorageLayout layout) {
        File f = appConfigFile(layout);
        if (f.exists()) {
            try { return JsonStorage.readJson(f); } catch (Exception ignored) {}
        }
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("schemaVersion", 1);
            cfg.put("appName", "PhoneServer");
            cfg.put("apiBasePath", "/api/v1");
            cfg.put("defaultProjectQuotaBytes", 0);
            cfg.put("maxUploadBytes", 0);
            cfg.put("features", new JSONObject());
        } catch (Exception ignored) {}
        return cfg;
    }

    private static File adNetworkFile(Context context) {
        return new File(new File(context.getApplicationContext().getFilesDir(), "config"), "ad-network-export.json");
    }

    private static JSONObject exportAdNetwork(Context context) {
        try {
            return com.yadaventerprise.phoneserver.ads.AdNetworkManager.exportConfig(context);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String safeMessage(Exception e) {
        String m = e == null ? null : e.getMessage();
        if (m == null || m.isEmpty()) return e == null ? "unknown error" : e.getClass().getSimpleName();
        return m;
    }
}
