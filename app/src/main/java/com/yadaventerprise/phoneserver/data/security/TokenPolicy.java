package com.yadaventerprise.phoneserver.data.security;

import com.yadaventerprise.phoneserver.data.database.Records;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Role and permission policy attached to an API token by id. */
public class TokenPolicy {

    public static final String ROLE_VIEWER = "viewer";
    public static final String ROLE_EDITOR = "editor";
    public static final String ROLE_ADMIN = "admin";

    public static final String PROJECTS_READ = "projects.read";
    public static final String PROJECTS_WRITE = "projects.write";
    public static final String DATABASES_READ = "databases.read";
    public static final String DATABASES_WRITE = "databases.write";
    public static final String FILES_READ = "files.read";
    public static final String FILES_WRITE = "files.write";
    public static final String BACKUPS_READ = "backups.read";
    public static final String BACKUPS_WRITE = "backups.write";
    public static final String SYSTEM_READ = "system.read";
    public static final String TOKENS_MANAGE = "tokens.manage";
    public static final String MAIL_READ = "mail.read";
    public static final String MAIL_WRITE = "mail.write";

    private static final Set<String> ALL_PERMISSIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(PROJECTS_READ, PROJECTS_WRITE, DATABASES_READ,
                    DATABASES_WRITE,                     FILES_READ, FILES_WRITE, BACKUPS_READ, BACKUPS_WRITE,
                    SYSTEM_READ, TOKENS_MANAGE, MAIL_READ, MAIL_WRITE)));

    public String tokenId = "";
    public String role = ROLE_VIEWER;
    public final Set<String> permissions = new LinkedHashSet<>();
    /** 0 means only the global per-IP limit applies. */
    public int rateLimitPerMinute;
    /** 0 means no expiry. */
    public long expiresAt;
    public long updatedAt;

    public static Set<String> allPermissions() {
        return ALL_PERMISSIONS;
    }

    public static boolean isValidRole(String value) {
        return ROLE_VIEWER.equals(value) || ROLE_EDITOR.equals(value) || ROLE_ADMIN.equals(value);
    }

    public static Set<String> permissionsForRole(String role) {
        Set<String> result = new LinkedHashSet<>();
        if (ROLE_ADMIN.equals(role)) {
            result.addAll(ALL_PERMISSIONS);
            return result;
        }
        result.add(PROJECTS_READ);
        result.add(DATABASES_READ);
        result.add(FILES_READ);
        result.add(BACKUPS_READ);
        result.add(MAIL_READ);
        if (ROLE_EDITOR.equals(role)) {
            result.add(PROJECTS_WRITE);
            result.add(DATABASES_WRITE);
            result.add(FILES_WRITE);
            result.add(BACKUPS_WRITE);
            result.add(MAIL_WRITE);
        }
        return result;
    }

    public static TokenPolicy preset(String role) {
        TokenPolicy policy = new TokenPolicy();
        policy.role = isValidRole(role) ? role : ROLE_VIEWER;
        policy.permissions.addAll(permissionsForRole(policy.role));
        return policy;
    }

    public static TokenPolicy defaultFor(Records.ApiTokenRecord record) {
        return preset(record != null && record.canWrite ? ROLE_EDITOR : ROLE_VIEWER);
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    public boolean can(String permission) {
        return isAdmin() || (permission != null && permissions.contains(permission));
    }

    public boolean canWrite() {
        if (isAdmin()) return true;
        for (String permission : permissions) {
            if (permission.endsWith(".write")) return true;
        }
        return false;
    }

    public boolean isExpired() {
        return expiresAt > 0 && expiresAt <= System.currentTimeMillis();
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("tokenId", tokenId);
        json.put("role", role);
        JSONArray array = new JSONArray();
        for (String permission : permissions) array.put(permission);
        json.put("permissions", array);
        json.put("rateLimitPerMinute", rateLimitPerMinute);
        json.put("expiresAt", expiresAt);
        json.put("updatedAt", updatedAt);
        return json;
    }

    public static TokenPolicy fromJson(JSONObject json) {
        TokenPolicy policy = new TokenPolicy();
        policy.tokenId = json.optString("tokenId", "");
        String role = json.optString("role", ROLE_VIEWER);
        policy.role = isValidRole(role) ? role : ROLE_VIEWER;
        JSONArray array = json.optJSONArray("permissions");
        if (array == null) {
            policy.permissions.addAll(permissionsForRole(policy.role));
        } else {
            for (int i = 0; i < array.length(); i++) {
                String permission = array.optString(i, "");
                if (ALL_PERMISSIONS.contains(permission)) policy.permissions.add(permission);
            }
        }
        policy.rateLimitPerMinute = clamp(json.optInt("rateLimitPerMinute", 0), 0, 6000);
        policy.expiresAt = Math.max(0, json.optLong("expiresAt", 0));
        policy.updatedAt = json.optLong("updatedAt", 0);
        return policy;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
