package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
public class MailApiAuthTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
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

    private int status(String method, String path, Map<String,String> headers, String body) {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = method;
        req.path = path;
        req.remoteIp = "10.40.0." + ipCounter.incrementAndGet();
        if (headers != null) req.headers.putAll(headers);
        if (body != null) req.body = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { DataApi.handle(context, req, out); }
        catch (Exception e) { throw new AssertionError(e); }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        return Integer.parseInt(text.split("\r\n")[0].split(" ")[1]);
    }

    private static Map<String,String> bearer(String token) {
        Map<String,String> h = new HashMap<>(); h.put("authorization","Bearer "+token); return h;
    }

    @Test
    public void mailEndpointsRequireAuthAndPermissions() throws Exception {
        assertEquals(401, status("GET", DataApi.BASE + "/mail/accounts", null, null));
        assertEquals(401, status("GET", DataApi.BASE + "/mail/messages/x", bearer("pst_invalid"), null));

        TokenStore.Created noMail = TokenStore.create(context, "nomail", null, false);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        p.tokenId = noMail.record.id;
        p.permissions.remove(TokenPolicy.MAIL_READ);
        p.permissions.remove(TokenPolicy.MAIL_WRITE);
        TokenPolicyStore.get(context).save(p);
        Map<String,String> auth = bearer(noMail.token);
        assertEquals(403, status("GET", DataApi.BASE + "/mail/accounts", auth, null));
        assertEquals(403, status("POST", DataApi.BASE + "/mail/accounts", auth, "{}"));
        assertEquals(403, status("GET", DataApi.BASE + "/mail/messages/m1", auth, null));
        assertEquals(403, status("POST", DataApi.BASE + "/mail/messages/m1/read", auth, "{\"seen\":true}"));
        assertEquals(403, status("DELETE", DataApi.BASE + "/mail/messages/m1", auth, null));
    }

    @Test
    public void mailReadOnlyCannotWrite() throws Exception {
        TokenStore.Created ro = TokenStore.create(context, "mailro", null, false);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        p.tokenId = ro.record.id;
        p.permissions.add(TokenPolicy.MAIL_READ);
        p.permissions.remove(TokenPolicy.MAIL_WRITE);
        TokenPolicyStore.get(context).save(p);
        Map<String,String> auth = bearer(ro.token);
        int read = status("GET", DataApi.BASE + "/mail/accounts", auth, null);
        assertNotEquals(403, read); // may be 200 with empty list
        assertEquals(403, status("POST", DataApi.BASE + "/mail/accounts", auth, "{\"name\":\"a\"}"));
        assertEquals(403, status("POST", DataApi.BASE + "/mail/accounts/x/sync", auth, "{}"));
        assertEquals(403, status("DELETE", DataApi.BASE + "/mail/accounts/x", auth, null));
    }
}
