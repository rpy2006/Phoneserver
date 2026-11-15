package com.yadaventerprise.phoneserver.network.pairing;

import org.json.JSONObject;

public class PairingCode {
    public String id = "";
    public String hash = "";
    public String name = "";
    public long createdAt;
    public long expiresAt;
    public int attempts;
    public boolean used;

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("hash", hash);
        json.put("name", name);
        json.put("createdAt", createdAt);
        json.put("expiresAt", expiresAt);
        json.put("attempts", attempts);
        json.put("used", used);
        return json;
    }

    public static PairingCode fromJson(String id, JSONObject json) {
        PairingCode code = new PairingCode();
        code.id = id;
        code.hash = json.optString("hash", "");
        code.name = json.optString("name", "");
        code.createdAt = json.optLong("createdAt", 0);
        code.expiresAt = json.optLong("expiresAt", 0);
        code.attempts = json.optInt("attempts", 0);
        code.used = json.optBoolean("used", false);
        return code;
    }

    public boolean isActive(long now) {
        return !used && expiresAt > now && attempts < PairingStore.MAX_ATTEMPTS;
    }
}
