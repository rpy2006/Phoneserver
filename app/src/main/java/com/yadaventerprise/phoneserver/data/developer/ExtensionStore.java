package com.yadaventerprise.phoneserver.data.developer;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ExtensionStore {
    private static volatile ExtensionStore instance;
    private final File file;
    private final File activityFile;
    private final Object lock = new Object();
    private List<ExtensionRecord> cached;

    private ExtensionStore(Context context) {
        this.file = new File(DeveloperPaths.developerDir(context), "extensions.json");
        this.activityFile = new File(DeveloperPaths.developerDir(context), "extension-activity.json");
    }

    public static ExtensionStore get(Context context) {
        if (instance == null) {
            synchronized (ExtensionStore.class) {
                if (instance == null) {
                    instance = new ExtensionStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() { instance = null; }

    public List<JSONObject> activity(int limit) {
        synchronized (lock) {
            List<JSONObject> all = readActivity();
            int max = Math.max(1, Math.min(limit, 200));
            if (all.size() <= max) return all;
            return new ArrayList<>(all.subList(0, max));
        }
    }

    private void record(String action, String extensionId, String actor, String detail) {
        try {
            List<JSONObject> all = readActivity();
            JSONObject row = new JSONObject();
            row.put("time", System.currentTimeMillis());
            row.put("action", action == null ? "" : action);
            row.put("extensionId", extensionId == null ? "" : extensionId);
            row.put("actor", actor == null ? "" : actor);
            row.put("detail", detail == null ? "" : detail);
            all.add(0, row);
            while (all.size() > 500) all.remove(all.size() - 1);
            JSONArray arr = new JSONArray();
            for (JSONObject o : all) arr.put(o);
            JsonStorage.writeJsonArrayAtomic(activityFile, arr, true);
            SecurityPaths.restrict(activityFile);
        } catch (Exception ignored) {}
    }

    private List<JSONObject> readActivity() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = JsonStorage.readJsonArray(activityFile);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public List<ExtensionRecord> list() {
        synchronized (lock) { ensureLoaded(); return new ArrayList<>(cached); }
    }

    public ExtensionRecord get(String id) {
        synchronized (lock) {
            ensureLoaded();
            for (ExtensionRecord r : cached) if (r.id.equals(id)) return r;
            return null;
        }
    }

    public ExtensionRecord install(JSONObject manifest, String actor) throws Exception {
        ExtensionRecord r = parseManifest(manifest);
        synchronized (lock) {
            ensureLoaded();
            for (ExtensionRecord existing : cached) {
                if (existing.id.equals(r.id)) throw new IllegalArgumentException("extension already installed");
            }
            validateDependencies(r, cached);
            r.installedAt = System.currentTimeMillis();
            r.updatedAt = r.installedAt;
            r.enabled = false;
            r.approved = false;
            r.status = "installed";
            cached.add(r);
            persist();
            record("install", r.id, actor, "manifest installed; disabled pending admin approval");
            return r;
        }
    }

    public ExtensionRecord approve(String id, boolean approved, String actor) throws Exception {
        synchronized (lock) {
            ensureLoaded();
            for (ExtensionRecord r : cached) {
                if (!r.id.equals(id)) continue;
                if (!approved) {
                    r.approved = false;
                    r.enabled = false;
                    r.status = "revoked";
                } else {
                    validateDependencies(r, cached);
                    r.approved = true;
                    if (!r.enabled) r.status = "approved";
                }
                r.updatedAt = System.currentTimeMillis();
                persist();
                record(approved ? "approve" : "revoke", r.id, actor, approved ? "extension approved" : "extension approval revoked");
                return r;
            }
            return null;
        }
    }

    public ExtensionRecord setEnabled(String id, boolean enabled, boolean approved, String actor) throws Exception {
        synchronized (lock) {
            ensureLoaded();
            for (ExtensionRecord r : cached) {
                if (!r.id.equals(id)) continue;
                if (enabled) {
                    if (approved) r.approved = true;
                    if (!r.approved) throw new IllegalArgumentException("admin approval required before enabling extension");
                    validateDependencies(r, cached);
                }
                r.enabled = enabled;
                r.updatedAt = System.currentTimeMillis();
                r.status = enabled ? "enabled" : (r.approved ? "approved" : "installed");
                persist();
                record(enabled ? "enable" : "disable", r.id, actor, enabled ? "extension enabled" : "extension disabled");
                return r;
            }
            return null;
        }
    }

    public boolean remove(String id, String actor) {
        synchronized (lock) {
            ensureLoaded();
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(id)) {
                    cached.remove(i);
                    persist();
                    record("remove", id, actor, "extension removed");
                    return true;
                }
            }
            return false;
        }
    }

    private static ExtensionRecord parseManifest(JSONObject manifest) throws Exception {
        if (manifest == null) throw new IllegalArgumentException("manifest is required");
        String id = manifest.optString("id", "").trim();
        String name = manifest.optString("name", "").trim();
        String version = manifest.optString("version", "").trim();
        if (!id.matches("[a-zA-Z0-9._-]{3,64}")) throw new IllegalArgumentException("invalid extension id");
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("invalid extension name");
        if (!version.matches("[0-9]+(\\.[0-9]+){1,3}")) throw new IllegalArgumentException("invalid extension version");
        ExtensionRecord r = new ExtensionRecord();
        r.id = id;
        r.name = name;
        r.version = version;
        r.description = manifest.optString("description", "").trim();
        r.author = manifest.optString("author", "").trim();
        r.manifestJson = manifest.toString();
        JSONArray perms = manifest.optJSONArray("permissions");
        Set<String> allowed = TokenPolicy.allPermissions();
        if (perms != null) {
            for (int i = 0; i < perms.length(); i++) {
                String p = perms.optString(i, "").trim();
                if (p.isEmpty()) continue;
                if (!allowed.contains(p)) throw new IllegalArgumentException("unsupported permission: " + p);
                if (!r.permissions.contains(p)) r.permissions.add(p);
            }
        }
        JSONArray deps = manifest.optJSONArray("dependencies");
        if (deps != null) {
            for (int i = 0; i < deps.length(); i++) {
                String d = deps.optString(i, "").trim();
                if (d.isEmpty()) continue;
                if (!d.matches("[a-zA-Z0-9._-]{3,64}")) throw new IllegalArgumentException("invalid dependency: " + d);
                if (d.equals(r.id)) throw new IllegalArgumentException("extension cannot depend on itself");
                if (!r.dependencies.contains(d)) r.dependencies.add(d);
            }
        }
        String type = manifest.optString("type", "manifest").trim().toLowerCase();
        if (!type.equals("manifest") && !type.equals("ui") && !type.equals("integration")) {
            throw new IllegalArgumentException("unsupported extension type");
        }
        String[] blocked = {"code","script","exec","command","binary","class","dex","jar","native","shell"};
        for (String k : blocked) if (manifest.has(k)) throw new IllegalArgumentException("executable extension code is not allowed");
        if (manifest.toString().length() > 32_768) throw new IllegalArgumentException("manifest too large");
        return r;
    }

    private static void validateDependencies(ExtensionRecord r, List<ExtensionRecord> all) throws Exception {
        Set<String> ids = new HashSet<>();
        for (ExtensionRecord existing : all) ids.add(existing.id);
        for (String dep : r.dependencies) {
            if (!ids.contains(dep)) throw new IllegalArgumentException("missing dependency: " + dep);
        }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        cached = new ArrayList<>();
        try {
            JSONArray arr = JsonStorage.readJsonArray(file);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) cached.add(ExtensionRecord.fromJson(o));
            }
        } catch (Exception ignored) {}
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (ExtensionRecord r : cached) arr.put(r.toJson());
            JsonStorage.writeJsonArrayAtomic(file, arr, true);
            SecurityPaths.restrict(file);
        } catch (Exception ignored) {}
    }
}
