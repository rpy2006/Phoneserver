package com.yadaventerprise.phoneserver.data.sharing;

import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.util.NetworkUtils;

public final class ShareUrlBuilder {

    private ShareUrlBuilder() {
    }

    public static Result build(android.content.Context context, String token) {
        HttpsConfig https = HttpsConfigStore.get(context).get();
        String ip = NetworkUtils.getLocalIpAddress();
        String base;
        boolean secure = false;
        String warning;
        if (https.enabled) {
            base = https.qrBaseUrl(ip);
            secure = true;
            warning = ServerService.isServerRunning()
                    ? "" : "The server is not running. Start it before opening this link.";
        } else {
            String host = ip == null || ip.isEmpty() ? "localhost" : ip;
            base = "http://" + host + ":" + ServerService.PORT;
            warning = "This link uses HTTP and is not encrypted. Enable HTTPS before sharing outside your local network.";
            if (!ServerService.isServerRunning()) {
                warning = warning + " The server is not running.";
            }
        }
        String url = trimSlash(base) + "/s/" + token;
        return new Result(url, secure, warning);
    }

    static Result build(String httpsBase, String publicUrl, String ip, int httpPort,
                        boolean httpsEnabled, boolean serverRunning, String token) {
        String base;
        boolean secure = false;
        String warning;
        if (httpsEnabled) {
            if (publicUrl != null && !publicUrl.isEmpty()) {
                base = publicUrl;
            } else {
                String host = ip == null || ip.isEmpty() ? "phoneserver.local" : ip;
                base = "https://" + host + ":" + HttpsConfig.DEFAULT_PORT;
            }
            secure = true;
            warning = serverRunning ? "" : "The server is not running. Start it before opening this link.";
        } else {
            String host = ip == null || ip.isEmpty() ? "localhost" : ip;
            base = "http://" + host + ":" + httpPort;
            warning = "This link uses HTTP and is not encrypted. Enable HTTPS before sharing outside your local network.";
            if (!serverRunning) warning = warning + " The server is not running.";
        }
        return new Result(trimSlash(base) + "/s/" + token, secure, warning);
    }

    private static String trimSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static final class Result {
        public final String url;
        public final boolean secure;
        public final String warning;

        Result(String url, boolean secure, String warning) {
            this.url = url;
            this.secure = secure;
            this.warning = warning;
        }
    }
}
