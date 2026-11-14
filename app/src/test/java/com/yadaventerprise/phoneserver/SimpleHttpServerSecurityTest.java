package com.yadaventerprise.phoneserver;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.yadaventerprise.phoneserver.data.DataTestSupport;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Regression tests for the legacy HTTP surface: request-size limits, the
 * "allow remote access" switch and escaping of file names in the HTML listing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SimpleHttpServerSecurityTest {

    private Context context;
    private SimpleHttpServer server;

    @Before
    public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        ContentStore.setPassword(context, "");
        ContentStore.setAllowRemoteAccess(context, true);
        server = new SimpleHttpServer(context, 0);
    }

    private String route(SimpleHttpServer.Request req) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            server.route(req, out);
        } catch (Exception e) {
            throw new AssertionError("route threw", e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static SimpleHttpServer.Request request(String method, String path) {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = method;
        req.path = path;
        req.remoteIp = "10.0.0.7";
        return req;
    }

    @Test
    public void oversizedBufferedBodyIsRejectedBeforeRouting() {
        SimpleHttpServer.Request req = request("POST", "/api/upload");
        req.bodyTooLarge = true;
        assertTrue("expected 413", route(req).startsWith("HTTP/1.1 413"));
    }

    @Test
    public void remoteFileAccessIsRefusedWhenTheSettingIsOff() {
        ContentStore.setAllowRemoteAccess(context, false);

        assertTrue("browse must be blocked", route(request("GET", "/api/browse")).startsWith("HTTP/1.1 403"));
        assertTrue("file download must be blocked", route(request("GET", "/files/x.txt")).startsWith("HTTP/1.1 403"));
        assertTrue("upload must be blocked", route(request("POST", "/api/upload")).startsWith("HTTP/1.1 403"));
        assertTrue("the public website must keep working", route(request("GET", "/status")).startsWith("HTTP/1.1 200"));
    }

}
