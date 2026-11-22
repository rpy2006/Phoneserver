package com.yadaventerprise.phoneserver.data.storage.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ImportPlan {
    public final String category;
    public final List<ValidationIssue> issues = new ArrayList<>();
    public final List<JSONObject> records = new ArrayList<>();
    public final Map<String, ImportRecordAction> actions = new LinkedHashMap<>();
    public int createCount;
    public int updateCount;
    public int skipCount;
    public int rejectCount;

    public ImportPlan(String category) {
        this.category = category;
    }

    public void addIssue(ValidationIssue issue) {
        if (issue != null) issues.add(issue);
    }

    public void addRecord(JSONObject record, ImportRecordAction action) {
        if (record == null) return;
        records.add(record);
        String id = record.optString("id", record.optString("username", record.optString("name", "record-" + records.size())));
        actions.put(id, action == null ? ImportRecordAction.REJECT : action);
        switch (action == null ? ImportRecordAction.REJECT : action) {
            case CREATE: createCount++; break;
            case UPDATE: updateCount++; break;
            case SKIP: skipCount++; break;
            case REJECT: rejectCount++; break;
        }
    }

    public boolean hasErrors() {
        for (ValidationIssue issue : issues) if (issue.severity == ValidationSeverity.ERROR) return true;
        return rejectCount > 0;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("category", category);
            json.put("valid", !hasErrors());
            json.put("createCount", createCount);
            json.put("updateCount", updateCount);
            json.put("skipCount", skipCount);
            json.put("rejectCount", rejectCount);
            JSONArray issueArr = new JSONArray();
            for (ValidationIssue issue : issues) issueArr.put(issue.toJson());
            json.put("issues", issueArr);
            JSONArray actionArr = new JSONArray();
            for (Map.Entry<String, ImportRecordAction> e : actions.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("id", e.getKey());
                o.put("action", e.getValue().name());
                actionArr.put(o);
            }
            json.put("actions", actionArr);
        } catch (Exception ignored) {
        }
        return json;
    }
}
