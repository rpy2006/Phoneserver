package com.yadaventerprise.phoneserver.data.remote;

import com.yadaventerprise.phoneserver.data.sharing.SharePassword;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class RemoteUser {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_REVOKED = "revoked";

    public String id = "";
    public String username = "";
    public String displayName = "";
    public String role = RemoteRole.VIEWER;
    public String passwordHash = "";
    public String status = STATUS_ACTIVE;
    public long createdAt;
    public long updatedAt;
    public long lastLoginAt;
    public int failedAttempts;
    public long lockedUntil;
    public final Set<String> grantedPermissions = new LinkedHashSet<>();
    public final Set<String> deniedPermissions = new LinkedHashSet<>();

    public static String normalizeUsername(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    public static boolean isValidUsername(String value) {
        String normalized = normalizeUsername(value);
        if (normalized.length() < 3 || normalized.length() > 32) return false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!valid) return false;
        }
        return true;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status) && lockedUntil <= System.currentTimeMillis();
    }

    public boolean isOwner() {
        return RemoteRole.OWNER.equals(role);
    }

    public boolean hasPermission(String permission) {
        if (!RemotePermissions.isValid(permission)) return false;
        if (isOwner()) return true;
        if (deniedPermissions.contains(permission)) return false;
        if (grantedPermissions.contains(permission)) return true;
        return RemotePermissions.forRole(role).contains(permission);
    }

    public boolean verifyPassword(String password) {
        return password != null && !passwordHash.isEmpty() && SharePassword.verify(password, passwordHash);
    }

    public RemoteUser copy() {
        return fromJson(toJson());
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        RemoteJson.put(json, "id", id);
        RemoteJson.put(json, "username", username);
        RemoteJson.put(json, "displayName", displayName);
        RemoteJson.put(json, "role", role);
        RemoteJson.put(json, "passwordHash", passwordHash);
        RemoteJson.put(json, "status", status);
        RemoteJson.put(json, "createdAt", createdAt);
        RemoteJson.put(json, "updatedAt", updatedAt);
        RemoteJson.put(json, "lastLoginAt", lastLoginAt);
        RemoteJson.put(json, "failedAttempts", failedAttempts);
        RemoteJson.put(json, "lockedUntil", lockedUntil);
        RemoteJson.put(json, "grantedPermissions", new JSONArray(grantedPermissions));
        RemoteJson.put(json, "deniedPermissions", new JSONArray(deniedPermissions));
        return json;
    }

    public static RemoteUser fromJson(JSONObject json) {
        RemoteUser user = new RemoteUser();
        if (json == null) return user;
        user.id = json.optString("id", "");
        user.username = normalizeUsername(json.optString("username", ""));
        user.displayName = json.optString("displayName", "");
        user.role = RemoteRole.normalize(json.optString("role", RemoteRole.VIEWER));
        user.passwordHash = json.optString("passwordHash", "");
        String status = json.optString("status", STATUS_ACTIVE);
        user.status = STATUS_ACTIVE.equals(status) || STATUS_SUSPENDED.equals(status)
                || STATUS_REVOKED.equals(status) ? status : STATUS_ACTIVE;
        user.createdAt = json.optLong("createdAt", 0);
        user.updatedAt = json.optLong("updatedAt", 0);
        user.lastLoginAt = json.optLong("lastLoginAt", 0);
        user.failedAttempts = Math.max(0, json.optInt("failedAttempts", 0));
        user.lockedUntil = Math.max(0, json.optLong("lockedUntil", 0));
        readPermissions(json.optJSONArray("grantedPermissions"), user.grantedPermissions);
        readPermissions(json.optJSONArray("deniedPermissions"), user.deniedPermissions);
        return user;
    }

    private static void readPermissions(JSONArray array, Set<String> target) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String permission = array.optString(i, "");
            if (RemotePermissions.isValid(permission)) target.add(permission);
        }
    }
}
