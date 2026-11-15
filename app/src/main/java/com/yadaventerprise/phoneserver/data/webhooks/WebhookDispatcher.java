package com.yadaventerprise.phoneserver.data.webhooks;

import android.content.Context;
import android.util.Log;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public final class WebhookDispatcher {

    public static final String EVENT_FILE_UPLOADED = "file.uploaded";
    public static final String EVENT_SERVER_STARTED = "server.started";
    public static final String EVENT_SERVER_STOPPED = "server.stopped";
    public static final String EVENT_AUTH_FAILURE = "auth.failure";
    public static final String EVENT_SUSPICIOUS = "suspicious.request";
    public static final String EVENT_BACKUP_COMPLETED = "backup.completed";
    public static final String EVENT_TASK_COMPLETED = "task.completed";
    public static final String EVENT_TASK_FAILED = "task.failed";

    private static final String USER_AGENT = "PhoneServer-Webhook/1.0";
    private static final int MAX_ATTEMPTS = 3;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PhoneServer-Webhook");
        thread.setDaemon(true);
        return thread;
    });

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
        return deliver(context.getApplicationContext(), WebhookConfig.copy(config),
                event == null ? "test" : event, payload(event == null ? "test" : event, data));
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
        entry.url = config.url;
        entry.createdAt = System.currentTimeMillis();
        String body = payload.toString();
        String signature = sign(body, config.secret);
        long started = System.currentTimeMillis();
        String lastError = "";
        int lastCode = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpURLConnection connection = null;
            entry.attempts = attempt;
            try {
                URL url = new URL(config.url);
                if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
                    throw new IllegalArgumentException("unsupported webhook URL scheme");
                }
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("X-PhoneServer-Event", event);
                if (!signature.isEmpty()) {
                    connection.setRequestProperty("X-PhoneServer-Signature", "sha256=" + signature);
                }
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
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
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(attempt == 1 ? 1000L : 3000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        entry.success = false;
        entry.error = lastError;
        entry.durationMs = System.currentTimeMillis() - started;
        finish(context, config, entry);
        return entry;
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
