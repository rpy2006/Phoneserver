package com.yadaventerprise.phoneserver.data.sharing;

import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.network.tls.TlsCertificateStore;
import com.yadaventerprise.phoneserver.network.tls.TlsIdentity;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.util.NetworkUtils;

public final class ShareUrlBuilder {

    private ShareUrlBuilder() {
    }

    public static Result build(android.content.Context context, String token) {
        HttpsConfig https = HttpsConfigStore.get(context).get();
        String ip = NetworkUtils.getLocalIpAddress();
        boolean httpsRunning = https.enabled && ServerService.isHttpsRunning();
        boolean trustedCertificate = false;
        if (httpsRunning) {
            TlsIdentity identity = TlsCertificateStore.get(context).load();
            trustedCertificate = identity != null && !identity.isSelfSigned();
        }
        return build(https.publicUrl, ip, https.port, ServerService.PORT,
                https.enabled, httpsRunning, trustedCertificate,
                ServerService.isServerRunning(), token);
    }

    static Result build(String publicUrl, String ip, int httpsPort, int httpPort,
                        boolean httpsEnabled, boolean httpsRunning,
                        boolean trustedCertificate, boolean serverRunning, String token) {
        String origin = HttpsConfig.cleanUrl(publicUrl);
        boolean hasIp = ip != null && !ip.isEmpty();
        String host = hasIp ? ip : "localhost";
        String base;
        boolean secure = false;
        String warning = "";

        if (httpsEnabled && httpsRunning && !origin.isEmpty()) {
            base = origin;
            secure = true;
        } else if (httpsEnabled && httpsRunning && trustedCertificate && hasIp) {
            base = "https://" + host + ":" + httpsPort;
            secure = true;
        } else {
            base = "http://" + host + ":" + httpPort;
            if (!httpsEnabled) {
                warning = "This link uses HTTP and is not encrypted. Enable HTTPS with a "
                        + "public URL or a CA-signed certificate before sharing outside "
                        + "your local network.";
            } else if (!httpsRunning) {
                warning = "HTTPS is enabled but the secure server is not running, so this "
                        + "link uses HTTP. Start the server and create a fresh link to "
                        + "share it over HTTPS.";
            } else if (!trustedCertificate) {
                warning = "HTTPS is enabled but only a self-signed certificate is available, "
                        + "which browsers reject. This local link uses HTTP. Set a public "
                        + "HTTPS URL or import a CA-signed certificate to use HTTPS.";
            } else {
                warning = "No Wi-Fi/LAN address was found, so this link uses HTTP. "
                        + "Connect to Wi-Fi or set a public HTTPS URL.";
            }
        }

        if (!serverRunning) {
            warning = join(warning, "The server is not running.");
        }
        return new Result(trimSlash(base) + "/s/" + token, secure, warning);
    }

    private static String join(String first, String second) {
        return first == null || first.isEmpty() ? second : first + " " + second;
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
