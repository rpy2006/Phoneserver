package com.yadaventerprise.phoneserver.data.system;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class MaintenanceReport {
    public long generatedAt;
    public int tempFilesCount;
    public long tempBytes;
    public int removedFiles;
    public long removedBytes;
    public int jsonFilesChecked;
    public int backupsChecked;
    public int validBackups;
    public int invalidBackups;
    public long logBytes;
    public final List<String> issues = new ArrayList<>();

    public int issueCount() {
        return issues.size();
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("generatedAt", generatedAt);
        json.put("tempFilesCount", tempFilesCount);
        json.put("tempBytes", tempBytes);
        json.put("removedFiles", removedFiles);
        json.put("removedBytes", removedBytes);
        json.put("jsonFilesChecked", jsonFilesChecked);
        json.put("backupsChecked", backupsChecked);
        json.put("validBackups", validBackups);
        json.put("invalidBackups", invalidBackups);
        json.put("logBytes", logBytes);
        json.put("issueCount", issueCount());
        JSONArray array = new JSONArray();
        for (String issue : issues) array.put(issue);
        json.put("issues", array);
        return json;
    }
}
