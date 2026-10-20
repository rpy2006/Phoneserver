package com.yadaventerprise.phoneserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.yadaventerprise.phoneserver.data.DataTestSupport;
import com.yadaventerprise.phoneserver.data.ProjectStore;
import com.yadaventerprise.phoneserver.data.TokenStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end tests of the {@code /api/v1} HTTP surface: authentication,
 * project scoping, CORS, oversized bodies and rate limiting.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DataApiTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;
    private final AtomicInteger ipCounter = new AtomicInteger();

    @Before
    public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ContentStore.setPassword(context, "");
        ContentStore.setApiRateLimitPerMinute(context, 240);
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
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = method;
        req.path = path;
        req.remoteIp = "10.0.0." + ipCounter.incrementAndGet();
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
        return call("GET", path, headers, null, null);
    }

    private static Map<String, String> header(String key, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    public void healthProbeIsPublic() {
        assertEquals(200, get(DataApi.BASE + "/health", null).status);
    }

    @Test
    public void unauthenticatedAccessIsRejectedWhenNoPasswordIsConfigured() {
        Response response = get(DataApi.BASE + "/projects", null);
        assertEquals(401, response.status);
        assertTrue(response.text.contains("unauthorized"));
    }

    @Test
    public void wrongPasswordIsRejectedAndCorrectPasswordGrantsAccess() throws Exception {
        String project = ProjectStore.create(context, "Alpha", "", 0, 0).id;
        ContentStore.setPassword(context, "secret");

        assertEquals(401, get(DataApi.BASE + "/projects",
                header("x-auth-password", "wrong")).status);

        Response ok = get(DataApi.BASE + "/projects",
                header("x-auth-password", "secret"));
        assertEquals(200, ok.status);
        assertTrue("the project list should be returned", ok.text.contains(project));
    }

    @Test
    public void apiTokensAuthenticateAndBadTokensDoNot() throws Exception {
        ProjectStore.create(context, "Alpha", "", 0, 0);
        TokenStore.Created token = TokenStore.create(context, "web", null, true);

        Response ok = get(DataApi.BASE + "/projects",
                header("authorization", "Bearer " + token.token));
        assertEquals(200, ok.status);

        assertEquals(401, get(DataApi.BASE + "/projects",
                header("authorization", "Bearer pst_not-a-real-token")).status);
    }

    @Test
    public void projectScopedTokenCannotTouchOtherProjectsOrCreateProjects() throws Exception {
        String alpha = ProjectStore.create(context, "Alpha", "", 0, 0).id;
        String beta = ProjectStore.create(context, "Beta", "", 0, 0).id;
        TokenStore.Created scoped = TokenStore.create(context, "alpha-only", alpha, true);
        Map<String, String> auth = header("authorization", "Bearer " + scoped.token);

        assertEquals(200, get(DataApi.BASE + "/projects/" + alpha, auth).status);
        assertEquals("cross-project read must be forbidden",
                403, get(DataApi.BASE + "/projects/" + beta, auth).status);

        Response create = call("POST", DataApi.BASE + "/projects", auth, null,
                "{\"name\":\"Gamma\"}");
        assertEquals(403, create.status);
        assertEquals(2, ProjectStore.list(context).size());
    }

    @Test
    public void oversizedBufferedBodyIsRejected() {
        SimpleHttpServer.Request req = new SimpleHttpServer.Request();
        req.method = "POST";
        req.path = DataApi.BASE + "/projects";
        req.remoteIp = "10.0.0.99";
        req.bodyTooLarge = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            DataApi.handle(context, req, out);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue("expected a 413 response", text.startsWith("HTTP/1.1 413"));
    }

    @Test
    public void rateLimitReturns429AfterTheWindowIsExhausted() {
        ContentStore.setApiRateLimitPerMinute(context, 10);
        Map<String, String> none = null;
        for (int i = 0; i < 10; i++) {
            assertEquals("request " + i + " should pass the limiter",
                    401, get(DataApi.BASE + "/projects", none).status);
        }
        assertEquals("the 11th request must be throttled",
                429, get(DataApi.BASE + "/projects", none).status);
    }

    @Test
    public void corsOnlyAllowsConfiguredOrigins() {
        ContentStore.setAllowedOrigins(context, "https://allowed.example");

        Response allowed = get(DataApi.BASE + "/health",
                header("origin", "https://allowed.example"));
        assertEquals(200, allowed.status);
        assertTrue("allowed origin should be echoed",
                allowed.text.contains("Access-Control-Allow-Origin: https://allowed.example"));

        Response denied = get(DataApi.BASE + "/health",
                header("origin", "https://evil.example"));
        assertEquals(200, denied.status);
        assertFalse("disallowed origin must not be echoed",
                denied.text.contains("Access-Control-Allow-Origin: https://evil.example"));
    }

    @Test
    public void preflightIsAnswered() {
        Response response = call("OPTIONS", DataApi.BASE + "/projects",
                header("origin", "https://allowed.example"), null, null);
        assertEquals(204, response.status);
        assertTrue(response.text.contains("Access-Control-Allow-Methods"));
    }

    @Test
    public void unknownEndpointIs404AndBadMethodIs405() throws Exception {
        ProjectStore.create(context, "Alpha", "", 0, 0);
        ContentStore.setPassword(context, "secret");
        Map<String, String> auth = header("x-auth-password", "secret");

        assertEquals(404, get(DataApi.BASE + "/does-not-exist", auth).status);
        assertEquals(405, call("DELETE", DataApi.BASE + "/health", auth, null, null).status);
    }
}
