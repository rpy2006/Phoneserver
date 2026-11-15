package com.yadaventerprise.phoneserver.data.sharing;

import org.json.JSONObject;

public class ShareLink {

    public static final String TYPE_FILE = "file";
    public static final String TYPE_FOLDER = "folder";
    public static final String PERMISSION_VIEW = "view";
    public static final String PERMISSION_DOWNLOAD = "download";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_REVOKED = "revoked";

    public String id = "";
    public String name = "";
    public String rootPath = "";
    public String type = TYPE_FILE;
    public String permission = PERMISSION_DOWNLOAD;
    public String tokenHash = "";
    public String passwordHash = "";
    public long createdAt;
    public long updatedAt;
    public long expiresAt;
    public int maxDownloads;
    public int downloadCount;
    public boolean revoked;
    public long lastAccessAt;
    public String lastAccessIp = "";

    public boolean isPasswordProtected() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    public boolean isExpired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    public boolean isActive(long now) {
        return !revoked && !isExpired(now);
    }

    public boolean isDownloadLimitReached() {
        return maxDownloads > 0 && downloadCount >= maxDownloads;
    }

    public boolean canDownload() {
        return PERMISSION_DOWNLOAD.equals(permission);
    }

    public int remainingDownloads() {
        if (maxDownloads <= 0) return -1;
        return Math.max(0, maxDownloads - downloadCount);
    }

    public String status(long now) {
        if (revoked) return STATUS_REVOKED;
        if (isExpired(now)) return STATUS_EXPIRED;
        return STATUS_ACTIVE;
    }

    public ShareLink copy() {
        ShareLink copy = new ShareLink();
        copy.id = id;
        copy.name = name;
        copy.rootPath = rootPath;
        copy.type = type;
        copy.permission = permission;
        copy.tokenHash = tokenHash;
        copy.passwordHash = passwordHash;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.expiresAt = expiresAt;
        copy.maxDownloads = maxDownloads;
        copy.downloadCount = downloadCount;
        copy.revoked = revoked;
        copy.lastAccessAt = lastAccessAt;
        copy.lastAccessIp = lastAccessIp;
        return copy;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id == null ? "" : id);
            json.put("name", name == null ? "" : name);
            json.put("rootPath", rootPath == null ? "" : rootPath);
            json.put("type", TYPE_FOLDER.equals(type) ? TYPE_FOLDER : TYPE_FILE);
            json.put("permission", PERMISSION_VIEW.equals(permission) ? PERMISSION_VIEW : PERMISSION_DOWNLOAD);
            json.put("tokenHash", tokenHash == null ? "" : tokenHash);
            json.put("passwordHash", passwordHash == null ? "" : passwordHash);
            json.put("createdAt", createdAt);
            json.put("updatedAt", updatedAt);
            json.put("expiresAt", expiresAt);
            json.put("maxDownloads", maxDownloads);
            json.put("downloadCount", downloadCount);
            json.put("revoked", revoked);
            json.put("lastAccessAt", lastAccessAt);
            json.put("lastAccessIp", lastAccessIp == null ? "" : lastAccessIp);
        } catch (Exception ignored) {
        }
        return json;
    }

    public static ShareLink fromJson(JSONObject json) {
        ShareLink link = new ShareLink();
        if (json == null) return link;
        link.id = json.optString("id", "");
        link.name = json.optString("name", "");
        link.rootPath = json.optString("rootPath", "");
        link.type = TYPE_FOLDER.equals(json.optString("type")) ? TYPE_FOLDER : TYPE_FILE;
        link.permission = PERMISSION_VIEW.equals(json.optString("permission"))
                ? PERMISSION_VIEW : PERMISSION_DOWNLOAD;
        link.tokenHash = json.optString("tokenHash", "");
        link.passwordHash = json.optString("passwordHash", "");
        link.createdAt = json.optLong("createdAt", 0);
        link.updatedAt = json.optLong("updatedAt", 0);
        link.expiresAt = json.optLong("expiresAt", 0);
        link.maxDownloads = Math.max(0, json.optInt("maxDownloads", 0));
        link.downloadCount = Math.max(0, json.optInt("downloadCount", 0));
        link.revoked = json.optBoolean("revoked", false);
        link.lastAccessAt = json.optLong("lastAccessAt", 0);
        link.lastAccessIp = json.optString("lastAccessIp", "");
        return link;
    }
}
