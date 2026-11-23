package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
public class PublicRouteSecurityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;

    @Before
    public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ContentStore.setPassword(context, "admin");
    }

    @Test
    public void pairingRouteRejectsInvalidCode() throws Exception {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = "POST";
        req.path = PairingApi.PATH;
        req.remoteIp = "10.1.2.3";
        req.body = "{\"code\":\"BAD-CODE\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PairingApi.handle(context, req, out);
        String text = out.toString("UTF-8");
        assertTrue(text.startsWith("HTTP/1.1 401") || text.startsWith("HTTP/1.1 429") || text.startsWith("HTTP/1.1 400"));
    }

    @Test
    public void shareRouteRejectsInvalidToken() throws Exception {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = "GET";
        req.path = "/s/invalid";
        req.remoteIp = "10.1.2.4";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ShareHandler.handle(context, req, out, false);
        String text = out.toString("UTF-8");
        assertTrue(text.contains("404") || text.toLowerCase().contains("not found"));
    }

    @Test
    public void statusEndpointIsPublic() throws Exception {
        SimpleHttpServer server = new SimpleHttpServer(context, 0);
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = "GET"; req.path = "/status"; req.remoteIp = "10.1.2.5";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.route(req, out);
        String text = out.toString("UTF-8");
        assertTrue(text.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void shareWriteMethodsAreRejected() throws Exception {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = "POST"; req.path = "/s/pss_" + "1".repeat(64); req.remoteIp = "10.1.2.6";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ShareHandler.handle(context, req, out, false);
        String text = out.toString("UTF-8");
        assertTrue(text.contains("401") || text.contains("404") || text.contains("405") || text.toLowerCase().contains("password"));
    }

    @Test
    public void sharePutDeleteMethodsAreRejectedForInvalidToken() throws Exception {
        for (String m : new String[]{"PUT","DELETE","PATCH"}) {
            SimpleHttpServer.Request req = new SimpleHttpServer.Request();
            req.method = m; req.path = "/s/pss_" + "2".repeat(64); req.remoteIp = "10.1.2.7";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ShareHandler.handle(context, req, out, false);
            String text = out.toString("UTF-8");
            assertTrue(text.contains("404") || text.contains("405"));
        }
    }

    @Test
    public void pairingGetMethodRejected() throws Exception {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = "GET"; req.path = PairingApi.PATH; req.remoteIp = "10.1.2.8";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PairingApi.handle(context, req, out);
        String text = out.toString("UTF-8");
        assertTrue(text.startsWith("HTTP/1.1 405"));
    }
}
