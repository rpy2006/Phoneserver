package com.yadaventerprise.phoneserver.data.webhooks;

import static org.junit.Assert.assertEquals;
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
                    if (line.toLowerCase().startsWith("x-phoneserver-signature:")) {
                        signature.set(line.substring(line.indexOf(':') + 1).trim());
                    }
                    if (line.toLowerCase().startsWith("x-phoneserver-event:")) {
                        event.set(line.substring(line.indexOf(':') + 1).trim());
                    }
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
        assertEquals("sha256=" + hmac(body.get(), "secret"), signature.get());
        assertEquals(1, WebhookHistoryStore.get(context).list().size());
    }

    private static String hmac(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
