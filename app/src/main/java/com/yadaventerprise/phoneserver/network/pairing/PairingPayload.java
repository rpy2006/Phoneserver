package com.yadaventerprise.phoneserver.network.pairing;

import org.json.JSONObject;

public final class PairingPayload {

    public String baseUrl = "";
    public String fingerprint = "";
    public String code = "";
    public String name = "PhoneServer";
    public boolean pairing;

    private PairingPayload() {
    }

    public static String build(String baseUrl, String fingerprint, String code, String name) {
        try {
            JSONObject json = new JSONObject();
            json.put("v", 2);
            json.put("type", "phoneserver");
            json.put("url", baseUrl == null ? "" : baseUrl);
            json.put("fingerprint", fingerprint == null ? "" : fingerprint);
            json.put("pairingCode", code == null ? "" : code);
            json.put("name", name == null ? "PhoneServer" : name);
            return json.toString();
        } catch (Exception e) {
            return baseUrl == null ? "" : baseUrl;
        }
    }

    public static PairingPayload parse(String value) {
        if (value == null) return null;
        String text = value.trim();
        PairingPayload payload = new PairingPayload();
        if (text.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(text);
                if (!"phoneserver".equals(json.optString("type", ""))) return null;
                payload.baseUrl = json.optString("url", "");
                payload.fingerprint = json.optString("fingerprint", "");
                payload.code = json.optString("pairingCode", "");
                payload.name = json.optString("name", "PhoneServer");
                payload.pairing = !payload.code.isEmpty();
                return payload;
            } catch (Exception e) {
                return null;
            }
        }
        try {
            java.net.URI uri = new java.net.URI(text);
            if (uri.getHost() == null) return null;
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(java.util.Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            payload.baseUrl = text;
            payload.name = uri.getHost();
            payload.pairing = false;
            return payload;
        } catch (Exception e) {
            return null;
        }
    }
}
