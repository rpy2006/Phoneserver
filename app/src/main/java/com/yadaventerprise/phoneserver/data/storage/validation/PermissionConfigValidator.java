package com.yadaventerprise.phoneserver.data.storage.validation;

import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PermissionConfigValidator {
    private PermissionConfigValidator() {}

    public static ImportPlan validate(JSONArray policies, Set<String> validUserIds, Set<String> existingPolicyIds, String strategy) {
        ImportPlan plan = new ImportPlan("permissions");
        if (policies == null) {
            plan.addIssue(ValidationIssue.error("PERMISSIONS_REQUIRED", "permissions", "Permissions array is required.", "Provide permissions array."));
            return plan;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < policies.length(); i++) {
            JSONObject raw = policies.optJSONObject(i);
            String ref = "permissions[" + i + "]";
            if (raw == null) {
                plan.addIssue(ValidationIssue.error("INVALID_RECORD", ref, "Permission record must be object.", "Provide object."));
                plan.addRecord(new JSONObject(), ImportRecordAction.REJECT);
                continue;
            }
            boolean ok = true;
            String id = raw.optString("tokenId", raw.optString("id", "")).trim();
            String role = raw.optString("role", "").trim();
            if (id.isEmpty()) { plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".tokenId", "tokenId/id is required.", "Provide token or policy identifier.")); ok = false; }
            if (!id.isEmpty() && !seen.add(id)) { plan.addIssue(ValidationIssue.error("DUPLICATE_ID", ref + ".tokenId", "Duplicate permission identifier: " + id, "Use unique identifier.")); ok = false; }
            if (!TokenPolicy.isValidRole(role)) { plan.addIssue(ValidationIssue.error("INVALID_ROLE", ref + ".role", "Invalid role: " + role, "Use viewer, editor, or admin.")); ok = false; }
            if (raw.has("userId")) {
                String userId = raw.optString("userId", "").trim();
                if (!userId.isEmpty() && !validUserIds.contains(userId)) {
                    plan.addIssue(ValidationIssue.error("INVALID_USER_REFERENCE", ref + ".userId", "Unknown userId: " + userId, "Import or reference an existing user."));
                    ok = false;
                }
            }
            JSONArray perms = raw.optJSONArray("permissions");
            if (perms == null) {
                plan.addIssue(ValidationIssue.error("FIELD_REQUIRED", ref + ".permissions", "permissions array is required.", "Provide permissions array."));
                ok = false;
            } else {
                for (int p = 0; p < perms.length(); p++) {
                    String perm = perms.optString(p, "");
                    if (!TokenPolicy.allPermissions().contains(perm)) {
                        plan.addIssue(ValidationIssue.error("INVALID_PERMISSION", ref + ".permissions[" + p + "]", "Unsupported permission: " + perm, "Use a supported permission identifier."));
                        ok = false;
                    }
                }
                if (!TokenPolicy.ROLE_ADMIN.equals(role) && containsAdminEscalation(perms)) {
                    plan.addIssue(ValidationIssue.error("PRIVILEGE_ESCALATION", ref + ".permissions", "Non-admin role cannot include tokens.manage/system admin escalation permissions.", "Remove admin-only permissions or assign admin role explicitly."));
                    ok = false;
                }
            }
            if (TokenPolicy.ROLE_ADMIN.equals(role) && raw.has("protected") && raw.optBoolean("protected", false) && "replace".equals(strategy)) {
                plan.addIssue(ValidationIssue.warning("ADMIN_PROTECTED", ref, "Protected admin policy cannot be replaced automatically.", "Use merge/update with explicit admin confirmation."));
            }
            Set<String> allowed = allowedFields();
            Iterator<String> keys = raw.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!allowed.contains(k)) {
                    plan.addIssue(ValidationIssue.error("UNKNOWN_FIELD", ref + "." + k, "Unknown permission field: " + k, "Remove unknown field."));
                    ok = false;
                }
            }
            ImportRecordAction action = ok ? UserConfigValidator.resolveAction(strategy, existingPolicyIds.contains(id), ref, plan) : ImportRecordAction.REJECT;
            plan.addRecord(raw, action);
        }
        return plan;
    }

    private static boolean containsAdminEscalation(JSONArray perms) {
        for (int i = 0; i < perms.length(); i++) {
            String p = perms.optString(i, "");
            if (TokenPolicy.TOKENS_MANAGE.equals(p)) return true;
        }
        return false;
    }

    private static Set<String> allowedFields() {
        Set<String> s = new HashSet<>();
        s.add("tokenId"); s.add("id"); s.add("userId"); s.add("role"); s.add("permissions");
        s.add("rateLimitPerMinute"); s.add("expiresAt"); s.add("updatedAt"); s.add("protected");
        return s;
    }
}
