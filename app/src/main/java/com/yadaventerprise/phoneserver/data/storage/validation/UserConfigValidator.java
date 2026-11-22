package com.yadaventerprise.phoneserver.data.storage.validation;

import com.yadaventerprise.phoneserver.data.remote.RemoteUser;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class UserConfigValidator {
    private UserConfigValidator() {}

    public static ImportPlan validate(JSONArray users, Set<String> existingUserIds, Set<String> existingUsernames, String strategy) {
        ImportPlan plan = new ImportPlan("users");
        if (users == null) {
            plan.addIssue(ValidationIssue.error("USERS_REQUIRED", "users", "Users array is required.", "Provide a users array."));
            return plan;
        }
        Set<String> seenIds = new HashSet<>();
        Set<String> seenUsernames = new HashSet<>();
        for (int i = 0; i < users.length(); i++) {
            JSONObject raw = users.optJSONObject(i);
            String ref = "users[" + i + "]";
            if (raw == null) {
                plan.addIssue(ValidationIssue.error("INVALID_RECORD", ref, "User record must be an object.", "Provide a user object."));
                plan.addRecord(new JSONObject(), ImportRecordAction.REJECT);
                continue;
            }
            boolean recordValid = true;
            String id = raw.optString("id", "").trim();
            String username = RemoteUser.normalizeUsername(raw.optString("username", ""));
            String role = raw.optString("role", "").trim();
            String status = raw.optString("status", RemoteUser.STATUS_ACTIVE).trim();
            String passwordHash = raw.optString("passwordHash", "");

            if (id.isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".id", "User id is required.", "Provide unique user id.")); recordValid = false; }
            if (!id.isEmpty() && !seenIds.add(id)) { plan.addIssue(ValidationIssue.error("DUPLICATE_ID", ref + ".id", "Duplicate user id: " + id, "Use unique user ids.")); recordValid = false; }
            if (username.isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".username", "Username is required.", "Provide a username.")); recordValid = false; }
            else if (!RemoteUser.isValidUsername(username)) { plan.addIssue(ValidationIssue.error("INVALID_USERNAME", ref + ".username", "Invalid username: " + username, "Use 3-32 chars: a-z, 0-9, . _ -.")); recordValid = false; }
            if (!username.isEmpty() && !seenUsernames.add(username)) { plan.addIssue(ValidationIssue.error("DUPLICATE_USERNAME", ref + ".username", "Duplicate username: " + username, "Use unique usernames.")); recordValid = false; }
            if (role.isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".role", "Role is required.", "Provide a valid role.")); recordValid = false; }
            if (!status.equals(RemoteUser.STATUS_ACTIVE) && !status.equals(RemoteUser.STATUS_SUSPENDED) && !status.equals(RemoteUser.STATUS_REVOKED)) {
                plan.addIssue(ValidationIssue.error("INVALID_STATUS", ref + ".status", "Invalid account state: " + status, "Use active, suspended, or revoked."));
                recordValid = false;
            }
            if (passwordHash.isEmpty()) {
                plan.addIssue(ValidationIssue.error("SENSITIVE_FIELD_REQUIRED", ref + ".passwordHash", "passwordHash is required; plaintext passwords are not accepted.", "Provide a hashed password field only."));
                recordValid = false;
            }
            if (raw.has("password") || raw.has("plainPassword")) {
                plan.addIssue(ValidationIssue.error("PLAINTEXT_SECRET", ref, "Plaintext password fields are not allowed.", "Remove plaintext password and provide passwordHash."));
                recordValid = false;
            }
            Iterator<String> keys = raw.keys();
            Set<String> allowed = allowedUserFields();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!allowed.contains(k)) {
                    plan.addIssue(ValidationIssue.error("UNKNOWN_FIELD", ref + "." + k, "Unknown user field: " + k, "Remove unknown field."));
                    recordValid = false;
                }
            }

            ImportRecordAction action = ImportRecordAction.REJECT;
            if (recordValid) {
                boolean exists = existingUserIds.contains(id) || existingUsernames.contains(username);
                action = resolveAction(strategy, exists, ref, plan);
            }
            if (!recordValid) action = ImportRecordAction.REJECT;
            plan.addRecord(raw, action);
        }
        return plan;
    }

    private static Set<String> allowedUserFields() {
        Set<String> s = new HashSet<>();
        s.add("id"); s.add("username"); s.add("displayName"); s.add("role"); s.add("passwordHash");
        s.add("status"); s.add("createdAt"); s.add("updatedAt"); s.add("lastLoginAt");
        s.add("failedAttempts"); s.add("lockedUntil"); s.add("grantedPermissions"); s.add("deniedPermissions");
        return s;
    }

    static ImportRecordAction resolveAction(String strategy, boolean exists, String ref, ImportPlan plan) {
        String s = strategy == null ? "merge" : strategy;
        if ("create_only".equals(s)) {
            if (exists) {
                plan.addIssue(ValidationIssue.warning("EXISTS_SKIP", ref, "Record exists and create_only selected.", "Use merge/update strategy to update existing records."));
                return ImportRecordAction.SKIP;
            }
            return ImportRecordAction.CREATE;
        }
        if ("update_only".equals(s)) {
            if (!exists) {
                plan.addIssue(ValidationIssue.warning("MISSING_SKIP", ref, "Record does not exist and update_only selected.", "Use create_only/merge strategy."));
                return ImportRecordAction.SKIP;
            }
            return ImportRecordAction.UPDATE;
        }
        return exists ? ImportRecordAction.UPDATE : ImportRecordAction.CREATE;
    }
}
