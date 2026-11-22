package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.security.TokenPolicyStore;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
public class SystemApiAuthTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;
    private final AtomicInteger ipCounter = new AtomicInteger();

    @Before public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ContentStore.setPassword(context, "admin");
        ContentStore.setApiRateLimitPerMinute(context, 5000);
    }

    private int status(String method, String path, Map<String,String> headers, String body) {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = method; req.path = path; req.remoteIp = "10.41.0." + ipCounter.incrementAndGet();
        if (headers != null) req.headers.putAll(headers);
        if (body != null) req.body = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { DataApi.handle(context, req, out); } catch (Exception e) { throw new AssertionError(e); }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        return Integer.parseInt(text.split("\r\n")[0].split(" ")[1]);
    }
    private static Map<String,String> bearer(String token) { Map<String,String> h=new HashMap<>(); h.put("authorization","Bearer "+token); return h; }

    @Test public void systemReadWriteAndAdminBoundaries() throws Exception {
        TokenStore.Created viewer = TokenStore.create(context, "v", null, false);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        p.tokenId = viewer.record.id; p.permissions.add(TokenPolicy.SYSTEM_READ);
        TokenPolicyStore.get(context).save(p);
        Map<String,String> auth = bearer(viewer.token);

        assertEquals(200, status("GET", DataApi.BASE + "/system/monitor", auth, null));
        assertEquals(200, status("GET", DataApi.BASE + "/system/diagnostics", auth, null));
        assertEquals(200, status("GET", DataApi.BASE + "/system/logs", auth, null));
        assertEquals(200, status("GET", DataApi.BASE + "/system/maintenance", auth, null));
        assertEquals(403, status("POST", DataApi.BASE + "/system/start", auth, "{}"));
        assertEquals(403, status("POST", DataApi.BASE + "/system/stop", auth, "{}"));
        assertEquals(403, status("POST", DataApi.BASE + "/system/restart", auth, "{}"));
        assertEquals(403, status("POST", DataApi.BASE + "/system/maintenance/clean", auth, "{\"confirm\":true}"));
        assertEquals(403, status("POST", DataApi.BASE + "/system/maintenance/verify-backups", auth, "{}"));
        assertEquals(403, status("PATCH", DataApi.BASE + "/system/automation", auth, "{\"tasksEnabled\":false}"));
        assertEquals(403, status("POST", DataApi.BASE + "/system/tasks", auth, "{\"name\":\"t\",\"type\":\"cleanup\",\"scheduleType\":\"manual\"}"));
        assertEquals(403, status("POST", DataApi.BASE + "/system/webhooks", auth, "{\"name\":\"w\",\"url\":\"http://127.0.0.1/\",\"events\":[\"test\"]}"));
    }

    @Test public void missingTokenRejectedOnSystem() throws Exception {
        assertEquals(401, status("GET", DataApi.BASE + "/system/monitor", null, null));
        assertEquals(401, status("POST", DataApi.BASE + "/system/start", null, "{}"));
    }
}
