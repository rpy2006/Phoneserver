package com.yadaventerprise.phoneserver.data.developer;

import static org.junit.Assert.*;

import android.content.Context;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
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
public class ExtensionStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;

    @Before public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ExtensionStore.reset();
    }

    @Test public void manifestExtensionRequiresApprovalAndBlocksExecutableCode() throws Exception {
        ExtensionStore store = ExtensionStore.get(context);
        JSONObject manifest = new JSONObject();
        manifest.put("id", "com.test.ext");
        manifest.put("name", "Test Ext");
        manifest.put("version", "1.0.0");
        manifest.put("permissions", new JSONArray().put("projects.read"));
        ExtensionRecord r = store.install(manifest, "test");
        assertFalse(r.enabled);
        assertFalse(r.approved);
        try { store.setEnabled(r.id, true, false, "test"); fail(); } catch (IllegalArgumentException expected) {}
        store.approve(r.id, true, "admin");
        ExtensionRecord enabled = store.setEnabled(r.id, true, false, "admin");
        assertTrue(enabled.enabled);
        assertTrue(enabled.approved);

        JSONObject bad = new JSONObject(manifest.toString());
        bad.put("id", "com.test.bad");
        bad.put("code", "rm -rf /");
        try { store.install(bad, "test"); fail(); } catch (IllegalArgumentException expected) {}
    }

    @Test public void extensionActivityIsRecorded() throws Exception {
        ExtensionStore store = ExtensionStore.get(context);
        JSONObject manifest = new JSONObject();
        manifest.put("id", "com.test.log");
        manifest.put("name", "Log Ext");
        manifest.put("version", "1.0.0");
        store.install(manifest, "tester");
        assertFalse(store.activity(10).isEmpty());
        assertEquals("install", store.activity(10).get(0).optString("action"));
    }
}
