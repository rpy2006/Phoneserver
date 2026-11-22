package com.yadaventerprise.phoneserver.data.storage.validation;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

public final class JsonConfigValidator {
    private JsonConfigValidator() {}

    public static ValidationResult validate(JSONObject json, String category) {
        ValidationResult result = new ValidationResult(category);
        if (json == null) {
            result.error("JSON_REQUIRED", category, "Configuration object is required.", "Provide a JSON object.");
            return result;
        }
        int schemaVersion = json.optInt("schemaVersion", -1);
        if (schemaVersion < 0) {
            result.error("SCHEMA_VERSION_REQUIRED", category + ".schemaVersion", "schemaVersion is required.", "Add schemaVersion with a supported integer value.");
        } else if (schemaVersion > 1) {
            result.error("SCHEMA_VERSION_UNSUPPORTED", category + ".schemaVersion", "Unsupported schema version: " + schemaVersion, "Use schemaVersion 1.");
        }
        String[] required = {"appName", "apiBasePath", "defaultProjectQuotaBytes", "maxUploadBytes", "features"};
        for (String field : required) {
            if (!json.has(field)) {
                result.error("FIELD_REQUIRED", category + "." + field, "Required field is missing: " + field, "Add the missing field.");
            }
        }
        Set<String> allowed = new HashSet<>();
        allowed.add("schemaVersion");
        allowed.add("appName");
        allowed.add("apiBasePath");
        allowed.add("defaultProjectQuotaBytes");
        allowed.add("maxUploadBytes");
        allowed.add("features");
        allowed.add("notes");
        Iterator<String> keys = json.keys();
        while (keys != null && keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) {
                result.error("UNKNOWN_FIELD", category + "." + key, "Unknown field: " + key, "Remove unknown field or update schema.");
            }
        }
        if (json.has("appName") && !(json.opt("appName") instanceof String)) {
            result.error("INVALID_TYPE", category + ".appName", "appName must be a string.", "Provide a string appName.");
        } else if (json.has("appName")) {
            String appName = json.optString("appName", "");
            if (appName == null || appName.trim().isEmpty()) {
                result.error("INVALID_VALUE", category + ".appName", "appName must not be empty.", "Provide a non-empty appName.");
            }
        }
        if (json.has("apiBasePath") && !(json.opt("apiBasePath") instanceof String)) {
            result.error("INVALID_TYPE", category + ".apiBasePath", "apiBasePath must be a string.", "Provide a string path.");
        } else if (json.has("apiBasePath")) {
            String path = json.optString("apiBasePath", "");
            if (path == null) path = "";
            if (!path.startsWith("/") || path.contains("..") || path.contains(" ")) {
                result.error("INVALID_VALUE", category + ".apiBasePath", "apiBasePath is invalid.", "Use an absolute path like /api/v1.");
            }
        }
        validateNonNegativeLong(json, "defaultProjectQuotaBytes", category, result);
        validateNonNegativeLong(json, "maxUploadBytes", category, result);
        if (json.has("features") && !(json.opt("features") instanceof JSONObject)) {
            result.error("INVALID_TYPE", category + ".features", "features must be an object.", "Provide features as a JSON object.");
        } else if (json.opt("features") instanceof JSONObject) {
            JSONObject features = json.optJSONObject("features");
            Iterator<String> fk = features.keys();
            while (fk.hasNext()) {
                String k = fk.next();
                if (!(features.opt(k) instanceof Boolean)) {
                    result.error("INVALID_TYPE", category + ".features." + k, "Feature flag must be boolean.", "Use true or false.");
                }
            }
        }
        return result;
    }

    private static void validateNonNegativeLong(JSONObject json, String field, String category, ValidationResult result) {
        if (!json.has(field)) return;
        Object value = json.opt(field);
        if (!(value instanceof Number)) {
            result.error("INVALID_TYPE", category + "." + field, field + " must be a number.", "Provide a non-negative integer.");
            return;
        }
        long v = ((Number) value).longValue();
        if (v < 0) {
            result.error("INVALID_VALUE", category + "." + field, field + " must not be negative.", "Use 0 or a positive value.");
        }
    }
}
