package com.yadaventerprise.phoneserver.data.storage.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ValidationResult {
    public final String category;
    public final List<ValidationIssue> issues = new ArrayList<>();

    public ValidationResult(String category) {
        this.category = category == null ? "unknown" : category;
    }

    public void add(ValidationIssue issue) {
        if (issue != null) issues.add(issue);
    }

    public void error(String code, String ref, String message, String suggestion) {
        add(ValidationIssue.error(code, ref, message, suggestion));
    }

    public void warning(String code, String ref, String message, String suggestion) {
        add(ValidationIssue.warning(code, ref, message, suggestion));
    }

    public boolean isValid() {
        for (ValidationIssue issue : issues) {
            if (issue.severity == ValidationSeverity.ERROR) return false;
        }
        return true;
    }

    public int errorCount() {
        int count = 0;
        for (ValidationIssue issue : issues) if (issue.severity == ValidationSeverity.ERROR) count++;
        return count;
    }

    public List<ValidationIssue> getIssues() {
        return Collections.unmodifiableList(issues);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("category", category);
            json.put("valid", isValid());
            json.put("errorCount", errorCount());
            JSONArray arr = new JSONArray();
            for (ValidationIssue issue : issues) arr.put(issue.toJson());
            json.put("issues", arr);
        } catch (Exception ignored) {
        }
        return json;
    }
}
