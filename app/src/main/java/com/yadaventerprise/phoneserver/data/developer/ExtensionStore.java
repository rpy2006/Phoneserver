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
    private final Object lock = new Object();
    private List<ExtensionRecord> cached;

    private ExtensionStore(File file) { this.file = file; }

    public static ExtensionStore get(Context context) {
        if (instance == null) {
            synchronized (ExtensionStore.class) {
                if (instance == null) {
                    instance = new ExtensionStore(new File(DeveloperPaths.developerDir(context), "extensions.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() { instance = null; }

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

    public ExtensionRecord install(JSONObject manifest) throws Exception {
        ExtensionRecord r = parseManifest(manifest);
        synchronized (lock) {
            ensureLoaded();
            for (ExtensionRecord existing : cached) {
                if (existing.id.equals(r.id)) throw new IllegalArgumentException("extension already installed");
            }
            validateDependencies(r, cached);
            r.installedAt = System.currentTimeMillis();
            r.updatedAt = r.installedAt;
            r.status = "installed";
            cached.add(r);
            persist();
            return r;
        }
    }

    public ExtensionRecord setEnabled(String id, boolean enabled, boolean approved) throws Exception {
        synchronized (lock) {
            ensureLoaded();
            for (ExtensionRecord r : cached) {
                if (!r.id.equals(id)) continue;
                if (enabled) {
                    if (!approved && !r.approved) throw new IllegalArgumentException("admin approval required before enabling extension");
                    r.approved = true;
                    validateDependencies(r, cached);
                }
                r.enabled = enabled;
                r.updatedAt = System.currentTimeMillis();
                r.status = enabled ? "enabled" : "disabled";
                persist();
                return r;
            }
            return null;
        }
    }

    public boolean remove(String id) {
        synchronized (lock) {
            ensureLoaded();
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(id)) {
                    cached.remove(i);
                    persist();
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
                if (!r.dependencies.contains(d)) r.dependencies.add(d);
            }
        }
        String type = manifest.optString("type", "manifest").trim().toLowerCase();
        if (!type.equals("manifest") && !type.equals("ui") && !type.equals("integration")) {
            throw new IllegalArgumentException("unsupported extension type");
        }
        if (manifest.has("code") || manifest.has("script") || manifest.has("exec") || manifest.has("command")) {
            throw new IllegalArgumentException("executable extension code is not allowed");
        }
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
