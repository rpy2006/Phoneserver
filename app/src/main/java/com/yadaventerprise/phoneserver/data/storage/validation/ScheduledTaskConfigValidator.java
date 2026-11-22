package com.yadaventerprise.phoneserver.data.storage.validation;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ScheduledTaskConfigValidator {
    private ScheduledTaskConfigValidator() {}

    public static ImportPlan validate(JSONArray tasks, Set<String> existingTaskIds, String strategy) {
        ImportPlan plan = new ImportPlan("scheduled_tasks");
        if (tasks == null) {
            plan.addIssue(ValidationIssue.error("TASKS_REQUIRED", "scheduled_tasks", "Scheduled tasks array is required.", "Provide scheduled_tasks array."));
            return plan;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject raw = tasks.optJSONObject(i);
            String ref = "scheduled_tasks[" + i + "]";
            if (raw == null) {
                plan.addIssue(ValidationIssue.error("INVALID_RECORD", ref, "Task record must be object.", "Provide object."));
                plan.addRecord(new JSONObject(), ImportRecordAction.REJECT);
                continue;
            }
            boolean ok = true;
            String id = raw.optString("id", "").trim();
            String action = raw.optString("action", "").trim();
            String scheduleType = raw.optString("scheduleType", "recurring").trim();
            if (id.isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".id", "Task id is required.", "Provide unique id.")); ok = false; }
            if (!id.isEmpty() && !seen.add(id)) { plan.addIssue(ValidationIssue.error("DUPLICATE_ID", ref + ".id", "Duplicate task id: " + id, "Use unique id.")); ok = false; }
            if (!"backup".equals(action) && !"webhook".equals(action)) { plan.addIssue(ValidationIssue.error("INVALID_ACTION", ref + ".action", "Unsupported action: " + action, "Use backup or webhook.")); ok = false; }
            if (!"recurring".equals(scheduleType) && !"once".equals(scheduleType)) { plan.addIssue(ValidationIssue.error("INVALID_SCHEDULE", ref + ".scheduleType", "Unsupported scheduleType: " + scheduleType, "Use recurring or once.")); ok = false; }
            if ("recurring".equals(scheduleType) && raw.optInt("intervalMinutes", 0) < 1) { plan.addIssue(ValidationIssue.error("INVALID_SCHEDULE", ref + ".intervalMinutes", "intervalMinutes must be >= 1 for recurring tasks.", "Set intervalMinutes >= 1.")); ok = false; }
            if ("once".equals(scheduleType) && raw.optLong("runAt", 0) <= 0) { plan.addIssue(ValidationIssue.error("INVALID_SCHEDULE", ref + ".runAt", "runAt must be set for one-time tasks.", "Set runAt epoch millis.")); ok = false; }
            if (raw.optInt("maxAttempts", 1) < 1) { plan.addIssue(ValidationIssue.error("INVALID_VALUE", ref + ".maxAttempts", "maxAttempts must be >= 1.", "Set maxAttempts >= 1.")); ok = false; }
            if (raw.optInt("retryDelayMinutes", 1) < 1) { plan.addIssue(ValidationIssue.error("INVALID_VALUE", ref + ".retryDelayMinutes", "retryDelayMinutes must be >= 1.", "Set retryDelayMinutes >= 1.")); ok = false; }
            if ("backup".equals(action) && raw.optString("projectId", "").trim().isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".projectId", "projectId required for backup task.", "Set projectId.")); ok = false; }
            if ("webhook".equals(action) && raw.optString("webhookId", "").trim().isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".webhookId", "webhookId required for webhook task.", "Set webhookId.")); ok = false; }
            ImportRecordAction actionResult = ok ? UserConfigValidator.resolveAction(strategy, existingTaskIds.contains(id), ref, plan) : ImportRecordAction.REJECT;
            plan.addRecord(raw, actionResult);
        }
        return plan;
    }
}
