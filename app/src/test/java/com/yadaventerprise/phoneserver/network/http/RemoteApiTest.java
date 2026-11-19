package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.remote.RemoteAuditEntry;
import com.yadaventerprise.phoneserver.data.remote.RemoteAuditStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteDevice;
import com.yadaventerprise.phoneserver.data.remote.RemoteDeviceStore;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingStore;
import com.yadaventerprise.phoneserver.data.remote.RemotePermissions;
import com.yadaventerprise.phoneserver.data.remote.RemoteRole;
import com.yadaventerprise.phoneserver.data.remote.RemoteSessionStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettings;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettingsStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteUser;
import com.yadaventerprise.phoneserver.data.remote.RemoteUserStore;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
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
public class RemoteApiTest {

    private static final String ADMIN_PASSWORD = "admin-secret-123";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicInteger ipCounter = new AtomicInteger();
    private Context context;

    @Before
    public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ContentStore.setPassword(context, ADMIN_PASSWORD);
        ContentStore.setApiRateLimitPerMinute(context, 2000);
        resetStores();
        clearStores();
        RemoteSettings settings = RemoteSettingsStore.get(context).get();
        settings.enabled = true;
        settings.requireHttps = true;
        settings.allowPairing = true;
        settings.allowRemoteFiles = true;
        RemoteSettingsStore.get(context).save(settings);
    }

    @After
    public void tearDown() {
        resetStores();
    }

    private void resetStores() {
        RemoteUserStore.reset();
        RemoteSessionStore.reset();
        RemoteDeviceStore.reset();
        RemotePairingStore.reset();
        RemoteAuditStore.reset();
        RemoteSettingsStore.reset();
    }

    private void clearStores() {
        RemoteUserStore.get(context).clear();
        RemoteSessionStore.get(context).clear();
        RemoteDeviceStore.get(context).clear();
        RemotePairingStore.get(context).clear();
        RemoteAuditStore.get(context).clear();
        RemoteSettingsStore.get(context).clear();
    }

    private String newIp() {
        return "10.2.0." + ipCounter.incrementAndGet();
    }

    private static final class Response {
        final int status;
        final String text;

        Response(int status, String text) {
            this.status = status;
            this.text = text;
        }
    }

    private Response call(String method, String path, Map<String, String> headers,
                          Map<String, String> query, String body) {
        return call(method, path, headers, query, body, newIp(), true);
    }

    private Response call(String method, String path, Map<String, String> headers,
                          Map<String, String> query, String body, String ip, boolean secure) {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = method;
        req.path = path;
        req.remoteIp = ip;
        req.secure = secure;
        if (headers != null) req.headers.putAll(headers);
        if (query != null) req.query.putAll(query);
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

    private Response get(String path, Map<String, String> headers) {
        return get(path, headers, null);
    }

    private Response get(String path, Map<String, String> headers, Map<String, String> query) {
        return call("GET", path, headers, query, null);
    }

    private static String path(String suffix) {
        return DataApi.BASE + "/remote/" + suffix;
    }

    private static Map<String, String> header(String key, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, String> bearer(String token) {
        return header("authorization", "Bearer " + token);
    }

    private static Map<String, String> query(String key, String value) {
        return header(key, value);
    }

    private static JSONObject body(Response response) throws Exception {
        String text = response.text;
        int start = text.indexOf("\r\n\r\n");
        assertTrue("response has no body separator", start >= 0);
        return new JSONObject(text.substring(start + 4));
    }

    private static String json(String... pairs) throws Exception {
        JSONObject json = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            json.put(pairs[i], pairs[i + 1]);
        }
        return json.toString();
    }

    private RemoteUser createUser(String username, String role, String password) {
        RemoteUser draft = new RemoteUser();
        draft.username = username;
        draft.role = role;
        RemoteUser created = RemoteUserStore.get(context).create(draft, password);
        assertNotNull(created);
        return created;
    }

    private String loginToken(String username, String password) throws Exception {
        Response response = call("POST", path("auth/login"), null, null,
                json("username", username, "password", password));
        assertEquals(200, response.status);
        String token = body(response).optString("token", "");
        assertFalse("login must return a token", token.isEmpty());
        return token;
    }

    @Test
    public void disabledRemoteAccessIsForbiddenAndHttpsLoginWorksAfterEnabling() throws Exception {
        createUser("loginuser", RemoteRole.VIEWER, "viewer-pass-123");
        String credentials = json("username", "loginuser", "password", "viewer-pass-123");

        RemoteSettings disabled = RemoteSettingsStore.get(context).get();
        disabled.enabled = false;
        RemoteSettingsStore.get(context).save(disabled);
        assertEquals(403, call("POST", path("auth/login"), null, null, credentials).status);

        RemoteSettings enabled = RemoteSettingsStore.get(context).get();
        enabled.enabled = true;
        enabled.requireHttps = true;
        RemoteSettingsStore.get(context).save(enabled);

        assertEquals(403, call("POST", path("auth/login"), null, null,
                credentials, newIp(), false).status);
        Response ok = call("POST", path("auth/login"), null, null, credentials);
        assertEquals(200, ok.status);
        assertFalse(body(ok).optString("token", "").isEmpty());
        assertEquals(200, call("POST", path("auth/login"), null, null,
                credentials, "127.0.0.1", false).status);
    }

    @Test
    public void loginIssuesTokenRejectsWrongPasswordAndLocksAfterThreshold() throws Exception {
        createUser("lockuser", RemoteRole.VIEWER, "correct-pass-123");
        String wrong = json("username", "lockuser", "password", "wrong-pass-123");

        assertEquals(401, call("POST", path("auth/login"), null, null, wrong).status);
        assertFalse(loginToken("lockuser", "correct-pass-123").isEmpty());

        String ip = newIp();
        for (int attempt = 0; attempt < 10; attempt++) {
            Response response = call("POST", path("auth/login"), null, null, wrong, ip, true);
            if (attempt < 9) assertEquals(401, response.status);
            else assertEquals(403, response.status);
        }
        RemoteUser stored = RemoteUserStore.get(context).findByUsername("lockuser");
        assertNotNull(stored);
        assertTrue("the user should be locked after repeated failures",
                stored.lockedUntil > System.currentTimeMillis());
    }

    @Test
    public void rolesEnforceDashboardUserAndFilePermissions() throws Exception {
        createUser("viewuser", RemoteRole.VIEWER, "viewer-pass-123");
        createUser("manageuser", RemoteRole.MANAGER, "manager-pass-123");
        String viewer = loginToken("viewuser", "viewer-pass-123");
        String manager = loginToken("manageuser", "manager-pass-123");

        assertEquals(200, get(path("dashboard"), bearer(viewer)).status);
        assertEquals(403, call("POST", path("users"), bearer(viewer), null,
                json("username", "byviewer", "password", "created-pass-123")).status);

        assertEquals(403, call("POST", path("users"), bearer(manager), null,
                json("username", "bymanager", "password", "created-pass-123")).status);
        Map<String, String> uploadQuery = query("name", "manager-" + System.nanoTime() + ".txt");
        assertEquals(201, call("POST", path("files/upload"), bearer(manager), uploadQuery,
                "manager file body").status);
        assertEquals(200, get(path("files/list"), bearer(manager)).status);

        String adminCreated = "admin" + (System.nanoTime() % 100000);
        Map<String, String> admin = header("x-auth-password", ADMIN_PASSWORD);
        Response created = call("POST", path("users"), admin, null,
                json("username", adminCreated, "role", RemoteRole.VIEWER,
                        "password", "created-pass-123"));
        assertEquals(201, created.status);
        assertEquals(adminCreated, body(created).optString("username"));

        createUser("owneruser", RemoteRole.OWNER, "owner-pass-123");
        String owner = loginToken("owneruser", "owner-pass-123");
        String ownerCreated = "owner" + (System.nanoTime() % 100000);
        assertEquals(201, call("POST", path("users"), bearer(owner), null,
                json("username", ownerCreated, "role", RemoteRole.VIEWER,
                        "password", "created-pass-123")).status);
    }

    @Test
    public void pairingFlowIssuesOneDeviceTokenAndScopesHeartbeats() throws Exception {
        Map<String, String> admin = header("x-auth-password", ADMIN_PASSWORD);
        Response created = call("POST", path("pairing"), admin, null, json("name", "Tablet"));
        assertEquals(201, created.status);
        JSONObject pairing = body(created);
        String code = pairing.optString("code", "");
        String requestId = pairing.optString("id", "");
        assertFalse(code.isEmpty());
        assertFalse(requestId.isEmpty());
        assertFalse("the QR payload must not contain a token", pairing.has("token"));
        assertTrue(pairing.optString("qrPayload").contains(code));

        JSONObject deviceInfo = new JSONObject();
        deviceInfo.put("model", "Pixel Tablet");
        JSONObject submit = new JSONObject();
        submit.put("code", code);
        submit.put("name", "Office tablet");
        submit.put("deviceInfo", deviceInfo);
        assertEquals(200, call("POST", path("pairing/request"), null, null, submit.toString()).status);

        JSONObject pending = body(call("POST", path("pairing/status"), null, null, json("code", code)));
        assertEquals("pending", pending.optString("status"));
        assertFalse("no token before approval", pending.has("token"));

        Response approved = call("POST", path("pairing/" + requestId + "/approve"), admin, null, null);
        assertEquals(200, approved.status);
        String deviceId = body(approved).optString("deviceId", "");
        assertFalse(deviceId.isEmpty());

        JSONObject issued = body(call("POST", path("pairing/status"), null, null, json("code", code)));
        assertEquals("consumed", issued.optString("status"));
        String deviceToken = issued.optString("token", "");
        assertFalse("approval should yield one token", deviceToken.isEmpty());
        assertEquals(deviceId, issued.optString("deviceId"));
        assertFalse(body(call("POST", path("pairing/status"), null, null,
                json("code", code))).has("token"));

        JSONObject report = new JSONObject();
        report.put("uptimeMillis", 1234);
        assertEquals(200, call("POST", path("devices/" + deviceId + "/heartbeat"),
                bearer(deviceToken), null, report.toString()).status);

        RemoteDevice other = new RemoteDevice();
        other.name = "Other device";
        RemoteDevice storedOther = RemoteDeviceStore.get(context).create(other);
        assertNotNull(storedOther);
        assertEquals(403, call("POST", path("devices/" + storedOther.id + "/heartbeat"),
                bearer(deviceToken), null, report.toString()).status);
    }

    @Test
    public void deviceMetricsReflectHeartbeatsAndStaleness() throws Exception {
        RemoteDevice draft = new RemoteDevice();
        draft.name = "Metrics device";
        RemoteDevice device = RemoteDeviceStore.get(context).create(draft);
        assertNotNull(device);
        device.lastSeenAt = 0;
        RemoteDeviceStore.get(context).save(device);

        Map<String, String> admin = header("x-auth-password", ADMIN_PASSWORD);
        JSONObject offline = body(get(path("devices/" + device.id), admin));
        assertFalse(offline.optBoolean("online"));
        assertFalse(offline.optBoolean("metricsAvailable"));
        assertTrue("staleness flag must be present", offline.has("metricsStale"));

        JSONObject report = new JSONObject();
        report.put("uptimeMillis", 5000);
        report.put("healthStatus", "ok");
        assertEquals(200, call("POST", path("devices/" + device.id + "/heartbeat"),
                admin, null, report.toString()).status);
        JSONObject online = body(get(path("devices/" + device.id), admin));
        assertTrue(online.optBoolean("online"));
        assertTrue(online.optBoolean("metricsAvailable"));
        assertFalse(online.optBoolean("metricsStale"));

        RemoteDevice staleDevice = RemoteDeviceStore.get(context).get(device.id);
        assertNotNull(staleDevice);
        staleDevice.lastReportAt = System.currentTimeMillis() - 600_000L;
        RemoteDeviceStore.get(context).save(staleDevice);
        JSONObject stale = body(get(path("devices/" + device.id), admin));
        assertTrue(stale.optBoolean("metricsStale"));
        assertFalse(stale.optBoolean("metricsAvailable"));
    }

    @Test
    public void fileOperationsRequireRemoteFileAccessAndRejectTraversal() throws Exception {
        Map<String, String> admin = header("x-auth-password", ADMIN_PASSWORD);
        assertEquals(200, get(path("files/list"), admin).status);

        String name = "phase7-" + System.nanoTime() + ".txt";
        assertEquals(201, call("POST", path("files/upload"), admin, query("name", name),
                "phase7 file body").status);

        Response download = get(path("files/download"), admin, query("path", name));
        assertEquals(200, download.status);
        assertTrue(download.text.contains("phase7 file body"));

        assertEquals(400, get(path("files/list"), admin, query("path", "../../etc")).status);
        Map<String, String> traversalUpload = query("path", "../../etc");
        traversalUpload.put("name", "evil.txt");
        assertEquals(400, call("POST", path("files/upload"), admin, traversalUpload, "x").status);

        assertEquals(200, call("POST", path("files/delete"), admin, null,
                json("path", name)).status);
        assertEquals(404, get(path("files/download"), admin, query("path", name)).status);

        RemoteSettings disabled = RemoteSettingsStore.get(context).get();
        disabled.allowRemoteFiles = false;
        RemoteSettingsStore.get(context).save(disabled);
        assertEquals(403, get(path("files/list"), admin).status);
        assertEquals(403, call("POST", path("files/delete"), admin, null,
                json("path", name)).status);
    }

    @Test
    public void auditRequiresPermissionAndSanitizesSecrets() throws Exception {
        RemoteUser draft = new RemoteUser();
        draft.username = "auditviewer";
        draft.role = RemoteRole.VIEWER;
        RemoteUser created = RemoteUserStore.get(context).create(draft, "audit-pass-123");
        assertNotNull(created);
        String viewer = loginToken("auditviewer", "audit-pass-123");
        assertEquals(403, get(path("audit"), bearer(viewer), query("limit", "50")).status);

        created.grantedPermissions.add(RemotePermissions.AUDIT_READ);
        RemoteUserStore.get(context).save(created);
        String granted = loginToken("auditviewer", "audit-pass-123");
        assertEquals(200, get(path("audit"), bearer(granted), query("limit", "50")).status);

        String secret = "SuperSecretValue99";
        String tokenValue = "abc123secrettoken456";
        RemoteAuditStore.get(context).append(RemoteAuditEntry.AUTH_FAILED, "audit-actor", "audit-subject",
                newIp(), "password=" + secret + " authorization: Bearer " + tokenValue, false);
        Response audit = get(path("audit"), header("x-auth-password", ADMIN_PASSWORD),
                query("limit", "50"));
        assertEquals(200, audit.status);
        assertFalse(audit.text.contains(secret));
        assertFalse(audit.text.contains(tokenValue));
        assertTrue(audit.text.contains("password=[redacted]"));
    }
}
