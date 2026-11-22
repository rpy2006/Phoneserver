package com.yadaventerprise.phoneserver.ads;

import android.content.Context;
import android.content.SharedPreferences;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/**
 * Entry point for the Custom Ads Network Manager. It is opt-in (disabled until switched on in Ad Network settings)
 * and fully separate from the existing PhoneServer HTTP server: its own port, its own files.
 */
public final class AdNetworkManager {
    public static final int PORT = 8090;
    private static final String PREFS = "adnet_prefs";

    private static AdHttpServer server;
    private static Thread sweeper;

    private AdNetworkManager() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
    }

    public static synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public static void startIfEnabled(Context c) {
        if (isEnabled(c)) start(c);
    }

    public static synchronized String start(Context c) {
        if (isRunning()) return null;
        Context app = c.getApplicationContext();
        try {
            AdDb db = AdDb.get(app);
            db.open();
            setupCode(app);
            AdHttpServer s = new AdHttpServer(app, db, PORT);
            s.start();
            server = s;
            startSweeper(app, db);
            emitState(app, "started");
            return null;
        } catch (IOException e) {
            return "Could not start on port " + PORT + ": " + e.getMessage();
        } catch (RuntimeException e) {
            return "Could not start: " + e.getMessage();
        }
    }

    public static synchronized void stop() {
        stop(null);
    }

    public static synchronized void stop(Context context) {
        boolean wasRunning = isRunning();
        if (server != null) server.stop();
        server = null;
        sweeper = null;
        if (wasRunning && context != null) emitState(context.getApplicationContext(), "stopped");
    }

    private static void emitState(Context context, String state) {
        JSONObject data = new JSONObject();
        try {
            data.put("state", state);
            data.put("port", PORT);
        } catch (Exception ignored) {
        }
        WebhookDispatcher.emit(context, WebhookDispatcher.EVENT_AD_STATE_CHANGED, data);
    }

    public static synchronized String setupCode(Context c) {
        SharedPreferences p = prefs(c);
        String code = p.getString("setup_code", "");
        if (code.isEmpty()) {
            code = AdSecurity.randomHex(4).toUpperCase(java.util.Locale.ROOT);
            p.edit().putString("setup_code", code).apply();
        }
        return code;
    }

    public static synchronized void clearSetupCode(Context c) {
        prefs(c).edit().putString("setup_code", "").apply();
    }

    public static synchronized String newSetupCode(Context c) {
        prefs(c).edit().putString("setup_code", "").apply();
        return setupCode(c);
    }

    public static boolean needsSetup(Context c) {
        return AdDb.get(c).count(AdDb.USERS) == 0;
    }

    public static org.json.JSONObject summary(Context c) {
        Context app = c.getApplicationContext();
        AdDb db = AdDb.get(app);
        db.open();
        org.json.JSONObject out = new org.json.JSONObject();
        try {
            out.put("enabled", isEnabled(app));
            out.put("running", isRunning());
            out.put("port", PORT);
            out.put("users", db.count(AdDb.USERS));
            out.put("sites", db.count(AdDb.SITES));
            out.put("placements", db.count(AdDb.PLACEMENTS));
            out.put("campaigns", db.count(AdDb.CAMPAIGNS));
            out.put("creatives", db.count(AdDb.CREATIVES));
            long imps = 0, clicks = 0, spend = 0, earnings = 0;
            for (org.json.JSONObject row : db.list(AdDb.STATS, x -> true)) {
                imps += row.optLong("imps");
                clicks += row.optLong("clicks");
                spend += row.optLong("spend_micros");
                earnings += row.optLong("pub_micros");
            }
            out.put("impressions", imps);
            out.put("clicks", clicks);
            out.put("spendMicros", spend);
            out.put("earningsMicros", earnings);
            out.put("actualRecordsOnly", true);
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject exportConfig(Context c) {
        Context app = c.getApplicationContext();
        AdDb db = AdDb.get(app);
        db.open();
        JSONObject out = new JSONObject();
        try {
            out.put("schemaVersion", 1);
            out.put("exportedAt", System.currentTimeMillis());
            out.put("users", sanitizeUsers(db.rows(AdDb.USERS)));
            out.put("sites", db.rows(AdDb.SITES));
            out.put("placements", db.rows(AdDb.PLACEMENTS));
            out.put("campaigns", db.rows(AdDb.CAMPAIGNS));
            out.put("creatives", db.rows(AdDb.CREATIVES));
        } catch (Exception ignored) {}
        return out;
    }

    public static void importConfig(Context c, JSONObject config, String strategy, File recoveryRoot, java.util.List<Runnable> rollback) throws Exception {
        Context app = c.getApplicationContext();
        AdDb db = AdDb.get(app);
        db.open();
        if (config == null) throw new IllegalArgumentException("ad_network config is required");
        String st = normalizeStrategy(strategy);
        JSONObject normalized = normalizeAdConfig(config);
        validateAdConfig(normalized);

        File backup = new File(recoveryRoot, "adnet-backup.json");
        com.yadaventerprise.phoneserver.data.database.JsonStorage.writeJsonAtomic(backup, db.exportDocument(), true);
        rollback.add(() -> {
            try {
                JSONObject snap = com.yadaventerprise.phoneserver.data.database.JsonStorage.readJson(backup);
                db.restoreDocument(snap);
            } catch (Exception ignored) {}
        });

        db.transaction(() -> {
            applyTable(db, AdDb.USERS, normalized.optJSONArray("users"), st, "id");
            applyTable(db, AdDb.SITES, normalized.optJSONArray("sites"), st, "id");
            applyTable(db, AdDb.PLACEMENTS, normalized.optJSONArray("placements"), st, "id");
            applyTable(db, AdDb.CAMPAIGNS, normalized.optJSONArray("campaigns"), st, "id");
            applyTable(db, AdDb.CREATIVES, normalized.optJSONArray("creatives"), st, "id");
            reconcileAdNextIds(db);
        });
        validateAdConfig(exportConfig(app));
    }

    private static String normalizeStrategy(String strategy) {
        if (strategy == null) return "merge";
        String s = strategy.trim().toLowerCase(java.util.Locale.US);
        if ("create-only".equals(s) || "create".equals(s)) return "create_only";
        if ("update-only".equals(s) || "update".equals(s)) return "update_only";
        if ("selective_replace".equals(s) || "selective-replace".equals(s) || "replace_selected".equals(s) || "replace".equals(s)) return "replace";
        if ("merge".equals(s) || "create_only".equals(s) || "update_only".equals(s)) return s;
        return "merge";
    }

    private static JSONObject normalizeAdConfig(JSONObject config) throws Exception {
        JSONObject out = new JSONObject(config.toString());
        normalizeIdField(out, "sites", "owner_id", "ownerId");
        normalizeIdField(out, "placements", "site_id", "siteId");
        normalizeIdField(out, "campaigns", "advertiser_id", "advertiserId");
        normalizeIdField(out, "creatives", "campaign_id", "campaignId");
        return out;
    }

    private static void normalizeIdField(JSONObject root, String table, String snake, String camel) throws Exception {
        org.json.JSONArray arr = root.optJSONArray(table);
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i); if (o == null) continue;
            if (!o.has(snake) && o.has(camel)) o.put(snake, o.opt(camel));
        }
    }

    private static org.json.JSONArray sanitizeUsers(org.json.JSONArray users) {
        org.json.JSONArray out = new org.json.JSONArray();
        if (users == null) return out;
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.optJSONObject(i); if (u == null) continue;
            JSONObject c = copyJson(u);
            c.remove("password");
            c.remove("pass_hash");
            c.remove("passwordHash");
            c.remove("api_key");
            try { c.put("hasPassword", u.optString("pass_hash", "").length() > 0); } catch (Exception ignored) {}
            out.put(c);
        }
        return out;
    }

    private static JSONObject copyJson(JSONObject o) {
        try { return new JSONObject(o.toString()); } catch (Exception e) { return new JSONObject(); }
    }

    private static void validateAdConfig(JSONObject config) {
        if (config == null) throw new IllegalArgumentException("ad_network config is required");
        org.json.JSONArray users = config.optJSONArray("users");
        org.json.JSONArray sites = config.optJSONArray("sites");
        org.json.JSONArray placements = config.optJSONArray("placements");
        org.json.JSONArray campaigns = config.optJSONArray("campaigns");
        org.json.JSONArray creatives = config.optJSONArray("creatives");
        if (sites == null || placements == null || campaigns == null || creatives == null) {
            throw new IllegalArgumentException("ad_network requires sites, placements, campaigns, creatives arrays");
        }
        java.util.Set<Long> userIds = ids(users);
        java.util.Set<Long> siteIds = ids(sites);
        java.util.Set<Long> campaignIds = ids(campaigns);
        java.util.Set<Long> placementIds = ids(placements);
        java.util.Set<Long> creativeIds = ids(creatives);
        for (int i = 0; i < sites.length(); i++) {
            JSONObject o = sites.optJSONObject(i); if (o == null) continue;
            long owner = o.optLong("owner_id", -1);
            if (owner > 0 && !userIds.contains(owner)) throw new IllegalArgumentException("ad_network.sites[" + i + "].owner_id references unknown user");
        }
        for (int i = 0; i < placements.length(); i++) {
            JSONObject o = placements.optJSONObject(i); if (o == null) continue;
            long siteId = o.optLong("site_id", -1);
            if (siteId <= 0 || !siteIds.contains(siteId)) throw new IllegalArgumentException("ad_network.placements[" + i + "].site_id references unknown site");
        }
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject o = campaigns.optJSONObject(i); if (o == null) continue;
            long adv = o.optLong("advertiser_id", -1);
            if (adv > 0 && !userIds.contains(adv)) throw new IllegalArgumentException("ad_network.campaigns[" + i + "].advertiser_id references unknown user");
            if (o.optDouble("total_budget_micros", o.optDouble("budget", 0)) < 0) throw new IllegalArgumentException("ad_network.campaigns[" + i + "] budget cannot be negative");
        }
        for (int i = 0; i < creatives.length(); i++) {
            JSONObject o = creatives.optJSONObject(i); if (o == null) continue;
            long cid = o.optLong("campaign_id", -1);
            if (cid <= 0 || !campaignIds.contains(cid)) throw new IllegalArgumentException("ad_network.creatives[" + i + "].campaign_id references unknown campaign");
        }
    }

    private static java.util.Set<Long> ids(org.json.JSONArray arr) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        if (arr == null) return ids;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i); if (o == null) continue;
            long id = o.optLong("id", -1);
            if (id <= 0) throw new IllegalArgumentException("ad_network record missing positive id");
            if (!ids.add(id)) throw new IllegalArgumentException("ad_network duplicate id: " + id);
        }
        return ids;
    }

    private static void applyTable(AdDb db, String table, org.json.JSONArray incoming, String strategy, String idField) {
        org.json.JSONArray existing = db.rows(table);
        java.util.Map<Long, JSONObject> existingById = byLongId(existing);
        java.util.LinkedHashMap<Long, JSONObject> result = new java.util.LinkedHashMap<>();
        if (!"replace".equals(strategy)) {
            for (int i = 0; i < existing.length(); i++) {
                JSONObject o = existing.optJSONObject(i); if (o == null) continue;
                result.put(o.optLong(idField), copyJson(o));
            }
        }
        if (incoming != null) {
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject o = incoming.optJSONObject(i); if (o == null) continue;
                long id = o.optLong(idField, -1);
                if (id <= 0) throw new IllegalArgumentException(table + " record missing id");
                boolean exists = existingById.containsKey(id);
                if ("create_only".equals(strategy) && exists) continue;
                if ("update_only".equals(strategy) && !exists) continue;
                JSONObject row = copyJson(o);
                if (AdDb.USERS.equals(table)) {
                    JSONObject prev = existingById.get(id);
                    if (prev != null && !row.has("pass_hash") && prev.has("pass_hash")) { try { row.put("pass_hash", prev.optString("pass_hash", "")); } catch (Exception ignored) {} }
                    row.remove("hasPassword");
                }
                result.put(id, row);
            }
        }
        org.json.JSONArray arr = new org.json.JSONArray();
        for (JSONObject o : result.values()) arr.put(o);
        db.replaceTable(table, arr);
    }

    private static java.util.Map<Long, JSONObject> byLongId(org.json.JSONArray arr) {
        java.util.Map<Long, JSONObject> map = new java.util.LinkedHashMap<>();
        if (arr == null) return map;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i); if (o == null) continue;
            map.put(o.optLong("id"), o);
        }
        return map;
    }

    private static void reconcileAdNextIds(AdDb db) {
        db.reconcileNextIds();
    }

    private static void startSweeper(final Context app, final AdDb db) {
        final Thread t = new Thread(() -> {
            long tick = 0;
            while (sweeper == Thread.currentThread()) {
                try {
                    long now = System.currentTimeMillis();
                    db.deleteWhere(AdDb.SESSIONS, s -> s.optLong("expires_at") < now);
                    AdRateLimiter.sweep();
                    if (tick % 10 == 0) AdFraud.reviewSites(db);
                    if (tick % 60 == 0) {
                        long keep = db.settingLong("retention_days", 30) * 86_400_000L;
                        db.deleteWhere(AdDb.EVENTS, e -> e.optLong("ts") < now - keep);
                        db.deleteWhere(AdDb.FRAUD_LOG, f -> f.optLong("day") < AdDb.today() - 90);
                        db.updateWhere(AdDb.CAMPAIGNS, c -> "approved".equals(c.optString("status"))
                                        || "paused".equals(c.optString("status")),
                                c -> {
                                    if (!c.isNull("end_at") && c.optLong("end_at") <= now) AdHttp.put(c, "status", "ended");
                                });
                        removeOrphanFiles(app, db);
                        AdSummaryMailer.maybeSend(db);
                        AdExperimentEngine.finishExpired(db);
                    }
                    tick++;
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    android.util.Log.w("AdNet", "sweeper", e);
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "AdNet-Sweeper");
        sweeper = t;
        t.start();
    }

    private static void removeOrphanFiles(Context app, AdDb db) {
        File[] files = AdDelivery.creativesDir(app).listFiles();
        if (files == null) return;
        Set<String> used = new HashSet<>();
        for (JSONObject row : db.list(AdDb.CREATIVES, c -> !c.optString("image_file").isEmpty())) {
            used.add(row.optString("image_file"));
        }
        long cutoff = System.currentTimeMillis() - 3600_000L;
        for (File f : files) {
            if (!used.contains(f.getName()) && f.lastModified() < cutoff) f.delete();
        }
    }
}
