package com.yadaventerprise.phoneserver.data.storage.validation;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ImportValidationTest {

    @Test
    public void jsonConfigValidatorRejectsInvalid() throws Exception {
        JSONObject cfg = new JSONObject();
        cfg.put("schemaVersion", 99);
        cfg.put("appName", "");
        cfg.put("apiBasePath", "relative");
        cfg.put("defaultProjectQuotaBytes", -1);
        cfg.put("maxUploadBytes", "bad");
        cfg.put("features", "nope");
        cfg.put("unknown", true);
        ValidationResult r = JsonConfigValidator.validate(cfg, "app_config");
        assertFalse(r.isValid());
        assertTrue(r.errorCount() >= 5);
    }

    @Test
    public void userValidatorRejectsDuplicateAndPlaintext() throws Exception {
        JSONArray users = new JSONArray();
        JSONObject u1 = new JSONObject();
        u1.put("id", "u1"); u1.put("username", "alice"); u1.put("role", "viewer"); u1.put("status", "active"); u1.put("passwordHash", "hash");
        JSONObject u2 = new JSONObject(u1.toString());
        u2.put("password", "plain");
        users.put(u1); users.put(u2);
        ImportPlan plan = UserConfigValidator.validate(users, Collections.emptySet(), Collections.emptySet(), "merge");
        assertTrue(plan.hasErrors());
    }

    @Test
    public void permissionValidatorRejectsEscalationAndBadRef() throws Exception {
        JSONArray perms = new JSONArray();
        JSONObject p = new JSONObject();
        p.put("tokenId", "p1"); p.put("role", "viewer"); p.put("userId", "missing");
        p.put("permissions", new JSONArray().put("tokens.manage"));
        perms.put(p);
        ImportPlan plan = PermissionConfigValidator.validate(perms, new HashSet<String>(), Collections.<String>emptySet(), "merge");
        assertTrue(plan.hasErrors());
    }

    @Test
    public void adNetworkValidatorRejectsBadRefs() throws Exception {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 2);
        root.put("users", new JSONArray().put(new JSONObject().put("id", 1).put("email", "u@example.com").put("password", "plain")));
        root.put("campaigns", new JSONArray().put(new JSONObject().put("id", 100).put("advertiser_id", 999).put("total_budget_micros", -1)));
        root.put("sites", new JSONArray().put(new JSONObject().put("id", 10).put("owner_id", 999)));
        root.put("placements", new JSONArray().put(new JSONObject().put("id", 20).put("site_id", 999)));
        root.put("creatives", new JSONArray().put(new JSONObject().put("id", 30).put("campaign_id", 999)));
        ValidationResult r = AdNetworkConfigValidator.validate(root);
        assertFalse(r.isValid());
    }

    @Test
    public void taskAndWebhookValidatorsRejectInvalid() throws Exception {
        JSONArray tasks = new JSONArray();
        tasks.put(new JSONObject().put("id", "t1").put("action", "backup").put("scheduleType", "once").put("runAt", 0));
        ImportPlan tp = ScheduledTaskConfigValidator.validate(tasks, Collections.emptySet(), "merge");
        assertTrue(tp.hasErrors());

        JSONArray hooks = new JSONArray();
        hooks.put(new JSONObject().put("id", "h1").put("url", "http://evil.example/h").put("events", new JSONArray().put("x")));
        ImportPlan wp = WebhookConfigValidator.validate(hooks, Collections.emptySet(), "merge");
        assertTrue(wp.hasErrors());
    }

    @Test
    public void configUsersPermissionsTasksWebhooksCoverValidationCases() throws Exception {
        JSONObject badCfg = new JSONObject();
        badCfg.put("schemaVersion", 2);
        badCfg.put("appName", 1);
        badCfg.put("apiBasePath", "relative");
        badCfg.put("defaultProjectQuotaBytes", -1);
        badCfg.put("maxUploadBytes", "big");
        badCfg.put("features", "no");
        ValidationResult badCfgRes = JsonConfigValidator.validate(badCfg, "app_config");
        assertFalse(badCfgRes.isValid());

        JSONArray users = new JSONArray();
        users.put(new JSONObject().put("id", "u1").put("username", "alice").put("password", "plain").put("role", "viewer").put("status", "active"));
        users.put(new JSONObject().put("id", "u1").put("username", "alice").put("passwordHash", "h").put("role", "viewer").put("status", "active"));
        ImportPlan usersPlan = UserConfigValidator.validate(users, Collections.emptySet(), Collections.emptySet(), "merge");
        assertTrue(usersPlan.hasErrors());

        JSONArray perms = new JSONArray();
        JSONObject p = new JSONObject();
        p.put("tokenId", "t1"); p.put("userId", "missing"); p.put("role", "viewer");
        p.put("permissions", new JSONArray().put("tokens.manage"));
        p.put("allowedProjects", new JSONArray().put("missing-project"));
        perms.put(p);
        ImportPlan permPlan = PermissionConfigValidator.validate(perms, new HashSet<String>(), Collections.<String>emptySet(), "merge");
        assertTrue(permPlan.hasErrors());

        JSONArray tasks = new JSONArray();
        tasks.put(new JSONObject().put("id", "task1").put("action", "backup").put("scheduleType", "weekly").put("intervalMinutes", 0).put("cronExpression", "* *"));
        tasks.put(new JSONObject().put("id", "task1").put("action", "backup").put("scheduleType", "once"));
        ImportPlan taskPlan = ScheduledTaskConfigValidator.validate(tasks, Collections.emptySet(), "merge");
        assertTrue(taskPlan.hasErrors());

        JSONArray hooks = new JSONArray();
        hooks.put(new JSONObject().put("id", "h1").put("url", "ftp://example.com/h").put("events", new JSONArray()));
        hooks.put(new JSONObject().put("id", "h1").put("url", "https://ok.example/h").put("events", new JSONArray().put("system.health_changed")).put("secret", "plain"));
        ImportPlan hookPlan = WebhookConfigValidator.validate(hooks, Collections.emptySet(), "merge");
        assertTrue(hookPlan.hasErrors());
    }

    @Test
    public void adNetworkCampaignPlacementValidationCoversRelationships() throws Exception {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("users", new JSONArray().put(new JSONObject().put("id", 1).put("email", "u@example.com")));
        root.put("sites", new JSONArray().put(new JSONObject().put("id", 10).put("owner_id", 1).put("name", "s")));
        root.put("campaigns", new JSONArray()
                .put(new JSONObject().put("id", 100).put("advertiser_id", 1).put("total_budget_micros", 10))
                .put(new JSONObject().put("id", 100).put("advertiser_id", 999).put("total_budget_micros", -1)));
        root.put("placements", new JSONArray()
                .put(new JSONObject().put("id", 200).put("site_id", 10).put("width", 1).put("height", 1))
                .put(new JSONObject().put("id", 201).put("site_id", 999).put("width", -1).put("height", 1)));
        root.put("creatives", new JSONArray().put(new JSONObject().put("id", 300).put("campaign_id", 100)));
        ValidationResult r = AdNetworkConfigValidator.validate(root);
        assertFalse(r.isValid());
    }
}
