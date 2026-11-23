package com.yadaventerprise.phoneserver.data.webhooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class WebhookDispatcherTest {

    private ServerSocket server;
    private Thread serverThread;
    private Context context;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> signature = new AtomicReference<>();
    private final AtomicReference<String> event = new AtomicReference<>();
    private final java.util.concurrent.ConcurrentHashMap<String,String> capturedHeaders = new java.util.concurrent.ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        WebhookHistoryStore.reset();
        WebhookStore.reset();
        WebhookHistoryStore.get(context).clear();
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        serverThread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
                int previous = -1;
                while (true) {
                    int current = in.read();
                    if (current < 0) break;
                    headerBytes.write(current);
                    String headers = headerBytes.toString("UTF-8");
                    if (headers.endsWith("\r\n\r\n")) break;
                    previous = current;
                }
                String headers = headerBytes.toString("UTF-8");
                int length = 0;
                for (String line : headers.split("\r\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                        length = Integer.parseInt(line.substring(colon + 1).trim());
                    }
                    String lower = line.toLowerCase();
                    if (lower.startsWith("x-phoneserver-signature:")) signature.set(line.substring(colon + 1).trim());
                    if (lower.startsWith("x-phoneserver-event:")) event.set(line.substring(colon + 1).trim());
                    if (lower.startsWith("x-phoneserver-timestamp:")) capturedHeaders.put("x-phoneserver-timestamp", line.substring(colon + 1).trim());
                    if (lower.startsWith("x-phoneserver-delivery-id:")) capturedHeaders.put("x-phoneserver-delivery-id", line.substring(colon + 1).trim());
                }
                byte[] payload = new byte[length];
                int read = 0;
                while (read < length) {
                    int count = in.read(payload, read, length - read);
                    if (count < 0) break;
                    read += count;
                }
                body.set(new String(payload, 0, read, StandardCharsets.UTF_8));
                OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {
            }
        }, "webhook-test-server");
        serverThread.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }
        if (serverThread != null) serverThread.interrupt();
    }

    @Test
    public void deliversSignedJsonAndRecordsHistory() throws Exception {
        WebhookConfig config = new WebhookConfig();
        config.id = "hook-1";
        config.name = "Test";
        config.url = "http://127.0.0.1:" + server.getLocalPort() + "/hook";
        config.secret = "secret";

        JSONObject data = new JSONObject();
        data.put("message", "hello");
        WebhookHistoryEntry entry = WebhookDispatcher.deliverNow(context, config, "test", data);

        assertNotNull(entry);
        assertTrue(entry.success);
        assertEquals("test", event.get());
        assertTrue(body.get().contains("\"message\":\"hello\""));
        String ts = capturedHeaders.get("x-phoneserver-timestamp");
        String delivery = capturedHeaders.get("x-phoneserver-delivery-id");
        assertNotNull(ts);
        assertNotNull(delivery);
        assertEquals("sha256=" + hmac(ts + "." + delivery + "." + body.get(), "secret"), signature.get());
        assertEquals(1, WebhookHistoryStore.get(context).list().size());
    }

    @Test
    public void verifiesSignatureAndRejectsReplay() {
        String secret = "secret";
        String ts = String.valueOf(System.currentTimeMillis());
        String delivery = "delivery-1";
        String body = "{\"ok\":true}";
        String sig = "sha256=" + hmacUnchecked(ts + "." + delivery + "." + body, secret);
        assertTrue(WebhookDispatcher.verifyIncoming(context, secret, ts, delivery, body, sig, System.currentTimeMillis()));
        assertFalse(WebhookDispatcher.verifyIncoming(context, secret, ts, delivery, body, sig, System.currentTimeMillis()));
    }

    @Test
    public void rejectsInvalidSignatureAndOldTimestamp() {
        String secret = "secret";
        String body = "{\"ok\":true}";
        String oldTs = String.valueOf(System.currentTimeMillis() - 10L * 60L * 1000L);
        String sig = "sha256=" + hmacUnchecked(oldTs + ".d1." + body, secret);
        assertFalse(WebhookDispatcher.verifyIncoming(context, secret, oldTs, "d1", body, sig, System.currentTimeMillis()));
        String ts = String.valueOf(System.currentTimeMillis());
        assertFalse(WebhookDispatcher.verifyIncoming(context, secret, ts, "d2", body, "sha256=bad", System.currentTimeMillis()));
    }

    @Test
    public void failedDeliveryIsRecorded() throws Exception {
        WebhookConfig config = new WebhookConfig();
        config.id = "hook-fail";
        config.name = "Fail";
        config.url = "http://127.0.0.1:1/hook";
        config.secret = "secret";
        config.maxAttempts = 1;
        config.timeoutSeconds = 1;
        WebhookHistoryEntry entry = WebhookDispatcher.deliverNow(context, config, "test", new JSONObject());
        assertNotNull(entry);
        assertFalse(entry.success);
        assertTrue(entry.error == null || !entry.error.contains("secret"));
    }

    @Test
    public void replayProtectionPersistsAcrossStoreReset() {
        String secret = "secret";
        String ts = String.valueOf(System.currentTimeMillis());
        String delivery = "delivery-persist";
        String body = "{\"ok\":true}";
        String sig = "sha256=" + hmacUnchecked(ts + "." + delivery + "." + body, secret);
        assertTrue(WebhookDispatcher.verifyIncoming(context, secret, ts, delivery, body, sig, System.currentTimeMillis()));
        WebhookReplayStore.reset();
        assertFalse(WebhookDispatcher.verifyIncoming(context, secret, ts, delivery, body, sig, System.currentTimeMillis()));
    }

    @Test
    public void webhookConfigToJsonRedactsSecretAndSensitiveHeaders() throws Exception {
        WebhookConfig config = new WebhookConfig();
        config.id = "w1"; config.name="n"; config.url="https://example.com"; config.secret="topsecret";
        config.headersJson = new org.json.JSONObject().put("Authorization","Bearer abc").put("X-Test","1").toString();
        org.json.JSONObject json = config.toJson();
        assertFalse(json.has("secret"));
        assertTrue(json.optBoolean("hasSecret"));
        org.json.JSONObject headers = new org.json.JSONObject(json.optString("headersJson","{}"));
        assertEquals("[redacted]", headers.optString("Authorization"));
        assertEquals("1", headers.optString("X-Test"));
    }

    private static String hmac(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static String hmacUnchecked(String value, String secret) {
        try { return hmac(value, secret); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
