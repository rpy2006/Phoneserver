package com.yadaventerprise.phoneserver.data.developer;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class ExtensionRecord {
    public String id = "";
    public String name = "";
    public String version = "";
    public String description = "";
    public String author = "";
    public boolean enabled;
    public boolean approved;
    public long installedAt;
    public long updatedAt;
    public final List<String> permissions = new ArrayList<>();
    public final List<String> dependencies = new ArrayList<>();
    public String manifestJson = "";
    public String status = "installed";

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("version", version);
        json.put("description", description);
        json.put("author", author);
        json.put("enabled", enabled);
        json.put("approved", approved);
        json.put("installedAt", installedAt);
        json.put("updatedAt", updatedAt);
        json.put("status", status);
        JSONArray perms = new JSONArray();
        for (String p : permissions) perms.put(p);
        json.put("permissions", perms);
        JSONArray deps = new JSONArray();
        for (String d : dependencies) deps.put(d);
        json.put("dependencies", deps);
        json.put("manifest", manifestJson);
        return json;
    }

    public static ExtensionRecord fromJson(JSONObject json) {
        ExtensionRecord r = new ExtensionRecord();
        r.id = json.optString("id", "");
        r.name = json.optString("name", "");
        r.version = json.optString("version", "");
        r.description = json.optString("description", "");
        r.author = json.optString("author", "");
        r.enabled = json.optBoolean("enabled", false);
        r.approved = json.optBoolean("approved", false);
        r.installedAt = json.optLong("installedAt", 0);
        r.updatedAt = json.optLong("updatedAt", 0);
        r.status = json.optString("status", "installed");
        r.manifestJson = json.optString("manifest", "");
        JSONArray perms = json.optJSONArray("permissions");
        if (perms != null) for (int i = 0; i < perms.length(); i++) r.permissions.add(perms.optString(i, ""));
        JSONArray deps = json.optJSONArray("dependencies");
        if (deps != null) for (int i = 0; i < deps.length(); i++) r.dependencies.add(deps.optString(i, ""));
        return r;
    }
}
