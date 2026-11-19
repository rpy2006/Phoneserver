package com.yadaventerprise.phoneserver.data.remote;

import org.json.JSONObject;

final class RemoteJson {
    private RemoteJson() {
    }

    static void put(JSONObject json, String key, Object value) {
        if (json == null || key == null) return;
        try {
            json.put(key, value);
        } catch (Exception ignored) {
        }
    }
}
