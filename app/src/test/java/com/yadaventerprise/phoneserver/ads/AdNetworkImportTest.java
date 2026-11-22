package com.yadaventerprise.phoneserver.ads;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
public class AdNetworkImportTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        AdDb.reset();
        File dir = new File(context.getFilesDir(), "adnet");
        File f = new File(dir, "adnet.json");
        if (f.exists()) f.delete();
    }

    @Test
    public void importMergeCreateUpdateReplace() throws Exception {
        AdDb db = AdDb.get(context); db.open();
        long u1 = db.insert(AdDb.USERS, AdHttp.obj("email", "pub@example.com", "role", "publisher", "pass_hash", "hash1"));
        long s10 = db.insert(AdDb.SITES, AdHttp.obj("owner_id", u1, "name", "Site A", "domain", "a.example", "status", "approved"));
        long c100 = db.insert(AdDb.CAMPAIGNS, AdHttp.obj("advertiser_id", u1, "name", "Camp A", "status", "draft", "total_budget_micros", 1000L));

        JSONObject cfg = baseConfig(u1);
        cfg.getJSONArray("sites").put(site(s10, u1, "Site A Updated", "a.example"));
        cfg.getJSONArray("sites").put(site(s10 + 1000, u1, "Site B", "b.example"));
        cfg.getJSONArray("campaigns").put(campaign(c100, u1, "Camp A"));
        runImport(cfg, "create_only");
        assertEquals("Site A", AdDb.get(context).copy(AdDb.SITES, s10).optString("name"));
        assertNotNull(AdDb.get(context).copy(AdDb.SITES, s10 + 1000));

        JSONObject upd = baseConfig(u1);
        upd.getJSONArray("sites").put(site(s10, u1, "Site A2", "a.example"));
        upd.getJSONArray("sites").put(site(s10 + 2000, u1, "Site C", "c.example"));
        upd.getJSONArray("campaigns").put(campaign(c100, u1, "Camp A"));
        runImport(upd, "update_only");
        assertEquals("Site A2", AdDb.get(context).copy(AdDb.SITES, s10).optString("name"));
        assertNull(AdDb.get(context).copy(AdDb.SITES, s10 + 2000));

        JSONObject repl = baseConfig(u1);
        long s20 = s10 + 3000; long c200 = c100 + 3000;
        repl.getJSONArray("sites").put(site(s20, u1, "Only", "only.example"));
        repl.getJSONArray("campaigns").put(campaign(c200, u1, "Camp New"));
        repl.getJSONArray("placements").put(placement(s20 + 1, s20, "P"));
        repl.getJSONArray("creatives").put(creative(c200 + 1, c200));
        runImport(repl, "replace");
        assertNull(AdDb.get(context).copy(AdDb.SITES, s10));
        assertNotNull(AdDb.get(context).copy(AdDb.SITES, s20));
    }

    @Test
    public void invalidReferenceRollsBack() throws Exception {
        AdDb db = AdDb.get(context); db.open();
        long u1 = db.insert(AdDb.USERS, AdHttp.obj("email", "pub@example.com", "role", "publisher")); JSONObject dummy=baseConfig(u1);
        JSONObject cfg = baseConfig(u1);
        cfg.getJSONArray("sites").put(site(10, 999, "Bad", "bad.example"));
        try {
            runImport(cfg, "merge");
            fail("expected");
        } catch (Exception expected) {
        }
        assertEquals(0, AdDb.get(context).count(AdDb.SITES));
    }

    private JSONObject baseConfig(long userId) throws Exception {
        JSONObject cfg = new JSONObject();
        cfg.put("users", new JSONArray().put(user(userId)));
        cfg.put("sites", new JSONArray());
        cfg.put("placements", new JSONArray());
        cfg.put("campaigns", new JSONArray());
        cfg.put("creatives", new JSONArray());
        return cfg;
    }

    private JSONObject user(long id) throws Exception {
        return new JSONObject().put("id", id).put("email", "u"+id+"@example.com").put("role", "publisher");
    }
    private JSONObject site(long id, long owner, String name, String domain) throws Exception {
        return new JSONObject().put("id", id).put("owner_id", owner).put("name", name).put("domain", domain).put("status", "approved");
    }
    private JSONObject campaign(long id, long adv, String name) throws Exception {
        return new JSONObject().put("id", id).put("advertiser_id", adv).put("name", name).put("status", "draft").put("total_budget_micros", 1000);
    }
    private JSONObject placement(long id, long siteId, String name) throws Exception {
        return new JSONObject().put("id", id).put("site_id", siteId).put("name", name).put("format", "banner").put("width", 300).put("height", 250).put("key", "pl_0123456789abcdef").put("status", "active");
    }
    private JSONObject creative(long id, long campaignId) throws Exception {
        return new JSONObject().put("id", id).put("campaign_id", campaignId).put("type", "banner").put("title", "t").put("status", "pending").put("width", 300).put("height", 250);
    }

    private void runImport(JSONObject cfg, String strategy) throws Exception {
        File recovery = tmp.newFolder("rec-" + strategy + "-" + System.nanoTime());
        List<Runnable> rollback = new ArrayList<>();
        try {
            AdNetworkManager.importConfig(context, cfg, strategy, recovery, rollback);
        } catch (Exception e) {
            for (int i = rollback.size() - 1; i >= 0; i--) { try { rollback.get(i).run(); } catch (Exception ignored) {} }
            throw e;
        }
    }
}
