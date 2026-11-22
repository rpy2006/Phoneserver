package com.yadaventerprise.phoneserver.data.storage;

import static org.junit.Assert.*;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.remote.RemoteUser;
import com.yadaventerprise.phoneserver.data.remote.RemoteUserStore;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import com.yadaventerprise.phoneserver.network.http.DataException;
import java.io.File;
import java.util.Collections;
import org.json.JSONArray;
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
public class ImportExportServiceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;
    private String projectId;

    @Before
    public void setUp() throws Exception {
        com.yadaventerprise.phoneserver.testing.DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        com.yadaventerprise.phoneserver.testing.DataTestSupport.freshDataRoot(context, tmp.getRoot());
        RemoteUserStore.reset(); ScheduledTaskStore.reset(); WebhookStore.reset();
        Records.ProjectRecord p = ProjectStore.create(context, "Import Test", "desc", 0, 0);
        projectId = p.id;
    }

    @Test
    public void previewDoesNotModifyProduction() throws Exception {
        seedUser("alice");
        JSONObject body = new JSONObject();
        body.put("strategy", "replace");
        body.put("users", new JSONArray().put(userJson("u2", "bob")));
        ImportExportService.previewConfigImport(context, projectId, body);
        assertEquals(1, RemoteUserStore.get(context).all().size());
        assertEquals("alice", RemoteUserStore.get(context).all().get(0).username);
    }

    @Test
    public void applyCreateOnlyMergeUpdateReplace() throws Exception {
        seedUserWithId("u1", "alice");
        seedWebhook("w1", "https://example.com/a");

        JSONObject createOnly = new JSONObject();
        createOnly.put("strategy", "create_only");
        createOnly.put("users", new JSONArray().put(userJson("u1", "alice-renamed")).put(userJson("u2", "bob")));
        ImportExportService.applyConfigImport(context, projectId, createOnly);
        assertEquals(2, RemoteUserStore.get(context).all().size());
        assertEquals("alice", findUser("u1").username);

        JSONObject updateOnly = new JSONObject();
        updateOnly.put("strategy", "update_only");
        updateOnly.put("users", new JSONArray().put(userJson("u1", "alice-updated")).put(userJson("u3", "carol")));
        ImportExportService.applyConfigImport(context, projectId, updateOnly);
        assertEquals(2, RemoteUserStore.get(context).all().size());
        assertEquals("alice-updated", findUser("u1").username);
        assertNull(findUser("u3"));

        JSONObject merge = new JSONObject();
        merge.put("strategy", "merge");
        JSONArray hooks = new JSONArray();
        JSONObject w1 = webhookJson("w1", "https://example.com/updated");
        w1.put("enabled", false);
        hooks.put(w1);
        hooks.put(webhookJson("w2", "https://example.com/b"));
        merge.put("webhooks", hooks);
        ImportExportService.applyConfigImport(context, projectId, merge);
        assertEquals(2, WebhookStore.get(context).all().size());
        assertEquals("https://example.com/updated", WebhookStore.get(context).get("w1").url);

        JSONObject replace = new JSONObject();
        replace.put("strategy", "replace");
        replace.put("webhooks", new JSONArray().put(webhookJson("w3", "https://example.com/c")));
        ImportExportService.applyConfigImport(context, projectId, replace);
        assertEquals(1, WebhookStore.get(context).all().size());
        assertNotNull(WebhookStore.get(context).get("w3"));
        assertNull(WebhookStore.get(context).get("w1"));
    }

    @Test
    public void invalidImportIsRejectedAndPreservesData() throws Exception {
        seedUser("alice");
        JSONObject body = new JSONObject();
        body.put("strategy", "merge");
        JSONArray users = new JSONArray();
        JSONObject bad = userJson("u2", "bob");
        bad.put("password", "plaintext-not-allowed");
        users.put(bad);
        body.put("users", users);
        try {
            ImportExportService.applyConfigImport(context, projectId, body);
            fail("expected validation failure");
        } catch (DataException expected) {
            assertTrue(expected.httpStatus() == 422);
        }
        assertEquals(1, RemoteUserStore.get(context).all().size());
        assertEquals("alice", RemoteUserStore.get(context).all().get(0).username);
    }

    @Test
    public void duplicateRecordsRejected() throws Exception {
        JSONObject body = new JSONObject();
        body.put("strategy", "merge");
        JSONArray users = new JSONArray();
        users.put(userJson("u1", "alice"));
        users.put(userJson("u1", "alice2"));
        body.put("users", users);
        try {
            ImportExportService.applyConfigImport(context, projectId, body);
            fail("expected duplicate rejection");
        } catch (DataException expected) {
            assertEquals(422, expected.httpStatus());
        }
    }

    @Test
    public void rollbackOnFailurePreservesOriginal() throws Exception {
        seedWebhook("w1", "https://example.com/original");
        JSONObject body = new JSONObject();
        body.put("strategy", "merge");
        JSONArray hooks = new JSONArray();
        hooks.put(webhookJson("w2", "https://example.com/new"));
        JSONObject bad = webhookJson("bad", "http://evil.example/hook");
        hooks.put(bad);
        body.put("webhooks", hooks);
        try {
            ImportExportService.applyConfigImport(context, projectId, body);
            fail("expected");
        } catch (DataException expected) {
            assertEquals(422, expected.httpStatus());
        }
        assertEquals(1, WebhookStore.get(context).all().size());
        assertEquals("https://example.com/original", WebhookStore.get(context).get("w1").url);
        assertNull(WebhookStore.get(context).get("w2"));
    }

    private void seedUser(String username) {
        RemoteUser u = new RemoteUser();
        u.username = username;
        u.displayName = username;
        u.role = "viewer";
        u.status = "active";
        RemoteUserStore.get(context).create(u, "password12345");
    }

    private void seedUserWithId(String id, String username) throws Exception {
        JSONObject body = new JSONObject();
        body.put("strategy", "merge");
        body.put("users", new JSONArray().put(userJson(id, username)));
        ImportExportService.applyConfigImport(context, projectId, body);
    }

    private RemoteUser findUser(String id) {
        return RemoteUserStore.get(context).get(id);
    }

    private void seedWebhook(String id, String url) {
        WebhookConfig c = new WebhookConfig();
        c.id = id; c.name = id; c.url = url; c.enabled = true; c.events.add("system.health_changed");
        WebhookStore.get(context).save(c);
    }

    private JSONObject userJson(String id, String username) throws Exception {
        JSONObject u = new JSONObject();
        u.put("id", id);
        u.put("username", username);
        u.put("displayName", username);
        u.put("role", "viewer");
        u.put("status", "active");
        u.put("passwordHash", "pbkdf2$sha256$120000$salt$hash");
        return u;
    }

    private JSONObject webhookJson(String id, String url) throws Exception {
        JSONObject w = new JSONObject();
        w.put("id", id);
        w.put("name", id);
        w.put("url", url);
        w.put("enabled", true);
        w.put("events", new JSONArray().put("system.health_changed"));
        return w;
    }
}
