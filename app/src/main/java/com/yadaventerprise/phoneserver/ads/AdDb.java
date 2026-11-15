package com.yadaventerprise.phoneserver.ads;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persistent file-backed storage for the Ad Network Manager. The whole database is a single
 * encrypted JSON document written atomically; there is no SQL database in use after the one-time
 * import of a legacy adnet.db file.
 */
final class AdDb {
    static final String USERS = "users";
    static final String SESSIONS = "sessions";
    static final String SITES = "sites";
    static final String PLACEMENTS = "placements";
    static final String CAMPAIGNS = "campaigns";
    static final String CREATIVES = "creatives";
    static final String EVENTS = "events";
    static final String STATS = "stats_daily";
    static final String GEO = "geo_daily";
    static final String CONVERSIONS = "conversions";
    static final String EXPERIMENTS = "experiments";
    static final String PAYOUT_REPORTS = "payout_reports";
    static final String SUMMARIES = "daily_summaries";
    static final String FRAUD_LOG = "fraud_log";
    static final String BILLING = "billing";
    static final String AUDIT = "audit";

    private static final String[] TABLES = {
            USERS, SESSIONS, SITES, PLACEMENTS, CAMPAIGNS, CREATIVES, EVENTS, STATS, GEO,
            CONVERSIONS, EXPERIMENTS, PAYOUT_REPORTS, SUMMARIES, FRAUD_LOG, BILLING, AUDIT
    };

    private static final String[][] DEFAULTS = {
            {"rev_share_pct", "70"}, {"currency", "USD"}, {"public_base_url", ""}, {"allow_signup", "0"},
            {"trust_proxy", "0"}, {"fraud_min_click_ms", "500"}, {"fraud_repeat_clicks_hour", "3"},
            {"fraud_ip_clicks_min", "10"}, {"fraud_max_imps_visitor_day", "50"}, {"retention_days", "30"},
            {"conversion_tracking", "0"}, {"conversion_attribution_days", "30"}, {"conversion_dedupe_hours", "24"},
            {"email_enabled", "0"}, {"email_recipients", ""}, {"email_hour_utc", "7"},
            {"smtp_host", ""}, {"smtp_port", "587"}, {"smtp_security", "starttls"},
            {"smtp_username", ""}, {"smtp_password", ""}, {"smtp_from", ""},
            {"geo_db_version", "none"}, {"geo_last_updated", "0"}
    };

    private static AdDb instance;
    private final File file;
    private final File legacyFile;
    private final Object lock = new Object();
    private JSONObject data;
    private int batchDepth;

    static synchronized AdDb get(Context c) {
        if (instance == null) {
            instance = new AdDb(new File(c.getFilesDir(), "adnet/adnet.json"),
                    c.getDatabasePath("adnet.db"));
        }
        return instance;
    }

    AdDb(File file, File legacyFile) {
        this.file = file;
        this.legacyFile = legacyFile;
    }

    AdDb(File file) {
        this(file, new File(file.getParentFile(), "adnet.db"));
    }

    File file() {
        return file;
    }

    synchronized void open() {
        synchronized (lock) {
            if (data != null) return;
            try {
                if (file.isFile()) {
                    data = JsonStorage.readJson(file);
                } else if (legacyFile != null && legacyFile.isFile()) {
                    data = importLegacy(legacyFile);
                } else {
                    data = new JSONObject();
                }
                initialize();
                save();
                if (legacyFile != null && legacyFile.isFile()) retireLegacy(legacyFile);
            } catch (IOException | JSONException e) {
                throw new IllegalStateException("Could not open ad storage: " + e.getMessage(), e);
            }
        }
    }

    private void initialize() throws JSONException {
        if (!data.has("next_id")) data.put("next_id", new JSONObject());
        if (!data.has("settings")) data.put("settings", new JSONObject());
        for (String table : TABLES) {
            if (!data.has(table)) data.put(table, new JSONArray());
            JSONArray rows = data.getJSONArray(table);
            if (!data.getJSONObject("next_id").has(table)) {
                long max = 0;
                for (int i = 0; i < rows.length(); i++) {
                    max = Math.max(max, rows.optJSONObject(i).optLong("id"));
                }
                data.getJSONObject("next_id").put(table, max + 1);
            }
        }
        JSONObject settings = data.getJSONObject("settings");
        for (String[] kv : DEFAULTS) {
            if (!settings.has(kv[0])) settings.put(kv[0], kv[1]);
        }
        if (!settings.has("hmac_secret")) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            settings.put("hmac_secret", Base64.encodeToString(secret, Base64.NO_WRAP));
        }
        if (!settings.has("postback_secret")) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            settings.put("postback_secret", Base64.encodeToString(secret, Base64.NO_WRAP));
        }
        data.put("schema", 2);
    }

    private JSONObject importLegacy(File dbFile) throws IOException, JSONException {
        JSONObject root = new JSONObject();
        root.put("settings", new JSONObject());
        root.put("next_id", new JSONObject());
        for (String table : TABLES) root.put(table, new JSONArray());
        SQLiteDatabase legacy = null;
        try {
            legacy = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            for (String table : TABLES) {
                if (!tableExists(legacy, table)) continue;
                JSONArray rows = root.getJSONArray(table);
                long max = 0;
                try (Cursor c = legacy.rawQuery("SELECT * FROM " + table, null)) {
                    while (c.moveToNext()) {
                        JSONObject row = new JSONObject();
                        for (int i = 0; i < c.getColumnCount(); i++) {
                            Object value;
                            switch (c.getType(i)) {
                                case Cursor.FIELD_TYPE_NULL: value = JSONObject.NULL; break;
                                case Cursor.FIELD_TYPE_INTEGER:
                                    value = c.getLong(i);
                                    if ("id".equals(c.getColumnName(i))) max = Math.max(max, c.getLong(i));
                                    break;
                                case Cursor.FIELD_TYPE_FLOAT: value = c.getDouble(i); break;
                                default: value = c.getString(i);
                            }
                            AdHttp.put(row, c.getColumnName(i), value);
                        }
                        rows.put(row);
                    }
                }
                root.getJSONObject("next_id").put(table, max + 1);
            }
            if (tableExists(legacy, "settings")) {
                try (Cursor c = legacy.rawQuery("SELECT key,value FROM settings", null)) {
                    JSONObject settings = root.getJSONObject("settings");
                    while (c.moveToNext()) {
                        if ("hmac_secret".equals(c.getString(0))) continue;
                        settings.put(c.getString(0), c.getString(1));
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("legacy ad database could not be imported: " + e.getMessage(), e);
        } finally {
            if (legacy != null) legacy.close();
        }
        return root;
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return c.moveToFirst();
        }
    }

    private static void retireLegacy(File dbFile) {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            File f = new File(dbFile.getPath() + suffix);
            if (!f.isFile()) continue;
            File dest = new File(f.getPath() + ".migrated");
            if (dest.isFile()) dest.delete();
            if (!f.renameTo(dest)) return;
        }
    }

    private void save() {
        try {
            JsonStorage.writeJsonAtomic(file, data, true);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save ad storage: " + e.getMessage(), e);
        }
    }

    private void changed() {
        if (batchDepth == 0) save();
    }

    synchronized void transaction(Runnable work) {
        transaction(() -> {
            work.run();
            return null;
        });
    }

    synchronized <T> T transaction(Supplier<T> work) {
        synchronized (lock) {
            open();
            JSONObject backup = copy(data);
            batchDepth++;
            try {
                T result = work.get();
                batchDepth--;
                if (batchDepth == 0) save();
                return result;
            } catch (RuntimeException | Error e) {
                data = backup;
                if (batchDepth > 0) batchDepth--;
                if (batchDepth == 0) save();
                throw e;
            }
        }
    }

    synchronized JSONObject copy(String table, long id) {
        synchronized (lock) {
            open();
            JSONObject row = find(table, r -> r.optLong("id") == id);
            return row == null ? null : copy(row);
        }
    }

    synchronized JSONArray rows(String table) {
        synchronized (lock) {
            open();
            return copy(data.optJSONArray(table));
        }
    }

    synchronized JSONArray rows(String table, Predicate<JSONObject> filter) {
        synchronized (lock) {
            open();
            JSONArray out = new JSONArray();
            JSONArray rows = data.optJSONArray(table);
            if (rows == null) return out;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && filter.test(row)) out.put(copy(row));
            }
            return out;
        }
    }

    synchronized List<JSONObject> list(String table, Predicate<JSONObject> filter) {
        synchronized (lock) {
            open();
            List<JSONObject> out = new ArrayList<>();
            JSONArray rows = data.optJSONArray(table);
            if (rows == null) return out;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && filter.test(row)) out.add(copy(row));
            }
            return out;
        }
    }

    synchronized JSONObject find(String table, Predicate<JSONObject> filter) {
        synchronized (lock) {
            open();
            JSONArray rows = data.optJSONArray(table);
            if (rows == null) return null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && filter.test(row)) return copy(row);
            }
            return null;
        }
    }

    synchronized long count(String table) {
        return count(table, r -> true);
    }

    synchronized long count(String table, Predicate<JSONObject> filter) {
        synchronized (lock) {
            open();
            JSONArray rows = data.optJSONArray(table);
            long n = 0;
            if (rows == null) return 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && filter.test(row)) n++;
            }
            return n;
        }
    }

    synchronized long insert(String table, JSONObject values) {
        synchronized (lock) {
            open();
            JSONObject next = data.optJSONObject("next_id");
            long id = next == null ? 1 : next.optLong(table, 1);
            JSONObject row = copy(values);
            AdHttp.put(row, "id", id);
            data.optJSONArray(table).put(row);
            if (next != null) AdHttp.put(next, table, id + 1);
            changed();
            return id;
        }
    }

    synchronized boolean update(String table, long id, JSONObject patch) {
        synchronized (lock) {
            open();
            JSONObject row = raw(table, r -> r.optLong("id") == id);
            if (row == null) return false;
            copyInto(row, patch);
            changed();
            return true;
        }
    }

    synchronized boolean update(String table, long id, Consumer<JSONObject> mutator) {
        synchronized (lock) {
            open();
            JSONObject row = raw(table, r -> r.optLong("id") == id);
            if (row == null) return false;
            mutator.accept(row);
            changed();
            return true;
        }
    }

    synchronized int updateWhere(String table, Predicate<JSONObject> filter, Consumer<JSONObject> mutator) {
        synchronized (lock) {
            open();
            JSONArray rows = data.optJSONArray(table);
            int n = 0;
            if (rows == null) return 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && filter.test(row)) {
                    mutator.accept(row);
                    n++;
                }
            }
            if (n > 0) changed();
            return n;
        }
    }

    synchronized boolean delete(String table, long id) {
        return deleteWhere(table, r -> r.optLong("id") == id) > 0;
    }

    synchronized int deleteWhere(String table, Predicate<JSONObject> filter) {
        synchronized (lock) {
            open();
            JSONArray rows = data.optJSONArray(table);
            if (rows == null) return 0;
            JSONArray kept = new JSONArray();
            int removed = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && filter.test(row)) removed++;
                else if (row != null) kept.put(row);
            }
            if (removed > 0) {
                AdHttp.put(data, table, kept);
                changed();
            }
            return removed;
        }
    }

    synchronized boolean insertEvent(JSONObject event) {
        synchronized (lock) {
            open();
            String nonce = event.optString("nonce");
            String type = event.optString("type");
            boolean duplicate = find(EVENTS, r -> nonce.equals(r.optString("nonce")) && type.equals(r.optString("type"))) != null;
            if (!duplicate) insert(EVENTS, event);
            return !duplicate;
        }
    }

    private JSONObject raw(String table, Predicate<JSONObject> filter) {
        JSONArray rows = data.optJSONArray(table);
        if (rows == null) return null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && filter.test(row)) return row;
        }
        return null;
    }

    private static JSONObject copy(JSONObject o) {
        if (o == null) return null;
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONArray copy(JSONArray a) {
        if (a == null) return new JSONArray();
        try {
            return new JSONArray(a.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copyInto(JSONObject target, JSONObject patch) {
        java.util.Iterator<String> it = patch.keys();
        while (it.hasNext()) {
            String key = it.next();
            AdHttp.put(target, key, patch.opt(key));
        }
    }

    static List<JSONObject> sorted(JSONArray rows, Comparator<JSONObject> comparator) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) out.add(row);
        }
        out.sort(comparator);
        return out;
    }

    JSONObject settings() {
        synchronized (lock) {
            open();
            return copy(data.optJSONObject("settings"));
        }
    }

    String setting(String key, String def) {
        synchronized (lock) {
            open();
            JSONObject s = data.optJSONObject("settings");
            return s == null || !s.has(key) ? def : s.optString(key, def);
        }
    }

    long settingLong(String key, long def) {
        try {
            return Long.parseLong(setting(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    void setSetting(String key, String value) {
        synchronized (lock) {
            open();
            AdHttp.put(data.optJSONObject("settings"), key, value);
            changed();
        }
    }

    byte[] secret() {
        return Base64.decode(setting("hmac_secret", ""), Base64.NO_WRAP);
    }

    void stat(long day, long campaign, long creative, long placement, long site,
              long served, long imps, long invImps, long clicks, long invClicks, long spend, long pub) {
        updateStat(STATS, day, "", campaign, creative, placement, site, served, imps, invImps, clicks, invClicks, spend, pub);
    }

    void statGeo(long day, String country, long campaign, long creative, long placement, long site,
                 long served, long imps, long invImps, long clicks, long invClicks, long spend, long pub) {
        updateStat(GEO, day, country, campaign, creative, placement, site, served, imps, invImps, clicks, invClicks, spend, pub);
    }

    private void updateStat(String table, long day, String country, long campaign, long creative, long placement, long site,
                            long served, long imps, long invImps, long clicks, long invClicks, long spend, long pub) {
        synchronized (lock) {
            JSONObject row = ensureStat(table, day, country, campaign, creative, placement, site);
            add(row, "served", served);
            add(row, "imps", imps);
            add(row, "invalid_imps", invImps);
            add(row, "clicks", clicks);
            add(row, "invalid_clicks", invClicks);
            add(row, "spend_micros", spend);
            add(row, "pub_micros", pub);
            changed();
        }
    }

    private JSONObject ensureStat(String table, long day, String country, long campaign, long creative, long placement, long site) {
        open();
        JSONObject row = raw(table, r -> r.optLong("day") == day && r.optLong("creative_id") == creative
                && r.optLong("placement_id") == placement && (country.isEmpty() || country.equals(r.optString("country"))));
        if (row == null) {
            row = new JSONObject();
            AdHttp.put(row, "day", day);
            if (!country.isEmpty()) AdHttp.put(row, "country", country);
            AdHttp.put(row, "campaign_id", campaign);
            AdHttp.put(row, "creative_id", creative);
            AdHttp.put(row, "placement_id", placement);
            AdHttp.put(row, "site_id", site);
            AdHttp.put(row, "served", 0L);
            AdHttp.put(row, "imps", 0L);
            AdHttp.put(row, "invalid_imps", 0L);
            AdHttp.put(row, "clicks", 0L);
            AdHttp.put(row, "invalid_clicks", 0L);
            AdHttp.put(row, "conversions", 0L);
            AdHttp.put(row, "conversion_value_micros", 0L);
            AdHttp.put(row, "spend_micros", 0L);
            AdHttp.put(row, "pub_micros", 0L);
            data.optJSONArray(table).put(row);
        }
        return row;
    }

    void adjustConversion(long day, String country, long campaign, long creative, long placement, long site, long count, long value) {
        synchronized (lock) {
            JSONObject row = ensureStat(STATS, day, "", campaign, creative, placement, site);
            add(row, "conversions", count);
            add(row, "conversion_value_micros", value);
            if (!country.isEmpty()) {
                JSONObject geo = ensureStat(GEO, day, country, campaign, creative, placement, site);
                add(geo, "conversions", count);
                add(geo, "conversion_value_micros", value);
            }
            changed();
        }
    }

    private static void add(JSONObject row, String key, long n) {
        if (n != 0) AdHttp.put(row, key, row.optLong(key) + n);
    }

    void fraudLog(String reason) {
        synchronized (lock) {
            open();
            long day = today();
            JSONObject row = raw(FRAUD_LOG, r -> r.optLong("day") == day && reason.equals(r.optString("reason")));
            if (row == null) {
                row = new JSONObject();
                AdHttp.put(row, "day", day);
                AdHttp.put(row, "reason", reason);
                AdHttp.put(row, "n", 0L);
                data.optJSONArray(FRAUD_LOG).put(row);
            }
            AdHttp.put(row, "n", row.optLong("n") + 1);
            changed();
        }
    }

    void audit(long userId, String action, String detail) {
        JSONObject row = new JSONObject();
        AdHttp.put(row, "ts", System.currentTimeMillis());
        AdHttp.put(row, "user_id", userId);
        AdHttp.put(row, "action", action);
        AdHttp.put(row, "detail", detail == null ? "" : detail);
        insert(AUDIT, row);
    }

    static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    static long startOfDayMs() {
        return today() * 86_400_000L;
    }
}
