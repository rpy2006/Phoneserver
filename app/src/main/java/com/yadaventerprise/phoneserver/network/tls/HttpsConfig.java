package com.yadaventerprise.phoneserver.network.tls;

import org.json.JSONObject;

public class HttpsConfig {
    public static final int DEFAULT_PORT = 8443;

    public boolean enabled;
    public int port = DEFAULT_PORT;
    public String hostname = "phoneserver.local";
    public String publicUrl = "";
    public boolean includeLocalIps = true;
    public long updatedAt;

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("enabled", enabled);
        json.put("port", port);
        json.put("hostname", hostname == null ? "" : hostname);
        json.put("publicUrl", publicUrl == null ? "" : publicUrl);
        json.put("includeLocalIps", includeLocalIps);
        json.put("updatedAt", updatedAt);
        return json;
    }

    public static HttpsConfig fromJson(JSONObject json) {
        HttpsConfig config = new HttpsConfig();
        config.enabled = json.optBoolean("enabled", false);
        config.port = clampPort(json.optInt("port", DEFAULT_PORT));
        config.hostname = cleanHostname(json.optString("hostname", "phoneserver.local"));
        config.publicUrl = cleanUrl(json.optString("publicUrl", ""));
        config.includeLocalIps = json.optBoolean("includeLocalIps", true);
        config.updatedAt = json.optLong("updatedAt", 0);
        return config;
    }

    public String localBaseUrl(String ip) {
        String host = hostname == null || hostname.isEmpty()
                ? (ip == null || ip.isEmpty() ? "localhost" : ip)
                : hostname;
        return "https://" + host + ":" + port;
    }

    public String qrBaseUrl(String ip) {
        if (publicUrl != null && !publicUrl.isEmpty()) return publicUrl;
        if (includeLocalIps && ip != null && !ip.isEmpty()) {
            return "https://" + ip + ":" + port;
        }
        return localBaseUrl(ip);
    }

    public static int clampPort(int value) {
        if (value < 1024 || value > 65535) return DEFAULT_PORT;
        return value;
    }

    public static String cleanHostname(String value) {
        if (value == null) return "phoneserver.local";
        String host = value.trim().toLowerCase(java.util.Locale.US);
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) host = host.substring(8);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (!host.matches("[a-z0-9][a-z0-9.\\-]{0,252}")) return "phoneserver.local";
        return host;
    }

    public static String cleanUrl(String value) {
        if (value == null) return "";
        String url = value.trim();
        if (url.isEmpty()) return "";
        if (!url.startsWith("https://")) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
