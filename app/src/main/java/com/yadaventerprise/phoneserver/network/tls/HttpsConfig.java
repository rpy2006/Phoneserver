package com.yadaventerprise.phoneserver.network.tls;

import java.net.URI;
import java.util.Locale;
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
        String configured = cleanUrl(publicUrl);
        if (!configured.isEmpty()) return configured;
        if (includeLocalIps && ip != null && !ip.isEmpty()) {
            return "https://" + ip + ":" + port;
        }
        return localBaseUrl(ip);
    }

    /**
     * Base URL for share links. Unlike {@link #qrBaseUrl(String)}, this never
     * falls back to the friendly hostname when a LAN address exists: this app
     * does not advertise phoneserver.local over mDNS, so such links fail with
     * DNS_PROBE_FINISHED_NXDOMAIN on the recipient's device.
     */
    public String shareBaseUrl(String ip) {
        String configured = cleanUrl(publicUrl);
        if (!configured.isEmpty()) return configured;
        if (ip != null && !ip.isEmpty()) return "https://" + ip + ":" + port;
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

    /**
     * Accepts only an HTTPS origin (https://host[:port]). Paths, queries and
     * fragments are rejected because the server mounts share links at the root:
     * a public URL such as https://example.com/base would generate
     * https://example.com/base/s/<token>, which is a 404 unless a proxy happens
     * to rewrite it. Keeping the value to an origin makes generated links
     * predictable and avoids silently broken share links.
     */
    public static String cleanUrl(String value) {
        if (value == null) return "";
        String url = value.trim();
        if (url.isEmpty()) return "";
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return "";
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "";
            if (uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) return "";
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) return "";
            int port = uri.getPort();
            if (port < -1 || port > 65535) return "";
            StringBuilder result = new StringBuilder("https://")
                    .append(host.toLowerCase(Locale.US));
            if (port > 0) result.append(':').append(port);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
