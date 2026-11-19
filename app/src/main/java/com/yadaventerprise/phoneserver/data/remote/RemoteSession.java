package com.yadaventerprise.phoneserver.data.remote;

import java.util.LinkedHashSet;
import org.json.JSONObject;

public class RemoteSession {
    public static final String SUBJECT_USER = "user";
    public static final String SUBJECT_DEVICE = "device";

    public String id = "";
    public String tokenHash = "";
    public String subjectType = "";
    public String subjectId = "";
    public long createdAt;
    public long expiresAt;
    public long lastSeenAt;
    public boolean revoked;
    public String ip = "";
    public final LinkedHashSet<String> grantedPermissions = new LinkedHashSet<>();
    public final LinkedHashSet<String> deniedPermissions = new LinkedHashSet<>();

    public boolean isActive() {
        return !revoked && expiresAt > System.currentTimeMillis();
    }

    public RemoteSession copy() {
        return fromJson(toJson());
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        RemoteJson.put(json, "id", id);
        RemoteJson.put(json, "tokenHash", tokenHash);
        RemoteJson.put(json, "subjectType", subjectType);
        RemoteJson.put(json, "subjectId", subjectId);
        RemoteJson.put(json, "createdAt", createdAt);
        RemoteJson.put(json, "expiresAt", expiresAt);
        RemoteJson.put(json, "lastSeenAt", lastSeenAt);
        RemoteJson.put(json, "revoked", revoked);
        RemoteJson.put(json, "ip", ip);
        RemoteJson.put(json, "grantedPermissions", new org.json.JSONArray(grantedPermissions));
        RemoteJson.put(json, "deniedPermissions", new org.json.JSONArray(deniedPermissions));
        return json;
    }

    public static RemoteSession fromJson(JSONObject json) {
        RemoteSession session = new RemoteSession();
        if (json == null) return session;
        session.id = json.optString("id", "");
        session.tokenHash = json.optString("tokenHash", "");
        session.subjectType = json.optString("subjectType", "");
        session.subjectId = json.optString("subjectId", "");
        session.createdAt = json.optLong("createdAt", 0);
        session.expiresAt = json.optLong("expiresAt", 0);
        session.lastSeenAt = json.optLong("lastSeenAt", 0);
        session.revoked = json.optBoolean("revoked", false);
        session.ip = json.optString("ip", "");
        readPermissions(json.optJSONArray("grantedPermissions"), session.grantedPermissions);
        readPermissions(json.optJSONArray("deniedPermissions"), session.deniedPermissions);
        return session;
    }

    private static void readPermissions(org.json.JSONArray array, LinkedHashSet<String> target) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String permission = array.optString(i, "");
            if (RemotePermissions.isValid(permission)) target.add(permission);
        }
    }
}
