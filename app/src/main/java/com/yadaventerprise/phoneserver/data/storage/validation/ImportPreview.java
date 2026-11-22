package com.yadaventerprise.phoneserver.data.storage.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ImportPreview {
    public boolean valid;
    public String packageFormat = "";
    public String packageVersion = "";
    public final Map<String, ImportPlan> categories = new LinkedHashMap<>();
    public final ValidationResult packageValidation = new ValidationResult("package");

    public void addPlan(ImportPlan plan) {
        if (plan == null) return;
        categories.put(plan.category, plan);
    }

    public void finalizeStatus() {
        boolean ok = packageValidation.isValid();
        for (ImportPlan plan : categories.values()) {
            if (plan.hasErrors()) ok = false;
        }
        valid = ok;
    }

    public JSONObject toJson() {
        finalizeStatus();
        JSONObject json = new JSONObject();
        try {
            json.put("valid", valid);
            json.put("packageFormat", packageFormat);
            json.put("packageVersion", packageVersion);
            json.put("packageValidation", packageValidation.toJson());
            JSONArray arr = new JSONArray();
            int create = 0, update = 0, skip = 0, reject = 0;
            for (ImportPlan plan : categories.values()) {
                arr.put(plan.toJson());
                create += plan.createCount;
                update += plan.updateCount;
                skip += plan.skipCount;
                reject += plan.rejectCount;
            }
            json.put("categories", arr);
            JSONObject totals = new JSONObject();
            totals.put("create", create);
            totals.put("update", update);
            totals.put("skip", skip);
            totals.put("reject", reject);
            json.put("totals", totals);
        } catch (Exception ignored) {
        }
        return json;
    }
}
