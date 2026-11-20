package com.yadaventerprise.phoneserver.data.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BackupPreview {
    public boolean valid;
    public boolean encrypted;
    public String format = "";
    public String version = "";
    public String backupType = BackupOptions.TYPE_FULL;
    public String projectId = "";
    public String projectName = "";
    public long createdAt;
    public boolean includesFiles;
    public boolean includesDatabases = true;
    public String note = "";
    public String checksum = "";
    public String parentBackupId = "";
    public final List<String> databases = new ArrayList<>();
    public final List<String> files = new ArrayList<>();
    public final List<String> folders = new ArrayList<>();
    public final Map<String, String> manifest = new LinkedHashMap<>();
    public String error = "";

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("valid", valid);
            json.put("encrypted", encrypted);
            json.put("format", format);
            json.put("version", version);
            json.put("backupType", backupType);
            json.put("projectId", projectId);
            json.put("projectName", projectName);
            json.put("createdAt", createdAt);
            json.put("includesFiles", includesFiles);
            json.put("includesDatabases", includesDatabases);
            json.put("note", note);
            json.put("checksum", checksum);
            json.put("parentBackupId", parentBackupId);
            json.put("error", error);
            JSONArray dbs = new JSONArray();
            for (String db : databases) dbs.put(db);
            json.put("databases", dbs);
            JSONArray fs = new JSONArray();
            for (String f : files) fs.put(f);
            json.put("files", fs);
            JSONArray dirs = new JSONArray();
            for (String d : folders) dirs.put(d);
            json.put("folders", dirs);
            JSONObject m = new JSONObject();
            for (Map.Entry<String, String> e : manifest.entrySet()) m.put(e.getKey(), e.getValue());
            json.put("manifest", m);
        } catch (Exception ignored) {
        }
        return json;
    }
}
