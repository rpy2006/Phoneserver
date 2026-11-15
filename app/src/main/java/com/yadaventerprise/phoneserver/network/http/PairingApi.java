package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.IntrusionDetector;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.security.TokenPolicyStore;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.network.pairing.PairingCode;
import com.yadaventerprise.phoneserver.network.pairing.PairingStore;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.network.tls.TlsCertificateStore;
import com.yadaventerprise.phoneserver.network.tls.TlsIdentity;
import com.yadaventerprise.phoneserver.security.RateLimiter;
import com.yadaventerprise.phoneserver.util.NetworkUtils;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

public final class PairingApi {

    public static final String PATH = "/api/pair";
    private static final long PAIR_TOKEN_DAYS = 30;
    private static final RateLimiter LIMITER = new RateLimiter(12, 60_000L);

    private PairingApi() {
    }

    public static boolean isPairingPath(String path) {
        return PATH.equals(path);
    }

    public static void handle(Context context, SimpleHttpServer.Request req, OutputStream out) {
        try {
            if (req.method == null || !req.method.toUpperCase(Locale.US).equals("POST")) {
                send(context, req, out, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!LIMITER.tryAcquire(req.remoteIp)) {
                IntrusionDetector.recordRateLimit(context, req.remoteIp, req.method, req.path);
                send(context, req, out, 429, "{\"error\":\"too many pairing attempts\"}");
                return;
            }
            JSONObject body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
            String supplied = body.optString("code", body.optString("pairingCode", ""));
            PairingCode code = PairingStore.get(context).redeem(supplied);
            if (code == null) {
                IntrusionDetector.recordAuthFailure(context, req.remoteIp, req.method, req.path,
                        "invalid pairing code");
                send(context, req, out, 401, "{\"error\":\"invalid or expired pairing code\"}");
                return;
            }

            String deviceName = body.optString("deviceName", "Paired device").trim();
            if (deviceName.isEmpty()) deviceName = "Paired device";
            if (deviceName.length() > 60) deviceName = deviceName.substring(0, 60);

            TokenStore.Created created = TokenStore.create(context,
                    "Paired: " + deviceName, null, true);
            TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_EDITOR);
            policy.tokenId = created.record.id;
            policy.expiresAt = System.currentTimeMillis() + PAIR_TOKEN_DAYS * 24L * 60L * 60L * 1000L;
            policy.rateLimitPerMinute = 240;
            TokenPolicyStore.get(context).save(policy);

            HttpsConfig config = HttpsConfigStore.get(context).get();
            TlsIdentity identity = TlsCertificateStore.get(context).load();
            String ip = NetworkUtils.getLocalIpAddress();
            String baseUrl = config.enabled
                    ? config.qrBaseUrl(ip)
                    : "http://" + (ip == null ? "localhost" : ip) + ":" + com.yadaventerprise.phoneserver.service.ServerService.PORT;

            JSONObject result = new JSONObject();
            result.put("token", created.token);
            result.put("tokenId", created.record.id);
            result.put("role", policy.role);
            result.put("expiresAt", policy.expiresAt);
            result.put("serverName", ContentStore.getUsername(context).isEmpty()
                    ? "PhoneServer" : ContentStore.getUsername(context));
            result.put("baseUrl", baseUrl);
            result.put("fingerprint", identity == null ? "" : identity.fingerprint);
            result.put("warning", "store this token now; it cannot be shown again");
            SimpleHttpServer.sendResponse(out, context, 201, "application/json",
                    result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            try {
                send(context, req, out, 400, "{\"error\":\"invalid pairing request\"}");
            } catch (Exception ignored) {
            }
        }
    }

    private static void send(Context context, SimpleHttpServer.Request req, OutputStream out,
                             int status, String body) throws Exception {
        SimpleHttpServer.sendResponse(out, context, status, "application/json",
                body.getBytes(StandardCharsets.UTF_8));
    }
}
