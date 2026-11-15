package com.yadaventerprise.phoneserver.data.sharing;

import org.json.JSONObject;

public class ShareSettings {

    public int defaultExpiryMinutes = 1440;
    public int defaultMaxDownloads;
    public String defaultPermission = ShareLink.PERMISSION_DOWNLOAD;
    public boolean requirePasswordByDefault;

    public ShareSettings copy() {
        ShareSettings copy = new ShareSettings();
        copy.defaultExpiryMinutes = defaultExpiryMinutes;
        copy.defaultMaxDownloads = defaultMaxDownloads;
        copy.defaultPermission = defaultPermission;
        copy.requirePasswordByDefault = requirePasswordByDefault;
        return copy;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("defaultExpiryMinutes", defaultExpiryMinutes);
            json.put("defaultMaxDownloads", defaultMaxDownloads);
            json.put("defaultPermission", defaultPermission);
            json.put("requirePasswordByDefault", requirePasswordByDefault);
        } catch (Exception ignored) {
        }
        return json;
    }

    public static ShareSettings fromJson(JSONObject json) {
        ShareSettings settings = new ShareSettings();
        if (json == null) return settings;
        settings.defaultExpiryMinutes = Math.max(0, json.optInt("defaultExpiryMinutes", 1440));
        settings.defaultMaxDownloads = Math.max(0, json.optInt("defaultMaxDownloads", 0));
        String permission = json.optString("defaultPermission", ShareLink.PERMISSION_DOWNLOAD);
        settings.defaultPermission = ShareLink.PERMISSION_VIEW.equals(permission)
                ? ShareLink.PERMISSION_VIEW : ShareLink.PERMISSION_DOWNLOAD;
        settings.requirePasswordByDefault = json.optBoolean("requirePasswordByDefault", false);
        return settings;
    }
}
