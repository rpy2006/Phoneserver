package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.security.TokenPolicyStore;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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
public class LegacyApiAuthTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;
    private SimpleHttpServer server;

    @Before public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ContentStore.setPassword(context, "admin");
        ContentStore.setAllowRemoteAccess(context, true);
        server = new SimpleHttpServer(context, 0);
    }

    private String route(SimpleHttpServer.Request req) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { server.route(req, out); } catch (Exception e) { throw new AssertionError(e); }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    private static SimpleHttpServer.Request req(String method, String path) {
        SimpleHttpServer.Request r = new SimpleHttpServer.Request();
        r.method = method; r.path = path; r.remoteIp = "10.44.0.1"; return r;
    }

    @Test public void readOnlyTokenCannotMutateLegacyFileApis() throws Exception {
        TokenStore.Created ro = TokenStore.create(context, "legacy-ro", null, false);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        p.tokenId = ro.record.id;
        p.permissions.add(TokenPolicy.FILES_READ);
        p.permissions.remove(TokenPolicy.FILES_WRITE);
        TokenPolicyStore.get(context).save(p);
        String auth = "Bearer " + ro.token;

        File root = ContentStore.getPublicFilesDir(context);
        File target = new File(root, "keep.txt");
        try (FileOutputStream fos = new FileOutputStream(target)) { fos.write("x".getBytes(StandardCharsets.UTF_8)); }

        SimpleHttpServer.Request del = req("POST", "/api/delete");
        del.headers.put("authorization", auth);
        del.body = "{\"path\":\"keep.txt\"}".getBytes(StandardCharsets.UTF_8);
        String resp = route(del);
        assertTrue(resp.startsWith("HTTP/1.1 401") || resp.startsWith("HTTP/1.1 403"));
        assertTrue(target.exists());

        SimpleHttpServer.Request mkdir = req("POST", "/api/mkdir");
        mkdir.headers.put("authorization", auth);
        mkdir.body = "{\"path\":\"\",\"name\":\"newdir\"}".getBytes(StandardCharsets.UTF_8);
        String mresp = route(mkdir);
        assertTrue(mresp.startsWith("HTTP/1.1 401") || mresp.startsWith("HTTP/1.1 403"));
        assertFalse(new File(root, "newdir").exists());
    }

    @Test public void remoteAccessDisabledBlocksLegacyAndWebdav() {
        ContentStore.setAllowRemoteAccess(context, false);
        SimpleHttpServer.Request browse = req("GET", "/api/browse");
        browse.headers.put("x-auth-password", "admin");
        assertTrue(route(browse).startsWith("HTTP/1.1 403"));
        SimpleHttpServer.Request dav = req("PROPFIND", "/dav/");
        dav.headers.put("x-auth-password", "admin");
        assertTrue(route(dav).startsWith("HTTP/1.1 403"));
    }

    @Test public void customApiRequiresAuthAndRemoteAccess() {
        ContentStore.setAllowRemoteAccess(context, false);
        assertTrue(route(req("GET", "/api/custom-missing")).startsWith("HTTP/1.1 403"));
        ContentStore.setAllowRemoteAccess(context, true);
        ContentStore.setPassword(context, "secret");
        assertTrue(route(req("GET", "/api/custom-missing")).startsWith("HTTP/1.1 401"));
    }

    @Test public void readOnlyTokenCannotWriteWebDav() throws Exception {
        TokenStore.Created ro = TokenStore.create(context, "dav-ro", null, false);
        TokenPolicy p = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        p.tokenId = ro.record.id;
        p.permissions.add(TokenPolicy.FILES_READ);
        p.permissions.remove(TokenPolicy.FILES_WRITE);
        TokenPolicyStore.get(context).save(p);

        SimpleHttpServer.Request mkcol = req("MKCOL", "/dav/newfolder");
        mkcol.headers.put("authorization", "Bearer " + ro.token);
        String resp = route(mkcol);
        assertTrue(resp.startsWith("HTTP/1.1 401") || resp.startsWith("HTTP/1.1 403"));

        SimpleHttpServer.Request put = req("PUT", "/dav/test.txt");
        put.headers.put("authorization", "Bearer " + ro.token);
        put.body = "hello".getBytes(StandardCharsets.UTF_8);
        String putResp = route(put);
        assertTrue(putResp.startsWith("HTTP/1.1 401") || putResp.startsWith("HTTP/1.1 403"));
        assertFalse(new File(ContentStore.getPublicFilesDir(context), "test.txt").exists());
    }

    @Test public void pathTraversalRejectedByLegacyApi() throws Exception {
        SimpleHttpServer.Request browse = req("GET", "/api/browse?path=../../..");
        browse.headers.put("x-auth-password", "admin");
        String resp = route(browse);
        assertTrue(resp.startsWith("HTTP/1.1 400") || resp.startsWith("HTTP/1.1 403") || resp.startsWith("HTTP/1.1 404"));
    }
}
