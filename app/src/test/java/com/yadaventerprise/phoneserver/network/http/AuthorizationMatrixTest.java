package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.security.TokenPolicyStore;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
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
public class AuthorizationMatrixTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;
    private final AtomicInteger ipCounter = new AtomicInteger();

    @Before
    public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ContentStore.setPassword(context, "admin");
        ContentStore.setApiRateLimitPerMinute(context, 5000);
    }

    private static final class Response {
        final int status;
        final String text;
        Response(int status, String text) { this.status = status; this.text = text; }
    }

    private Response call(String method, String path, Map<String, String> headers, String body) {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = method;
        req.path = path;
        req.remoteIp = "10.9.0." + ipCounter.incrementAndGet();
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
        return new Response(Integer.parseInt(firstLine.split(" ")[1]), text);
    }

    private static Map<String, String> bearer(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("authorization", "Bearer " + token);
        return h;
    }

    private static Map<String, String> admin() {
        Map<String, String> h = new HashMap<>();
        h.put("x-auth-password", "admin");
        return h;
    }

    private TokenStore.Created tokenWithPolicy(String name, String role, String projectId, boolean canWrite) throws Exception {
        TokenStore.Created created = TokenStore.create(context, name, projectId, canWrite);
        TokenPolicy policy = TokenPolicy.preset(role);
        policy.tokenId = created.record.id;
        if (!canWrite) {
            policy.permissions.removeIf(p -> p.endsWith(".write") || p.equals(TokenPolicy.TOKENS_MANAGE) || p.equals(TokenPolicy.AI_ACTIONS) || p.equals(TokenPolicy.AI_SETTINGS_WRITE));
            policy.role = TokenPolicy.ROLE_VIEWER;
        }
        TokenPolicyStore.get(context).save(policy);
        return created;
    }

    @Test
    public void missingAndInvalidTokensRejected() throws Exception {
        ProjectStore.create(context, "A", "", 0, 0);
        assertEquals(401, call("GET", DataApi.BASE + "/projects", null, null).status);
        assertEquals(401, call("GET", DataApi.BASE + "/projects", bearer("pst_invalid"), null).status);
        assertEquals(401, call("GET", DataApi.BASE + "/system", bearer(""), null).status);
    }

    @Test
    public void revokedAndExpiredTokensRejected() throws Exception {
        ProjectStore.create(context, "A", "", 0, 0);
        TokenStore.Created revoked = TokenStore.create(context, "r", null, true);
        TokenStore.revoke(context, revoked.record.id);
        assertEquals(401, call("GET", DataApi.BASE + "/projects", bearer(revoked.token), null).status);

        TokenStore.Created expired = TokenStore.create(context, "e", null, true, System.currentTimeMillis() - 1000);
        assertEquals(401, call("GET", DataApi.BASE + "/projects", bearer(expired.token), null).status);
    }

    @Test
    public void readOnlyTokenCannotMutateProtectedEndpoints() throws Exception {
        String projectId = ProjectStore.create(context, "A", "", 0, 0).id;
        TokenStore.Created ro = TokenStore.create(context, "ro", null, false);
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        policy.tokenId = ro.record.id;
        TokenPolicyStore.get(context).save(policy);
        Map<String, String> auth = bearer(ro.token);

        assertEquals(200, call("GET", DataApi.BASE + "/projects", auth, null).status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects", auth, "{\"name\":\"B\"}").status);
        assertEquals(403, call("PATCH", DataApi.BASE + "/projects/" + projectId, auth, "{\"name\":\"B\"}").status);
        assertEquals(403, call("DELETE", DataApi.BASE + "/projects/" + projectId + "?confirm=true", auth, null).status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + projectId + "/databases", auth, "{\"name\":\"db1\"}").status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + projectId + "/folders", auth, "{\"path\":\"docs\"}").status);
        assertEquals(403, call("POST", DataApi.BASE + "/developer/webhooks", auth, "{\"name\":\"x\",\"url\":\"http://127.0.0.1/\",\"events\":[\"test\"]}").status);
        assertEquals(403, call("POST", DataApi.BASE + "/developer/api-keys", auth, "{\"name\":\"x\",\"role\":\"viewer\"}").status);
    }

    @Test
    public void projectScopedTokenCannotAccessOtherProject() throws Exception {
        String a = ProjectStore.create(context, "A", "", 0, 0).id;
        String b = ProjectStore.create(context, "B", "", 0, 0).id;
        TokenStore.Created scoped = TokenStore.create(context, "scoped", a, true);
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_EDITOR);
        policy.tokenId = scoped.record.id;
        TokenPolicyStore.get(context).save(policy);
        Map<String, String> auth = bearer(scoped.token);

        assertEquals(200, call("GET", DataApi.BASE + "/projects/" + a, auth, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/projects/" + b, auth, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/projects/" + b + "/files", auth, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/projects/" + b + "/databases", auth, null).status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + b + "/folders", auth, "{\"path\":\"x\"}").status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects", auth, "{\"name\":\"C\"}").status);
    }

    @Test
    public void revokedPermissionTakesEffectImmediately() throws Exception {
        ProjectStore.create(context, "A", "", 0, 0);
        TokenStore.Created created = TokenStore.create(context, "perm", null, true);
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_EDITOR);
        policy.tokenId = created.record.id;
        TokenPolicyStore.get(context).save(policy);
        Map<String, String> auth = bearer(created.token);

        assertEquals(200, call("GET", DataApi.BASE + "/projects", auth, null).status);
        policy.permissions.remove(TokenPolicy.PROJECTS_READ);
        TokenPolicyStore.get(context).save(policy);
        assertEquals(403, call("GET", DataApi.BASE + "/projects", auth, null).status);
    }

    @Test
    public void tokenManagementRequiresAdmin() throws Exception {
        TokenStore.Created viewer = TokenStore.create(context, "viewer", null, false);
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        policy.tokenId = viewer.record.id;
        TokenPolicyStore.get(context).save(policy);
        Map<String, String> auth = bearer(viewer.token);
        assertEquals(403, call("GET", DataApi.BASE + "/tokens", auth, null).status);
        assertEquals(403, call("POST", DataApi.BASE + "/tokens", auth, "{\"name\":\"x\"}").status);
        assertEquals(200, call("GET", DataApi.BASE + "/tokens", admin(), null).status);
    }

    @Test
    public void systemMutationsRequireAdmin() throws Exception {
        TokenStore.Created viewer = TokenStore.create(context, "viewer", null, false);
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        policy.tokenId = viewer.record.id;
        policy.permissions.add(TokenPolicy.SYSTEM_READ);
        TokenPolicyStore.get(context).save(policy);
        Map<String, String> auth = bearer(viewer.token);
        assertEquals(200, call("GET", DataApi.BASE + "/system/monitor", auth, null).status);
        assertEquals(403, call("POST", DataApi.BASE + "/system/maintenance/clean", auth, "{\"confirm\":true}").status);
    }

    @Test
    public void developerEndpointsEnforcePermissions() throws Exception {
        TokenStore.Created viewer = TokenStore.create(context, "viewer", null, false);
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        policy.tokenId = viewer.record.id;
        TokenPolicyStore.get(context).save(policy);
        Map<String, String> auth = bearer(viewer.token);
        assertEquals(403, call("GET", DataApi.BASE + "/developer/overview", auth, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/developer/api-keys", auth, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/developer/webhooks", auth, null).status);
        assertEquals(403, call("POST", DataApi.BASE + "/developer/sandbox", auth, "{\"action\":\"echo\"}").status);
    }

    @Test
    public void revokedPermissionBlocksDeveloperMutationImmediately() throws Exception {
        TokenStore.Created created = TokenStore.create(context, "dev", null, true);
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_ADMIN);
        policy.tokenId = created.record.id;
        TokenPolicyStore.get(context).save(policy);
        Map<String, String> auth = bearer(created.token);
        Response before = call("POST", DataApi.BASE + "/developer/webhooks", auth,
                "{\"name\":\"w\",\"url\":\"http://127.0.0.1:1/h\",\"events\":[\"test\"],\"enabled\":false}");
        assertEquals(201, before.status);
        policy.permissions.remove(TokenPolicy.WEBHOOKS_WRITE);
        TokenPolicyStore.get(context).save(policy);
        Response after = call("POST", DataApi.BASE + "/developer/webhooks", auth,
                "{\"name\":\"w2\",\"url\":\"http://127.0.0.1:1/h\",\"events\":[\"test\"],\"enabled\":false}");
        assertEquals(403, after.status);
    }

    @Test
    public void alternateEndpointsCannotBypassProjectScope() throws Exception {
        String a = ProjectStore.create(context, "A", "", 0, 0).id;
        String b = ProjectStore.create(context, "B", "", 0, 0).id;
        TokenStore.Created scoped = TokenStore.create(context, "scoped", a, true);
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_EDITOR);
        policy.tokenId = scoped.record.id;
        TokenPolicyStore.get(context).save(policy);
        Map<String, String> auth = bearer(scoped.token);
        assertEquals(403, call("GET", DataApi.BASE + "/projects/" + b + "/stats", auth, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/projects/" + b + "/usage", auth, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/projects/" + b + "/backups", auth, null).status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + b + "/backups/export-config", auth, "{\"categories\":[]}").status);
    }

    @Test
    public void fileAndBackupMutationsRequireWritePermission() throws Exception {
        String projectId = ProjectStore.create(context, "A", "", 0, 0).id;
        TokenStore.Created ro = TokenStore.create(context, "ro2", null, false);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        p.tokenId = ro.record.id;
        TokenPolicyStore.get(context).save(p);
        Map<String, String> auth = bearer(ro.token);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + projectId + "/files", auth, null).status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + projectId + "/backups", auth, "{\"confirm\":true}").status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + projectId + "/backups/export-config", auth, "{\"categories\":[]}").status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + projectId + "/backups/import-config/preview", auth, "{}").status);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + projectId + "/backups/import-config/apply", auth, "{}").status);
    }

    @Test
    public void databaseMutationsRequireWritePermission() throws Exception {
        String projectId = ProjectStore.create(context, "A", "", 0, 0).id;
        TokenStore.Created ro = TokenStore.create(context, "rodb", null, false);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        p.tokenId = ro.record.id;
        p.permissions.add(TokenPolicy.DATABASES_READ);
        TokenPolicyStore.get(context).save(p);
        Map<String, String> auth = bearer(ro.token);
        assertEquals(403, call("POST", DataApi.BASE + "/projects/" + projectId + "/databases", auth, "{\"name\":\"db1\"}").status);
        assertEquals(403, call("DELETE", DataApi.BASE + "/projects/" + projectId + "/databases/db1?confirm=true", auth, null).status);
    }

    @Test
    public void aiAndMailPermissionsAreEnforced() throws Exception {
        TokenStore.Created ro = TokenStore.create(context, "roai", null, false);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        p.tokenId = ro.record.id;
        p.permissions.remove(TokenPolicy.AI_USE);
        p.permissions.remove(TokenPolicy.MAIL_READ);
        TokenPolicyStore.get(context).save(p);
        Map<String, String> auth = bearer(ro.token);
        assertEquals(403, call("GET", DataApi.BASE + "/ai/providers", auth, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/mail/accounts", auth, null).status);
    }

    @Test
    public void systemTaskAndWebhookMutationsRequireAdmin() throws Exception {
        TokenStore.Created editor = TokenStore.create(context, "ed", null, true);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_EDITOR);
        p.tokenId = editor.record.id;
        TokenPolicyStore.get(context).save(p);
        Map<String, String> auth = bearer(editor.token);
        assertEquals(403, call("POST", DataApi.BASE + "/system/tasks", auth, "{\"name\":\"t\",\"type\":\"cleanup\",\"scheduleType\":\"manual\"}").status);
        assertEquals(403, call("POST", DataApi.BASE + "/system/webhooks", auth, "{\"name\":\"w\",\"url\":\"http://127.0.0.1/\",\"events\":[\"test\"]}").status);
        assertEquals(403, call("PATCH", DataApi.BASE + "/system/automation", auth, "{\"tasksEnabled\":false}").status);
    }
}
