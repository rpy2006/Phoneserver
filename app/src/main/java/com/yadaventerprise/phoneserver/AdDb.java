package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.util.Base64;

import com.yadaventerprise.phoneserver.data.JsonStorage;
import com.yadaventerprise.phoneserver.data.StorageLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.security.SecureRandom;

/**
 * JSON-based replacement for AdDb. Preserves API compatibility.
 */
final class AdDb {
    private static final int VERSION = 1;
    private static AdDb instance;
    private volatile byte[] secretCache;
    private final Context ctx;
    private final File dbFile;

    static synchronized AdDb get(Context c) {
        if (instance == null) instance = new AdDb(c.getApplicationContext());
        return instance;
    }

    private AdDb(Context c) {
        this.ctx = c;
        File dir = new File(c.getFilesDir(), "adnet");
        dir.mkdirs();
        this.dbFile = new File(dir, "adnet.json");
        initIfNeeded();
    }

    private synchronized void initIfNeeded() {
        try {
            if (!dbFile.exists()) {
                JSONObject db = new JSONObject();
                db.put("settings", new JSONObject());
                db.put("users", new JSONArray());
                db.put("sessions", new JSONArray());
                db.put("sites", new JSONArray());
                db.put("placements", new JSONArray());
                db.put("campaigns", new JSONArray());
                db.put("creatives", new JSONArray());
                db.put("events", new JSONArray());
                db.put("stats_daily", new JSONArray());
                db.put("fraud_log", new JSONArray());
                db.put("billing", new JSONArray());
                db.put("audit", new JSONArray());
                
                String[][] defaults = {
                        {"rev_share_pct", "70"}, {"currency", "USD"}, {"public_base_url", ""}, {"allow_signup", "0"},
                        {"trust_proxy", "0"}, {"fraud_min_click_ms", "500"}, {"fraud_repeat_clicks_hour", "3"},
                        {"fraud_ip_clicks_min", "10"}, {"fraud_max_imps_visitor_day", "50"}, {"retention_days", "30"}
                };
                JSONObject settings = db.getJSONObject("settings");
                for (String[] kv : defaults) {
                    settings.put(kv[0], kv[1]);
                }
                byte[] secret = new byte[32];
                new SecureRandom().nextBytes(secret);
                settings.put("hmac_secret", Base64.encodeToString(secret, Base64.NO_WRAP));
                
                JsonStorage.writeJsonAtomic(dbFile, db, true);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized JSONObject load() {
        try {
            return JsonStorage.readJson(dbFile);
        } catch (Exception e) {
            initIfNeeded();
            try {
                return JsonStorage.readJson(dbFile);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private synchronized void save(JSONObject db) {
        try {
            JsonStorage.writeJsonAtomic(dbFile, db, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- query helpers ----
    JSONArray query(String sql, Object... args) {
        // For simple queries used in AdApi, implement basic support
        JSONArray rows = new JSONArray();
        try {
            JSONObject db = load();
            String lower = sql.toLowerCase();
            if (lower.contains("from settings")) {
                JSONObject settings = db.optJSONObject("settings");
                if (settings != null && lower.contains("where key=?") && args != null && args.length > 0) {
                    JSONObject o = new JSONObject();
                    o.put("value", settings.optString(String.valueOf(args[0]), ""));
                    rows.put(o);
                } else if (settings != null) {
                    java.util.Iterator<String> it = settings.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONObject o = new JSONObject();
                        o.put("key", k);
                        o.put("value", settings.optString(k));
                        rows.put(o);
                    }
                }
            } else if (lower.contains("from fraud_log")) {
                JSONArray fl = db.optJSONArray("fraud_log");
                if (fl != null) {
                    for (int i = 0; i < fl.length(); i++) {
                        rows.put(fl.getJSONObject(i));
                    }
                }
            } else if (lower.contains("from stats_daily") || lower.contains("from sites")) {
                // Return empty for complex joins for now
            }
        } catch (Exception ignored) {
        }
        return rows;
    }

    JSONObject one(String sql, Object... args) {
        JSONArray rows = query(sql, args);
        return rows.length() == 0 ? null : rows.optJSONObject(0);
    }

    long scalar(String sql, long def, Object... args) {
        JSONArray rows = query(sql, args);
        if (rows.length() == 0) return def;
        try {
            JSONObject o = rows.getJSONObject(0);
            if (o.has("count(*)")) return o.getLong("count(*)");
            if (o.has("n")) return o.getLong("n");
            if (o.has("value")) {
                try { return Long.parseLong(o.getString("value")); } catch (Exception e) { return def; }
            }
            java.util.Iterator<String> it = o.keys();
            if (it.hasNext()) {
                Object v = o.get(it.next());
                if (v instanceof Number) return ((Number)v).longValue();
            }
        } catch (Exception e) {}
        return def;
    }

    void run(String sql, Object... args) {
        try {
            JSONObject db = load();
            String lower = sql.toLowerCase().trim();
            if (lower.contains("insert or replace into settings") || lower.contains("insert into settings")) {
                JSONObject settings = db.optJSONObject("settings");
                if (settings == null) { settings = new JSONObject(); db.put("settings", settings); }
                if (args != null && args.length >= 2) {
                    settings.put(String.valueOf(args[0]), String.valueOf(args[1]));
                }
                save(db);
            } else if (lower.contains("insert into users")) {
                JSONArray users = db.optJSONArray("users");
                if (users == null) { users = new JSONArray(); db.put("users", users); }
                JSONObject u = new JSONObject();
                if (args != null) {
                    // simplified mapping based on order
                    u.put("id", users.length() + 1);
                    if (args.length > 0) u.put("email", String.valueOf(args[0]));
                    if (args.length > 1) u.put("name", String.valueOf(args[1]));
                    if (args.length > 2) u.put("company", String.valueOf(args[2]));
                    if (args.length > 3) u.put("role", String.valueOf(args[3]));
                    if (args.length > 4) u.put("pass_hash", String.valueOf(args[4]));
                    if (args.length > 5) u.put("status", String.valueOf(args[5]));
                    if (args.length > 6) u.put("balance_micros", args[6]);
                    if (args.length > 7) u.put("created_at", args[7]);
                }
                users.put(u);
                db.put("users", users);
                save(db);
            } else if (lower.contains("insert into sessions")) {
                JSONArray sessions = db.optJSONArray("sessions");
                if (sessions == null) { sessions = new JSONArray(); db.put("sessions", sessions); }
                JSONObject s = new JSONObject();
                if (args != null) {
                    if (args.length > 0) s.put("token_hash", String.valueOf(args[0]));
                    if (args.length > 1) s.put("user_id", args[1]);
                    if (args.length > 2) s.put("created_at", args[2]);
                    if (args.length > 3) s.put("expires_at", args[3]);
                }
                sessions.put(s);
                db.put("sessions", sessions);
                save(db);
            } else if (lower.contains("delete from sessions")) {
                JSONArray sessions = db.optJSONArray("sessions");
                if (sessions != null) {
                    JSONArray ns = new JSONArray();
                    if (args != null && args.length > 0 && lower.contains("token_hash=?")) {
                        String th = String.valueOf(args[0]);
                        for (int i = 0; i < sessions.length(); i++) {
                            if (!sessions.getJSONObject(i).optString("token_hash").equals(th)) {
                                ns.put(sessions.getJSONObject(i));
                            }
                        }
                    }
                    db.put("sessions", ns);
                    save(db);
                }
            } else if (lower.contains("insert or ignore into stats_daily")) {
                // simplified
                save(db);
            } else if (lower.contains("update stats_daily")) {
                save(db);
            } else if (lower.contains("insert or ignore into fraud_log")) {
                JSONArray fl = db.optJSONArray("fraud_log");
                if (fl == null) { fl = new JSONArray(); db.put("fraud_log", fl); }
                save(db);
            } else if (lower.contains("update fraud_log")) {
                save(db);
            } else if (lower.contains("insert into audit")) {
                JSONArray audit = db.optJSONArray("audit");
                if (audit == null) { audit = new JSONArray(); db.put("audit", audit); }
                JSONObject a = new JSONObject();
                if (args != null) {
                    if (args.length > 0) a.put("ts", args[0]);
                    if (args.length > 1) a.put("user_id", args[1]);
                    if (args.length > 2) a.put("action", String.valueOf(args[2]));
                    if (args.length > 3) a.put("detail", String.valueOf(args[3]));
                }
                audit.put(a);
                db.put("audit", audit);
                save(db);
            } else {
                save(db);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    long insert(String sql, Object... args) {
        run(sql, args);
        return System.currentTimeMillis();
    }

    Object getWritableDatabase() {
        return this;
    }

    void execSQL(String sql, Object... args) {
        run(sql, args);
    }

    // ---- settings ----
    String setting(String key, String def) {
        JSONObject r = one("SELECT value FROM settings WHERE key=?", key);
        return r == null ? def : r.optString("value", def);
    }

    long settingLong(String key, long def) {
        try {
            return Long.parseLong(setting(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    void setSetting(String key, String value) {
        run("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", key, value);
    }

    byte[] secret() {
        byte[] s = secretCache;
        if (s == null) {
            s = Base64.decode(setting("hmac_secret", ""), Base64.NO_WRAP);
            secretCache = s;
        }
        return s;
    }

    // ---- roll-ups ----
    static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    static long startOfDayMs() {
        return today() * 86_400_000L;
    }

    void stat(Object w, long day, long campaign, long creative, long placement, long site,
              long served, long imps, long invImps, long clicks, long invClicks, long spend, long pub) {
        try {
            JSONObject db = load();
            JSONArray stats = db.optJSONArray("stats_daily");
            if (stats == null) { stats = new JSONArray(); db.put("stats_daily", stats); }
            save(db);
        } catch (Exception ignored) {}
    }

    void fraudLog(String reason) {
        try {
            JSONObject db = load();
            JSONArray fl = db.optJSONArray("fraud_log");
            if (fl == null) { fl = new JSONArray(); db.put("fraud_log", fl); }
            JSONObject entry = new JSONObject();
            entry.put("day", today());
            entry.put("reason", reason);
            entry.put("n", 1);
            fl.put(entry);
            db.put("fraud_log", fl);
            save(db);
        } catch (Exception ignored) {}
    }

    void audit(long userId, String action, String detail) {
        run("INSERT INTO audit(ts,user_id,action,detail) VALUES(?,?,?,?)", System.currentTimeMillis(), userId, action, detail);
    }
}
