package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.*;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettings;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettingsStore;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingRequest;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteSession;
import com.yadaventerprise.phoneserver.data.remote.RemoteSessionStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteUser;
import com.yadaventerprise.phoneserver.data.remote.RemoteUserStore;
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
public class RemoteApiAuthTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;
    private final AtomicInteger ipCounter = new AtomicInteger();

    @Before public void setUp() throws Exception {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ContentStore.setPassword(context, "admin");
        ContentStore.setApiRateLimitPerMinute(context, 5000);
        RemoteSettings s = RemoteSettingsStore.get(context).get();
        s.enabled = true; s.allowPairing = true; s.requireHttps = false;
        RemoteSettingsStore.get(context).save(s);
    }

    private static final class Resp { int status; String body; Resp(int s,String b){status=s;body=b;} }
    private Resp call(String method, String path, Map<String,String> headers, String body, String ip) {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = method; req.path = path; req.remoteIp = ip == null ? "10.42.0." + ipCounter.incrementAndGet() : ip;
        if (headers != null) req.headers.putAll(headers);
        if (body != null) req.body = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { DataApi.handle(context, req, out); } catch (Exception e) { throw new AssertionError(e); }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int status = Integer.parseInt(text.split("\r\n")[0].split(" ")[1]);
        int idx = text.indexOf("\r\n\r\n");
        return new Resp(status, idx >= 0 ? text.substring(idx + 4) : "");
    }
    private static Map<String,String> bearer(String token) { Map<String,String> h=new HashMap<>(); h.put("authorization","Bearer "+token); return h; }
    private static Map<String,String> admin() { Map<String,String> h=new HashMap<>(); h.put("x-auth-password","admin"); return h; }

    @Test public void remoteProtectedEndpointsRequireCredential() {
        assertEquals(403, call("GET", DataApi.BASE + "/remote/dashboard", null, null, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/remote/devices", bearer("pst_invalid"), null, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/remote/users", bearer("pst_invalid"), null, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/remote/settings", bearer("pst_invalid"), null, null).status);
    }

    @Test public void adminPasswordCanAccessRemoteDashboard() {
        Resp resp = call("GET", DataApi.BASE + "/remote/dashboard", admin(), null, null);
        assertEquals(200, resp.status);
        assertTrue(resp.body.contains("serverRunning"));
    }

    @Test public void remoteAccessDisabledBlocksPublicAndProtectedRemote() throws Exception {
        RemoteSettings s = RemoteSettingsStore.get(context).get();
        s.enabled = false; RemoteSettingsStore.get(context).save(s);
        Resp login = call("POST", DataApi.BASE + "/remote/auth/login", null,
                new JSONObject().put("username","u").put("password","p").toString(), null);
        assertEquals(403, login.status);
        Resp dash = call("GET", DataApi.BASE + "/remote/dashboard", admin(), null, null);
        assertEquals(403, dash.status);
    }

    @Test public void requireHttpsBlocksNonLoopbackRemote() {
        RemoteSettings s = RemoteSettingsStore.get(context).get();
        s.requireHttps = true; RemoteSettingsStore.get(context).save(s);
        Resp resp = call("GET", DataApi.BASE + "/remote/dashboard", admin(), null, "203.0.113.5");
        assertEquals(403, resp.status);
    }

    @Test public void pairingCodeIsOneTimeConsumable() throws Exception {
        Resp reqResp = call("POST", DataApi.BASE + "/remote/pairing/request", null,
                new JSONObject().put("deviceName","Pixel").toString(), null);
        assertEquals(200, reqResp.status);
        String code = new JSONObject(reqResp.body).getString("code");
        String id = new JSONObject(reqResp.body).getString("id");
        Resp approve = call("POST", DataApi.BASE + "/remote/pairing/" + id + "/approve", admin(),
                new JSONObject().put("deviceName","Pixel").toString(), null);
        assertEquals(200, approve.status);
        Resp first = call("POST", DataApi.BASE + "/remote/pairing/status", null,
                new JSONObject().put("code", code).toString(), null);
        assertEquals(200, first.status);
        assertTrue(first.body.contains("token"));
        Resp second = call("POST", DataApi.BASE + "/remote/pairing/status", null,
                new JSONObject().put("code", code).toString(), null);
        assertNotEquals(200, second.status);
    }

    @Test public void revokedRemoteUserSessionIsRejected() throws Exception {
        com.yadaventerprise.phoneserver.data.remote.RemoteUserStore us = com.yadaventerprise.phoneserver.data.remote.RemoteUserStore.get(context);
        com.yadaventerprise.phoneserver.data.remote.RemoteUser u = new com.yadaventerprise.phoneserver.data.remote.RemoteUser();
        u.username = "alice"; u.displayName = "Alice"; u.role = com.yadaventerprise.phoneserver.data.remote.RemoteRole.VIEWER; u.status = com.yadaventerprise.phoneserver.data.remote.RemoteUser.STATUS_ACTIVE;
        us.create(u, "password12345");
        Resp login = call("POST", DataApi.BASE + "/remote/auth/login", null,
                new JSONObject().put("username","alice").put("password","password12345").toString(), null);
        assertEquals(200, login.status);
        String token = new JSONObject(login.body).getJSONObject("session").getString("token");
        Resp ok = call("GET", DataApi.BASE + "/remote/dashboard", bearer(token), null, null);
        assertEquals(200, ok.status);
        Resp logout = call("POST", DataApi.BASE + "/remote/auth/logout", bearer(token), "{}", null);
        assertEquals(200, logout.status);
        Resp denied = call("GET", DataApi.BASE + "/remote/dashboard", bearer(token), null, null);
        assertEquals(403, denied.status);
    }

    @Test public void suspendedRemoteUserCannotLogin() throws Exception {
        com.yadaventerprise.phoneserver.data.remote.RemoteUserStore us = com.yadaventerprise.phoneserver.data.remote.RemoteUserStore.get(context);
        com.yadaventerprise.phoneserver.data.remote.RemoteUser u = new com.yadaventerprise.phoneserver.data.remote.RemoteUser();
        u.username = "bob"; u.displayName = "Bob"; u.role = com.yadaventerprise.phoneserver.data.remote.RemoteRole.VIEWER; u.status = com.yadaventerprise.phoneserver.data.remote.RemoteUser.STATUS_SUSPENDED;
        us.create(u, "password12345");
        Resp login = call("POST", DataApi.BASE + "/remote/auth/login", null,
                new JSONObject().put("username","bob").put("password","password12345").toString(), null);
        assertEquals(403, login.status);
    }

    @Test public void remoteFilesRejectUnsafePath() {
        Resp resp = call("GET", DataApi.BASE + "/remote/files/list?path=../../..", admin(), null, null);
        assertTrue(resp.status == 400 || resp.status == 403 || resp.status == 404);
    }

    @Test public void remoteFilesDeleteRequiresWritePermission() throws Exception {
        com.yadaventerprise.phoneserver.data.remote.RemoteUserStore us = com.yadaventerprise.phoneserver.data.remote.RemoteUserStore.get(context);
        com.yadaventerprise.phoneserver.data.remote.RemoteUser u = new com.yadaventerprise.phoneserver.data.remote.RemoteUser();
        u.username = "viewer"; u.displayName = "Viewer"; u.role = com.yadaventerprise.phoneserver.data.remote.RemoteRole.VIEWER; u.status = com.yadaventerprise.phoneserver.data.remote.RemoteUser.STATUS_ACTIVE;
        us.create(u, "password12345");
        Resp login = call("POST", DataApi.BASE + "/remote/auth/login", null,
                new JSONObject().put("username","viewer").put("password","password12345").toString(), null);
        assertEquals(200, login.status);
        String token = new JSONObject(login.body).getJSONObject("session").getString("token");
        Resp del = call("POST", DataApi.BASE + "/remote/files/delete", bearer(token),
                new JSONObject().put("path","nope.txt").toString(), null);
        assertEquals(403, del.status);
    }

    @Test public void revokedDeviceSessionIsRejected() throws Exception {
        Resp reqResp = call("POST", DataApi.BASE + "/remote/pairing/request", null,
                new JSONObject().put("deviceName","Tablet").toString(), null);
        assertEquals(200, reqResp.status);
        String code = new JSONObject(reqResp.body).getString("code");
        String id = new JSONObject(reqResp.body).getString("id");
        Resp approve = call("POST", DataApi.BASE + "/remote/pairing/" + id + "/approve", admin(),
                new JSONObject().put("deviceName","Tablet").toString(), null);
        assertEquals(200, approve.status);
        Resp status = call("POST", DataApi.BASE + "/remote/pairing/status", null,
                new JSONObject().put("code", code).toString(), null);
        assertEquals(200, status.status);
        String token = new JSONObject(status.body).getString("token");
        String deviceId = new JSONObject(status.body).getString("deviceId");
        assertEquals(200, call("GET", DataApi.BASE + "/remote/dashboard", bearer(token), null, null).status);
        Resp revoke = call("DELETE", DataApi.BASE + "/remote/devices/" + deviceId, admin(), null, null);
        assertEquals(200, revoke.status);
        assertEquals(403, call("GET", DataApi.BASE + "/remote/dashboard", bearer(token), null, null).status);
    }

    @Test public void deviceCannotHeartbeatAnotherDevice() throws Exception {
        Resp r1 = call("POST", DataApi.BASE + "/remote/pairing/request", null, new JSONObject().put("deviceName","A").toString(), null);
        String code1 = new JSONObject(r1.body).getString("code"); String id1 = new JSONObject(r1.body).getString("id");
        assertEquals(200, call("POST", DataApi.BASE + "/remote/pairing/" + id1 + "/approve", admin(), new JSONObject().put("deviceName","A").toString(), null).status);
        Resp s1 = call("POST", DataApi.BASE + "/remote/pairing/status", null, new JSONObject().put("code",code1).toString(), null);
        String token1 = new JSONObject(s1.body).getString("token"); String dev1 = new JSONObject(s1.body).getString("deviceId");

        Resp r2 = call("POST", DataApi.BASE + "/remote/pairing/request", null, new JSONObject().put("deviceName","B").toString(), null);
        String code2 = new JSONObject(r2.body).getString("code"); String id2 = new JSONObject(r2.body).getString("id");
        assertEquals(200, call("POST", DataApi.BASE + "/remote/pairing/" + id2 + "/approve", admin(), new JSONObject().put("deviceName","B").toString(), null).status);
        Resp s2 = call("POST", DataApi.BASE + "/remote/pairing/status", null, new JSONObject().put("code",code2).toString(), null);
        String dev2 = new JSONObject(s2.body).getString("deviceId");

        Resp hb = call("POST", DataApi.BASE + "/remote/devices/" + dev2 + "/heartbeat", bearer(token1), new JSONObject().put("uptimeMillis",1).toString(), null);
        assertEquals(403, hb.status);
        assertEquals(200, call("POST", DataApi.BASE + "/remote/devices/" + dev1 + "/heartbeat", bearer(token1), new JSONObject().put("uptimeMillis",1).toString(), null).status);
    }

    @Test public void logoutRequiresSessionCredential() throws Exception {
        assertEquals(403, call("POST", DataApi.BASE + "/remote/auth/logout", admin(), null, null).status);
    }

    @Test public void userSessionRevocationRejectsOldToken() throws Exception {
        RemoteUserStore users = RemoteUserStore.get(context);
        RemoteUser draft = new RemoteUser();
        draft.username = "viewer1"; draft.displayName = "Viewer"; draft.role = "viewer";
        RemoteUser user = users.create(draft, "password12345");
        RemoteSessionStore.Created created = RemoteSessionStore.get(context).create(RemoteSession.SUBJECT_USER, user.id, 60000, "127.0.0.1", null, null);
        assertEquals(200, call("GET", DataApi.BASE + "/remote/dashboard", bearer(created.token), null, null).status);
        assertEquals(200, call("DELETE", DataApi.BASE + "/remote/users/" + user.id + "/sessions", admin(), null, null).status);
        assertEquals(403, call("GET", DataApi.BASE + "/remote/dashboard", bearer(created.token), null, null).status);
    }

    @Test public void remoteFilesRejectTraversalAndRootDelete() throws Exception {
        Resp list = call("GET", DataApi.BASE + "/remote/files/list?path=..%2F..%2Fetc", admin(), null, null);
        assertTrue(list.status == 400 || list.status == 403 || list.status == 404);
        Resp delRoot = call("POST", DataApi.BASE + "/remote/files/delete", admin(), new JSONObject().put("path","").toString(), null);
        assertTrue(delRoot.status == 400 || delRoot.status == 403);
    }

    @Test public void pairingStatusRejectsInvalidCode() throws Exception {
        Resp resp = call("POST", DataApi.BASE + "/remote/pairing/status", null, new JSONObject().put("code","INVALID").toString(), null);
        assertEquals(401, resp.status);
    }

    @Test public void pairingRejectAndUnknownActionsAreHandled() throws Exception {
        Resp reqResp = call("POST", DataApi.BASE + "/remote/pairing/request", null, new JSONObject().put("deviceName","X").toString(), null);
        String id = new JSONObject(reqResp.body).getString("id");
        assertEquals(200, call("POST", DataApi.BASE + "/remote/pairing/" + id + "/reject", admin(), null, null).status);
        assertEquals(404, call("POST", DataApi.BASE + "/remote/pairing/unknown/approve", admin(), new JSONObject().toString(), null).status);
        assertEquals(404, call("POST", DataApi.BASE + "/remote/pairing/unknown/reject", admin(), null, null).status);
    }

    @Test public void consumedPairingCodeCannotBeReused() throws Exception {
        Resp reqResp = call("POST", DataApi.BASE + "/remote/pairing/request", null, new JSONObject().put("deviceName","Reuse").toString(), null);
        String code = new JSONObject(reqResp.body).getString("code");
        String id = new JSONObject(reqResp.body).getString("id");
        assertEquals(200, call("POST", DataApi.BASE + "/remote/pairing/" + id + "/approve", admin(), new JSONObject().put("deviceName","Reuse").toString(), null).status);
        Resp first = call("POST", DataApi.BASE + "/remote/pairing/status", null, new JSONObject().put("code", code).toString(), null);
        assertEquals(200, first.status);
        assertTrue(new JSONObject(first.body).has("token"));
        Resp second = call("POST", DataApi.BASE + "/remote/pairing/status", null, new JSONObject().put("code", code).toString(), null);
        assertTrue(second.status == 401 || second.status == 409);
    }

    @Test public void expiredPairingCodeCannotBeApprovedOrUsed() throws Exception {
        RemotePairingStore.Created created = RemotePairingStore.get(context).create("Expired", 1000);
        RemotePairingRequest req = RemotePairingStore.get(context).get(created.request.id);
        req.expiresAt = System.currentTimeMillis() - 1000;
        java.io.File f = new java.io.File(context.getFilesDir(), "remote/pairing.json");
        org.json.JSONObject root = new org.json.JSONObject();
        if (f.exists()) root = com.yadaventerprise.phoneserver.data.database.JsonStorage.readJson(f);
        org.json.JSONArray arr = root.optJSONArray("requests");
        for (int i=0; arr!=null && i<arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o != null && created.request.id.equals(o.optString("id"))) o.put("expiresAt", req.expiresAt);
        }
        com.yadaventerprise.phoneserver.data.database.JsonStorage.writeJsonAtomic(f, root);
        RemotePairingStore.reset();

        Resp approve = call("POST", DataApi.BASE + "/remote/pairing/" + created.request.id + "/approve", admin(), new JSONObject().put("deviceName","Expired").toString(), null);
        assertTrue(approve.status == 400 || approve.status == 404 || approve.status == 409);
        Resp status = call("POST", DataApi.BASE + "/remote/pairing/status", null, new JSONObject().put("code", created.code).toString(), null);
        assertTrue(status.status == 401 || status.status == 404 || status.status == 410);
    }
}
