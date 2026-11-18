package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.system.AutomationSettingsStore;
import com.yadaventerprise.phoneserver.data.system.SystemEventStore;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import com.yadaventerprise.phoneserver.data.tasks.TaskHistoryStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SystemApiTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicInteger ipCounter = new AtomicInteger();
    private Context context;

    @Before
    public void setUp() throws Exception {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ContentStore.setPassword(context, "secret");
        ContentStore.setApiRateLimitPerMinute(context, 240);
        AutomationSettingsStore.reset();
        SystemEventStore.reset();
        ScheduledTaskStore.reset();
        TaskHistoryStore.reset();
        WebhookStore.reset();
        WebhookHistoryStore.reset();
        for (com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig hook
                : WebhookStore.get(context).all()) {
            WebhookStore.get(context).delete(hook.id);
        }
        WebhookHistoryStore.get(context).clear();
        for (com.yadaventerprise.phoneserver.data.tasks.ScheduledTask task
                : ScheduledTaskStore.get(context).all()) {
            ScheduledTaskStore.get(context).delete(task.id);
        }
        TaskHistoryStore.get(context).clear();
    }

    @Test
    public void monitorRequiresSystemReadPermission() throws Exception {
        assertEquals(401, get(DataApi.BASE + "/system/monitor", null).status);

        TokenStore.Created viewer = TokenStore.create(context, "viewer", null, false);
        Response forbidden = get(DataApi.BASE + "/system/monitor",
                header("authorization", "Bearer " + viewer.token));
        assertEquals(403, forbidden.status);

        Response ok = get(DataApi.BASE + "/system/monitor", admin());
        assertEquals(200, ok.status);
        JSONObject json = body(ok);
        assertNotNull(json.optJSONObject("server"));
        assertNotNull(json.optJSONObject("resources"));
        assertTrue(json.optJSONArray("checks").length() > 0);
    }

    @Test
    public void automationPatchRequiresAdminAndPersistsChanges() throws Exception {
        TokenStore.Created editor = TokenStore.create(context, "editor", null, true);
        Response forbidden = call("PATCH", DataApi.BASE + "/system/automation",
                header("authorization", "Bearer " + editor.token),
                "{\"autoRestartEnabled\":false,\"watchdogIntervalMinutes\":9}");
        assertEquals(403, forbidden.status);

        Response ok = call("PATCH", DataApi.BASE + "/system/automation", admin(),
                "{\"autoRestartEnabled\":false,\"watchdogIntervalMinutes\":9,"
                        + "\"notifyTaskResults\":false,\"storageWarningMb\":1024}");
        assertEquals(200, ok.status);
        AutomationSettingsStore.reset();
        assertFalse(AutomationSettingsStore.get(context).get().autoRestartEnabled);
        assertEquals(9, AutomationSettingsStore.get(context).get().watchdogIntervalMinutes);
        assertEquals(1024, AutomationSettingsStore.get(context).get().storageWarningMb);
    }

    @Test
    public void webhookResponsesRedactSecretsAndPreserveThemOnPatch() throws Exception {
        Response created = call("POST", DataApi.BASE + "/system/webhooks", admin(),
                "{\"name\":\"Hook\",\"url\":\"https://example.com/hook\","
                        + "\"secret\":\"super-secret\",\"method\":\"PUT\","
                        + "\"events\":[\"server.started\"],\"maxAttempts\":4}");
        assertEquals(201, created.status);
        assertFalse(created.text.contains("super-secret"));
        JSONObject json = body(created);
        assertTrue(json.optBoolean("hasSecret"));
        assertEquals("PUT", json.optString("method"));
        assertEquals(4, json.optInt("maxAttempts"));

        String id = json.optString("id");
        Response patched = call("PATCH", DataApi.BASE + "/system/webhooks/" + id, admin(),
                "{\"name\":\"Renamed\"}");
        assertEquals(200, patched.status);
        assertTrue(body(patched).optBoolean("hasSecret"));
        assertFalse(body(patched).has("secret"));
    }

    @Test
    public void tasksSupportOneTimeSchedulesAndHistory() throws Exception {
        Response hook = call("POST", DataApi.BASE + "/system/webhooks", admin(),
                "{\"name\":\"Hook\",\"url\":\"https://example.com/hook\","
                        + "\"events\":[\"task.completed\"]}");
        assertEquals(201, hook.status);
        String webhookId = body(hook).optString("id");
        long runAt = System.currentTimeMillis() + 60_000L;

        Response created = call("POST", DataApi.BASE + "/system/tasks", admin(),
                new JSONObject()
                        .put("name", "Notify")
                        .put("action", "webhook")
                        .put("webhookId", webhookId)
                        .put("scheduleType", "once")
                        .put("runAt", runAt)
                        .put("maxAttempts", 3)
                        .put("retryDelayMinutes", 10)
                        .toString());
        assertEquals(201, created.status);
        JSONObject task = body(created);
        assertEquals("once", task.optString("scheduleType"));
        assertEquals(runAt, task.optLong("runAt"));
        assertEquals(3, task.optInt("maxAttempts"));
        assertEquals(10, task.optInt("retryDelayMinutes"));

        Response history = get(DataApi.BASE + "/system/tasks/" + task.optString("id") + "/history",
                admin());
        assertEquals(200, history.status);
        assertEquals(0, body(history).optJSONArray("history").length());

        Response invalid = call("POST", DataApi.BASE + "/system/tasks", admin(),
                "{\"name\":\"Broken\",\"action\":\"webhook\",\"webhookId\":\"missing\","
                        + "\"scheduleType\":\"once\",\"runAt\":1}");
        assertEquals(400, invalid.status);
    }

    @Test
    public void maintenanceCleanRequiresExplicitConfirmation() throws Exception {
        Response rejected = call("POST", DataApi.BASE + "/system/maintenance/clean", admin(), "{}");
        assertEquals(400, rejected.status);

        Response ok = call("POST", DataApi.BASE + "/system/maintenance/clean", admin(),
                "{\"confirm\":true}");
        assertEquals(200, ok.status);
        assertNotNull(body(ok).optJSONArray("issues"));
    }

    private Response get(String path, Map<String, String> headers) {
        return call("GET", path, headers, null);
    }

    private Map<String, String> admin() {
        return header("x-auth-password", "secret");
    }

    private Response call(String method, String path, Map<String, String> headers, String body) {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = method;
        req.path = path;
        req.remoteIp = "10.1.0." + ipCounter.incrementAndGet();
        if (headers != null) req.headers.putAll(headers);
        if (body != null) req.body = body.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            DataApi.handle(context, req, out);
        } catch (Exception e) {
            throw new AssertionError("DataApi.handle threw for " + method + " " + path, e);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        String firstLine = text.split("\r\n")[0];
        String[] parts = firstLine.split(" ");
        assertEquals("malformed response: " + firstLine, "HTTP/1.1", parts[0]);
        return new Response(Integer.parseInt(parts[1]), text);
    }

    private static JSONObject body(Response response) throws Exception {
        int split = response.text.indexOf("\r\n\r\n");
        assertTrue("response has no body", split >= 0);
        return new JSONObject(response.text.substring(split + 4));
    }

    private static Map<String, String> header(String key, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static final class Response {
        final int status;
        final String text;

        Response(int status, String text) {
            this.status = status;
            this.text = text;
        }
    }
}
