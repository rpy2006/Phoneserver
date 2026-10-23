package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * Persistent SQL storage for the Ad Network Manager: one app-private SQLite database.
 * Every query uses bound parameters - user input is never concatenated into SQL.
 * Money is stored as integer micro-units (1 currency unit = 1,000,000) so nothing drifts.
 */
final class AdDb extends SQLiteOpenHelper {
    private static final int VERSION = 1;
    private static AdDb instance;
    private volatile byte[] secretCache;

    static synchronized AdDb get(Context c) {
        if (instance == null) instance = new AdDb(c.getApplicationContext());
        return instance;
    }

    private AdDb(Context c) {
        super(c, "adnet.db", null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    private static final String[] SCHEMA = {
            "CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)",

            "CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " email TEXT NOT NULL UNIQUE COLLATE NOCASE, name TEXT NOT NULL, company TEXT NOT NULL DEFAULT '',"
                    + " role TEXT NOT NULL CHECK(role IN('admin','publisher','advertiser')),"
                    + " pass_hash TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'active' CHECK(status IN('active','disabled')),"
                    + " balance_micros INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",

            "CREATE TABLE sessions(token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
                    + " created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)",
            "CREATE INDEX ix_sessions_user ON sessions(user_id)",

            "CREATE TABLE sites(id INTEGER PRIMARY KEY AUTOINCREMENT, owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
                    + " name TEXT NOT NULL, domain TEXT NOT NULL,"
                    + " status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN('pending','approved','rejected','removed')),"
                    + " flagged INTEGER NOT NULL DEFAULT 0, flag_reason TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX ux_sites_domain ON sites(domain) WHERE status<>'removed'",

            "CREATE TABLE placements(id INTEGER PRIMARY KEY AUTOINCREMENT, site_id INTEGER NOT NULL REFERENCES sites(id) ON DELETE CASCADE,"
                    + " name TEXT NOT NULL, format TEXT NOT NULL CHECK(format IN('banner','native','text')),"
                    + " width INTEGER NOT NULL DEFAULT 0, height INTEGER NOT NULL DEFAULT 0, key TEXT NOT NULL UNIQUE,"
                    + " status TEXT NOT NULL DEFAULT 'active' CHECK(status IN('active','paused','removed')), created_at INTEGER NOT NULL)",

            "CREATE TABLE campaigns(id INTEGER PRIMARY KEY AUTOINCREMENT, advertiser_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
                    + " name TEXT NOT NULL,"
                    + " status TEXT NOT NULL DEFAULT 'draft' CHECK(status IN('draft','pending','approved','rejected','paused','ended')),"
                    + " pricing TEXT NOT NULL CHECK(pricing IN('CPM','CPC')), bid_micros INTEGER NOT NULL,"
                    + " daily_budget_micros INTEGER NOT NULL DEFAULT 0, total_budget_micros INTEGER NOT NULL,"
                    + " spent_micros INTEGER NOT NULL DEFAULT 0, start_at INTEGER, end_at INTEGER,"
                    + " targeting TEXT NOT NULL DEFAULT '{}', reject_reason TEXT NOT NULL DEFAULT '',"
                    + " created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE INDEX ix_campaigns_adv ON campaigns(advertiser_id)",

            "CREATE TABLE creatives(id INTEGER PRIMARY KEY AUTOINCREMENT, campaign_id INTEGER NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,"
                    + " type TEXT NOT NULL CHECK(type IN('banner','native','text')),"
                    + " title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', cta TEXT NOT NULL DEFAULT '', click_url TEXT NOT NULL,"
                    + " image_file TEXT NOT NULL DEFAULT '', image_mime TEXT NOT NULL DEFAULT '',"
                    + " width INTEGER NOT NULL DEFAULT 0, height INTEGER NOT NULL DEFAULT 0,"
                    + " status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN('pending','approved','rejected')),"
                    + " reject_reason TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)",
            "CREATE INDEX ix_creatives_campaign ON creatives(campaign_id)",

            // Raw billable/verified events. A (nonce,type) pair can exist only once, which blocks replayed tokens.
            "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL CHECK(type IN('imp','click')),"
                    + " nonce TEXT NOT NULL, campaign_id INTEGER NOT NULL, creative_id INTEGER NOT NULL, placement_id INTEGER NOT NULL,"
                    + " site_id INTEGER NOT NULL, visitor TEXT NOT NULL DEFAULT '', valid INTEGER NOT NULL, reason TEXT NOT NULL DEFAULT '',"
                    + " cost_micros INTEGER NOT NULL DEFAULT 0, pub_micros INTEGER NOT NULL DEFAULT 0)",
            "CREATE UNIQUE INDEX ux_events_once ON events(nonce, type)",
            "CREATE INDEX ix_events_ts ON events(ts)",
            "CREATE INDEX ix_events_visitor ON events(visitor, campaign_id, type, ts)",

            // Daily roll-up that all reports read. It outlives raw-event pruning.
            "CREATE TABLE stats_daily(day INTEGER NOT NULL, campaign_id INTEGER NOT NULL, creative_id INTEGER NOT NULL,"
                    + " placement_id INTEGER NOT NULL, site_id INTEGER NOT NULL,"
                    + " served INTEGER NOT NULL DEFAULT 0, imps INTEGER NOT NULL DEFAULT 0, invalid_imps INTEGER NOT NULL DEFAULT 0,"
                    + " clicks INTEGER NOT NULL DEFAULT 0, invalid_clicks INTEGER NOT NULL DEFAULT 0,"
                    + " spend_micros INTEGER NOT NULL DEFAULT 0, pub_micros INTEGER NOT NULL DEFAULT 0,"
                    + " PRIMARY KEY(day, creative_id, placement_id))",
            "CREATE INDEX ix_stats_campaign ON stats_daily(campaign_id, day)",
            "CREATE INDEX ix_stats_site ON stats_daily(site_id, day)",

            // Unattributable rejections (forged / expired tokens) and per-reason counters.
            "CREATE TABLE fraud_log(day INTEGER NOT NULL, reason TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, reason))",

            // Manual ledger: advertiser top-ups, adjustments and publisher payouts. Spend/earnings are derived from stats_daily.
            "CREATE TABLE billing(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,"
                    + " user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
                    + " type TEXT NOT NULL CHECK(type IN('topup','adjustment','payout')), amount_micros INTEGER NOT NULL,"
                    + " balance_after_micros INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '', created_by INTEGER NOT NULL)",
            "CREATE INDEX ix_billing_user ON billing(user_id, ts)",

            "CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, user_id INTEGER NOT NULL,"
                    + " action TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')"
    };

    @Override
    public void onCreate(SQLiteDatabase db) {
        for (String s : SCHEMA) db.execSQL(s);
        String[][] defaults = {
                {"rev_share_pct", "70"}, {"currency", "USD"}, {"public_base_url", ""}, {"allow_signup", "0"},
                {"trust_proxy", "0"}, {"fraud_min_click_ms", "500"}, {"fraud_repeat_clicks_hour", "3"},
                {"fraud_ip_clicks_min", "10"}, {"fraud_max_imps_visitor_day", "50"}, {"retention_days", "30"}
        };
        for (String[] kv : defaults) {
            db.execSQL("INSERT OR IGNORE INTO settings(key,value) VALUES(?,?)", new Object[]{kv[0], kv[1]});
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        db.execSQL("INSERT OR IGNORE INTO settings(key,value) VALUES('hmac_secret',?)",
                new Object[]{Base64.encodeToString(secret, Base64.NO_WRAP)});
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first schema. Future migrations go here.
    }

    // ---- query helpers ----

    private static String[] strs(Object[] args) {
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) out[i] = String.valueOf(args[i]);
        return out;
    }

    /** Runs a SELECT with bound parameters and returns rows as JSON objects keyed by column name. */
    JSONArray query(String sql, Object... args) {
        JSONArray rows = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(sql, strs(args))) {
            int n = c.getColumnCount();
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                for (int i = 0; i < n; i++) {
                    Object v;
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: v = JSONObject.NULL; break;
                        case Cursor.FIELD_TYPE_INTEGER: v = c.getLong(i); break;
                        case Cursor.FIELD_TYPE_FLOAT: v = c.getDouble(i); break;
                        default: v = c.getString(i);
                    }
                    AdHttp.put(o, c.getColumnName(i), v);
                }
                rows.put(o);
            }
        }
        return rows;
    }

    JSONObject one(String sql, Object... args) {
        JSONArray rows = query(sql, args);
        return rows.length() == 0 ? null : rows.optJSONObject(0);
    }

    long scalar(String sql, long def, Object... args) {
        try (Cursor c = getReadableDatabase().rawQuery(sql, strs(args))) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : def;
        }
    }

    void run(String sql, Object... args) {
        getWritableDatabase().execSQL(sql, args);
    }

    /** Inserts and returns the new row id (uses a compiled statement so the id comes from the same connection). */
    long insert(String sql, Object... args) {
        SQLiteStatement st = getWritableDatabase().compileStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) st.bindNull(i + 1);
                else if (a instanceof Number) st.bindLong(i + 1, ((Number) a).longValue());
                else st.bindString(i + 1, String.valueOf(a));
            }
            return st.executeInsert();
        } finally {
            st.close();
        }
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

    void stat(SQLiteDatabase w, long day, long campaign, long creative, long placement, long site,
              long served, long imps, long invImps, long clicks, long invClicks, long spend, long pub) {
        w.execSQL("INSERT OR IGNORE INTO stats_daily(day,campaign_id,creative_id,placement_id,site_id) VALUES(?,?,?,?,?)",
                new Object[]{day, campaign, creative, placement, site});
        w.execSQL("UPDATE stats_daily SET served=served+?, imps=imps+?, invalid_imps=invalid_imps+?, clicks=clicks+?,"
                        + " invalid_clicks=invalid_clicks+?, spend_micros=spend_micros+?, pub_micros=pub_micros+?"
                        + " WHERE day=? AND creative_id=? AND placement_id=?",
                new Object[]{served, imps, invImps, clicks, invClicks, spend, pub, day, creative, placement});
    }

    void fraudLog(String reason) {
        SQLiteDatabase w = getWritableDatabase();
        long day = today();
        w.execSQL("INSERT OR IGNORE INTO fraud_log(day,reason,n) VALUES(?,?,0)", new Object[]{day, reason});
        w.execSQL("UPDATE fraud_log SET n=n+1 WHERE day=? AND reason=?", new Object[]{day, reason});
    }

    void audit(long userId, String action, String detail) {
        run("INSERT INTO audit(ts,user_id,action,detail) VALUES(?,?,?,?)", System.currentTimeMillis(), userId, action, detail);
    }
}
