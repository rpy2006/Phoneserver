package com.yadaventerprise.phoneserver.data.storage.validation;

import org.json.JSONObject;

public final class ValidationIssue {
    public final String status;
    public final String errorCode;
    public final String fieldOrRecord;
    public final String message;
    public final ValidationSeverity severity;
    public final String suggestedCorrection;

    public ValidationIssue(String status, String errorCode, String fieldOrRecord, String message,
                           ValidationSeverity severity, String suggestedCorrection) {
        this.status = status == null ? "invalid" : status;
        this.errorCode = errorCode == null ? "VALIDATION_ERROR" : errorCode;
        this.fieldOrRecord = fieldOrRecord == null ? "" : fieldOrRecord;
        this.message = message == null ? "Validation failed" : message;
        this.severity = severity == null ? ValidationSeverity.ERROR : severity;
        this.suggestedCorrection = suggestedCorrection == null ? "" : suggestedCorrection;
    }

    public static ValidationIssue error(String code, String ref, String message, String suggestion) {
        return new ValidationIssue("invalid", code, ref, message, ValidationSeverity.ERROR, suggestion);
    }

    public static ValidationIssue warning(String code, String ref, String message, String suggestion) {
        return new ValidationIssue("warning", code, ref, message, ValidationSeverity.WARNING, suggestion);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("status", status);
            json.put("errorCode", errorCode);
            json.put("fieldOrRecord", fieldOrRecord);
            json.put("message", message);
            json.put("severity", severity.name());
            json.put("suggestedCorrection", suggestedCorrection);
        } catch (Exception ignored) {
        }
        return json;
    }
}
