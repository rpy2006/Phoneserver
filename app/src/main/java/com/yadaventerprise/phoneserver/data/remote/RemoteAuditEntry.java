package com.yadaventerprise.phoneserver.data.remote;

import com.yadaventerprise.phoneserver.data.system.SystemLogSanitizer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONObject;

public class RemoteAuditEntry {
    public static final String AUTH_LOGIN = "remote.auth.login";
    public static final String AUTH_LOGOUT = "remote.auth.logout";
    public static final String AUTH_FAILED = "remote.auth.failed";
    public static final String PAIRING_CREATED = "remote.pairing.created";
    public static final String PAIRING_APPROVED = "remote.pairing.approved";
    public static final String PAIRING_REJECTED = "remote.pairing.rejected";
    public static final String PAIRING_CONSUMED = "remote.pairing.consumed";
    public static final String DEVICE_REGISTERED = "remote.device.registered";
    public static final String DEVICE_UPDATED = "remote.device.updated";
    public static final String DEVICE_REVOKED = "remote.device.revoked";
    public static final String DEVICE_HEARTBEAT = "remote.device.heartbeat";
    public static final String USER_CREATED = "remote.user.created";
    public static final String USER_UPDATED = "remote.user.updated";
    public static final String USER_DELETED = "remote.user.deleted";
    public static final String PERMISSIONS_CHANGED = "remote.permissions.changed";
    public static final String SETTINGS_CHANGED = "remote.settings.changed";
    public static final String SERVER_CONTROL = "remote.server.control";
    public static final String FILE_LISTED = "remote.files.listed";
    public static final String FILE_DOWNLOADED = "remote.files.downloaded";
    public static final String FILE_UPLOADED = "remote.files.uploaded";
    public static final String FILE_DELETED = "remote.files.deleted";
    public static final String FILE_UPDATED = "remote.files.updated";
    public static final String ACCESS_DENIED = "remote.access.denied";

    public long timestamp;
    public String event = "";
    public String actor = "";
    public String subject = "";
    public String ip = "";
    public String details = "";
    public boolean allowed = true;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        RemoteJson.put(json, "timestamp", timestamp);
        RemoteJson.put(json, "time", iso(timestamp));
        RemoteJson.put(json, "event", event);
        RemoteJson.put(json, "actor", actor);
        RemoteJson.put(json, "subject", subject);
        RemoteJson.put(json, "ip", ip);
        RemoteJson.put(json, "details", details);
        RemoteJson.put(json, "allowed", allowed);
        return json;
    }

    public static RemoteAuditEntry fromJson(JSONObject json) {
        RemoteAuditEntry entry = new RemoteAuditEntry();
        if (json == null) return entry;
        entry.timestamp = json.optLong("timestamp", 0);
        entry.event = json.optString("event", "");
        entry.actor = json.optString("actor", "");
        entry.subject = json.optString("subject", "");
        entry.ip = json.optString("ip", "");
        entry.details = json.optString("details", "");
        entry.allowed = json.optBoolean("allowed", true);
        return entry;
    }

    public static String clean(String value) {
        return SystemLogSanitizer.sanitize(value == null ? "" : value);
    }

    private static String iso(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestamp));
    }
}
