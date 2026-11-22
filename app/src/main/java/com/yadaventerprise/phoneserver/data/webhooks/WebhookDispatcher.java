package com.yadaventerprise.phoneserver.data.webhooks;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.system.SystemLogSanitizer;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public final class WebhookDispatcher {

    public static final String EVENT_FILE_UPLOADED = "file.uploaded";
    public static final String EVENT_FILE_DOWNLOADED = "file.downloaded";
    public static final String EVENT_SHARE_CREATED = "share.created";
    public static final String EVENT_SERVER_STARTED = "server.started";
    public static final String EVENT_SERVER_STOPPED = "server.stopped";
    public static final String EVENT_SERVER_FAILURE = "server.failure";
    public static final String EVENT_SERVER_RECOVERED = "server.recovered";
    public static final String EVENT_AD_STATE_CHANGED = "ad.state.changed";
    public static final String EVENT_AUTH_FAILURE = "auth.failure";
    public static final String EVENT_SUSPICIOUS = "suspicious.request";
    public static final String EVENT_BACKUP_COMPLETED = "backup.completed";
    public static final String EVENT_STORAGE_WARNING = "storage.warning";
    public static final String EVENT_MAINTENANCE_COMPLETED = "maintenance.completed";
    public static final String EVENT_TASK_COMPLETED = "task.completed";
    public static final String EVENT_TASK_FAILED = "task.failed";

    public static final String[] ALL_EVENTS = new String[]{
            EVENT_FILE_UPLOADED,
            EVENT_FILE_DOWNLOADED,
            EVENT_SHARE_CREATED,
            EVENT_SERVER_STARTED,
            EVENT_SERVER_STOPPED,
            EVENT_SERVER_FAILURE,
            EVENT_SERVER_RECOVERED,
            EVENT_AD_STATE_CHANGED,
            EVENT_AUTH_FAILURE,
            EVENT_SUSPICIOUS,
            EVENT_BACKUP_COMPLETED,
            EVENT_STORAGE_WARNING,
            EVENT_MAINTENANCE_COMPLETED,
            EVENT_TASK_COMPLETED,
            EVENT_TASK_FAILED
    };

    private static final String USER_AGENT = "PhoneServer-Webhook/1.0";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PhoneServer-Webhook");
        thread.setDaemon(true);
        return thread;
    });
    private static final long REPLAY_SKEW_MS = 5L * 60L * 1000L;

    private WebhookDispatcher() {
    }

    public static void emit(Context context, String event, JSONObject data) {
        if (context == null || event == null) return;
        Context app = context.getApplicationContext();
        List<WebhookConfig> hooks = WebhookStore.get(app).all();
        for (WebhookConfig hook : hooks) {
            if (!hook.matches(event)) continue;
            WebhookConfig snapshot = WebhookConfig.copy(hook);
            JSONObject payload = payload(event, data);
            EXECUTOR.execute(() -> deliver(app, snapshot, event, payload));
        }
    }

    public static WebhookHistoryEntry deliverNow(Context context, WebhookConfig config,
                                                  String event, JSONObject data) {
        if (context == null || config == null) return null;
        String eventName = event == null ? "test" : event;
        return deliver(context.getApplicationContext(), WebhookConfig.copy(config),
                eventName, payload(eventName, data));
    }

    private static JSONObject payload(String event, JSONObject data) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("event", event);
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("server", "PhoneServer");
            payload.put("data", data == null ? new JSONObject() : data);
            return payload;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static WebhookHistoryEntry deliver(Context context, WebhookConfig config,
                                                String event, JSONObject payload) {
        WebhookHistoryEntry entry = new WebhookHistoryEntry();
        entry.webhookId = config.id;
        entry.webhookName = config.name;
        entry.event = event;
        entry.url = SystemLogSanitizer.sanitize(config.url);
        entry.createdAt = System.currentTimeMillis();
        String method = config.method == null ? "POST" : config.method.trim().toUpperCase(Locale.US);
        boolean sendBody = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        String body = payload.toString();
        long started = System.currentTimeMillis();
        String lastError = "";
        int lastCode = 0;
        int attempts = Math.max(1, Math.min(5, config.maxAttempts));
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpURLConnection connection = null;
            entry.attempts = attempt;
            try {
                URL url = new URL(config.url);
                if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
                    throw new IllegalArgumentException("unsupported webhook URL scheme");
                }
                if (!isAllowedMethod(method)) {
                    throw new IllegalArgumentException("unsupported webhook method");
                }
                String deliveryId = java.util.UUID.randomUUID().toString();
                String timestamp = String.valueOf(System.currentTimeMillis());
                String signedBody = sendBody ? body : "";
                String signature = sign(timestamp + "." + deliveryId + "." + signedBody, config.secret);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(Math.max(1, config.timeoutSeconds) * 1000);
                connection.setReadTimeout(Math.max(1, config.timeoutSeconds) * 1000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json");
                applyHeaders(connection, config.headersJson, event, signature, timestamp, deliveryId);
                if (sendBody) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                lastCode = connection.getResponseCode();
                entry.responseCode = lastCode;
                if (lastCode >= 200 && lastCode < 300) {
                    entry.success = true;
                    entry.error = "";
                    entry.durationMs = System.currentTimeMillis() - started;
                    finish(context, config, entry);
                    return entry;
                }
                lastError = "HTTP " + lastCode;
            } catch (Exception e) {
                lastError = SystemLogSanitizer.sanitize(
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(attempt == 1 ? 1000L : 3000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        entry.success = false;
        entry.error = SystemLogSanitizer.sanitize(lastError);
        entry.durationMs = System.currentTimeMillis() - started;
        finish(context, config, entry);
        return entry;
    }

    private static boolean isAllowedMethod(String method) {
        return method.equals("GET") || method.equals("POST") || method.equals("PUT")
                || method.equals("PATCH") || method.equals("DELETE");
    }

    private static void applyHeaders(HttpURLConnection connection, String headersJson,
                                     String event, String signature, String timestamp, String deliveryId) throws Exception {
        connection.setRequestProperty("X-PhoneServer-Event", event);
        if (timestamp != null) connection.setRequestProperty("X-PhoneServer-Timestamp", timestamp);
        if (deliveryId != null) connection.setRequestProperty("X-PhoneServer-Delivery-Id", deliveryId);
        if (!signature.isEmpty()) {
            connection.setRequestProperty("X-PhoneServer-Signature", "sha256=" + signature);
        }
        if (headersJson == null || headersJson.trim().isEmpty()) return;
        JSONObject headers = new JSONObject(headersJson);
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (!name.matches("[A-Za-z0-9-]+")) continue;
            String lower = name.toLowerCase(Locale.US);
            if (lower.equals("authorization") || lower.equals("cookie")
                    || lower.equals("x-auth-password") || lower.equals("x-phoneserver-signature")
                    || lower.equals("x-phoneserver-timestamp") || lower.equals("x-phoneserver-delivery-id")
                    || lower.equals("host") || lower.equals("content-length")) continue;
            connection.setRequestProperty(name, String.valueOf(headers.opt(name)));
        }
    }

    public static boolean verifySignature(String secret, String timestamp, String deliveryId, String body, String signatureHeader) {
        if (secret == null || secret.isEmpty() || timestamp == null || deliveryId == null || body == null || signatureHeader == null) return false;
        String expected = sign(timestamp + "." + deliveryId + "." + body, secret);
        if (expected.isEmpty()) return false;
        String provided = signatureHeader.startsWith("sha256=") ? signatureHeader.substring("sha256=".length()) : signatureHeader;
        return constantTimeEquals(expected, provided);
    }

    public static boolean isReplay(Context context, String deliveryId, long timestampMs, long nowMs) {
        if (context == null) return true;
        return !WebhookReplayStore.get(context.getApplicationContext()).checkAndRecord(deliveryId, timestampMs, nowMs);
    }

    public static boolean verifyIncoming(Context context, String secret, String timestamp, String deliveryId, String body, String signatureHeader, long nowMs) {
        long ts;
        try { ts = Long.parseLong(timestamp == null ? "" : timestamp.trim()); } catch (Exception e) { return false; }
        if (Math.abs(nowMs - ts) > REPLAY_SKEW_MS) return false;
        if (!verifySignature(secret, timestamp, deliveryId, body, signatureHeader)) return false;
        return !isReplay(context, deliveryId, ts, nowMs);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }

    private static void finish(Context context, WebhookConfig config, WebhookHistoryEntry entry) {
        try {
            WebhookStore.get(context).recordDelivery(config.id, entry.success,
                    entry.responseCode, entry.error);
            WebhookHistoryStore.get(context).append(entry);
        } catch (Exception e) {
            Log.w("WebhookDispatcher", "could not record webhook result", e);
        }
    }

    private static String sign(String body, String secret) {
        if (secret == null || secret.isEmpty()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
