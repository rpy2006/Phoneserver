package com.yadaventerprise.phoneserver.ads;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Publisher payout statements and CSV exports. Payout statements never include advertiser spend. */
final class AdPayouts {
    private AdPayouts() {}

    private static long parseDay(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            return f.parse(s).getTime() / 86_400_000L;
        } catch (ParseException e) {
            throw AdHttp.bad("Dates must look like 2026-10-22");
        }
    }

    private static long[] range(AdHttp.Req r) {
        return checkedRange(r.param("from"), r.param("to"));
    }

    private static long[] range(JSONObject b) {
        return checkedRange(b.optString("from", ""), b.optString("to", ""));
    }

    private static long[] checkedRange(String fromValue, String toValue) {
        long to = parseDay(toValue, AdDb.today());
        long from = parseDay(fromValue, to - 29);
        if (to < from) throw AdHttp.bad("'to' must not be before 'from'");
        if (to - from > 366) throw AdHttp.bad("Date range is limited to one year");
        return new long[]{from, to};
    }

    private static JSONObject publisher(AdDb db, long id) {
        JSONObject u = db.copy(AdDb.USERS, id);
        if (u == null || !"publisher".equals(u.optString("role"))) throw AdHttp.notFound("Publisher not found");
        return u;
    }

    private static void publisherOnly(AdSecurity.User u) {
        if (!u.isAdmin() && !"publisher".equals(u.role)) throw AdHttp.forbidden("Payout statements are only available to publishers and administrators");
    }

    private static List<JSONObject> details(AdDb db, long publisherId, long from, long to) {
        Map<Long, JSONObject> sites = new HashMap<>();
        for (JSONObject s : db.list(AdDb.SITES, x -> x.optLong("owner_id") == publisherId)) sites.put(s.optLong("id"), s);
        Map<String, JSONObject> grouped = new HashMap<>();
        for (JSONObject s : db.list(AdDb.STATS, x -> x.optLong("day") >= from && x.optLong("day") <= to
                && sites.containsKey(x.optLong("site_id")))) {
            String key = s.optLong("day") + ":" + s.optLong("site_id");
            JSONObject d = grouped.get(key);
            if (d == null) {
                JSONObject site = sites.get(s.optLong("site_id"));
                d = AdHttp.obj("day", s.optLong("day"), "site_id", s.optLong("site_id"),
                        "site_domain", site == null ? "" : site.optString("domain"),
                        "imps", 0L, "clicks", 0L, "earnings_micros", 0L);
                grouped.put(key, d);
            }
            AdHttp.put(d, "imps", d.optLong("imps") + s.optLong("imps"));
            AdHttp.put(d, "clicks", d.optLong("clicks") + s.optLong("clicks"));
            AdHttp.put(d, "earnings_micros", d.optLong("earnings_micros") + s.optLong("pub_micros"));
        }
        List<JSONObject> rows = new ArrayList<>(grouped.values());
        rows.sort((a, b) -> {
            int c = Long.compare(a.optLong("day"), b.optLong("day"));
            return c != 0 ? c : a.optString("site_domain").compareTo(b.optString("site_domain"));
        });
        return rows;
    }

    private static long total(List<JSONObject> rows) {
        long n = 0;
        for (JSONObject r : rows) n += r.optLong("earnings_micros");
        return n;
    }

    static AdHttp.Res list(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        publisherOnly(u);
        long target = u.isAdmin() ? 0 : u.id;
        if (u.isAdmin()) {
            String v = r.param("user_id");
            if (v != null && !v.isEmpty()) target = parseId(v, "user_id");
        }
        Map<Long, JSONObject> users = new HashMap<>();
        for (JSONObject x : db.list(AdDb.USERS, y -> true)) users.put(x.optLong("id"), x);
        final long publisherFilter = target;
        JSONArray out = new JSONArray();
        for (JSONObject p : db.list(AdDb.PAYOUT_REPORTS, x -> publisherFilter == 0 || x.optLong("user_id") == publisherFilter)) {
            JSONObject us = users.get(p.optLong("user_id"));
            AdHttp.put(p, "publisher_email", us == null ? "" : us.optString("email"));
            AdHttp.put(p, "publisher_name", us == null ? "" : us.optString("name"));
            AdHttp.put(p, "from", AdReports.dayString(p.optLong("from_day")));
            AdHttp.put(p, "to", AdReports.dayString(p.optLong("to_day")));
            out.put(p);
        }
        List<JSONObject> sorted = AdDb.sorted(out, (a, b) -> Long.compare(b.optLong("id"), a.optLong("id")));
        JSONArray ordered = new JSONArray();
        for (JSONObject p : sorted) ordered.put(p);
        return AdHttp.ok(ordered);
    }

    static AdHttp.Res create(AdHttp.Req r, AdSecurity.User admin, AdDb db) {
        if (!admin.isAdmin()) throw AdHttp.forbidden("Administrator access required");
        JSONObject b = AdHttp.body(r);
        long publisherId = AdHttp.num(b, "user_id", 1, Long.MAX_VALUE, 0);
        publisher(db, publisherId);
        long[] range = range(b);
        List<JSONObject> rows = details(db, publisherId, range[0], range[1]);
        long amount = total(rows);
        if (amount <= 0) throw AdHttp.bad("There are no publisher earnings in that date range");
        JSONArray snapshot = new JSONArray();
        for (JSONObject x : rows) snapshot.put(x);
        JSONObject row = AdHttp.obj("user_id", publisherId, "from_day", range[0], "to_day", range[1],
                "amount_micros", amount, "currency", db.setting("currency", "USD"), "status", "pending",
                "line_count", rows.size(), "note", AdHttp.str(b, "note", 0, 200),
                "lines", snapshot, "created_by", admin.id, "created_at", System.currentTimeMillis(), "paid_at", 0L);
        long id = db.insert(AdDb.PAYOUT_REPORTS, row);
        db.audit(admin.id, "payout.report", "#" + id + " publisher #" + publisherId + " " + amount);
        return AdHttp.ok(AdHttp.obj("id", id, "amount_micros", amount, "line_count", rows.size()));
    }

    static AdHttp.Res export(AdHttp.Req r, AdSecurity.User u, AdDb db, long id) {
        publisherOnly(u);
        JSONObject p = db.copy(AdDb.PAYOUT_REPORTS, id);
        if (p == null || (!u.isAdmin() && p.optLong("user_id") != u.id)) throw AdHttp.notFound("Payout report not found");
        JSONObject user = db.copy(AdDb.USERS, p.optLong("user_id"));
        return csv(db, p.optLong("user_id"), user == null ? "publisher" : user.optString("email"),
                p.optLong("from_day"), p.optLong("to_day"), p.optJSONArray("lines"));
    }

    static AdHttp.Res exportRange(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        publisherOnly(u);
        long[] range = range(r);
        long target = u.id;
        if (u.isAdmin()) {
            String v = r.param("user_id");
            if (v != null && !v.isEmpty()) target = parseId(v, "user_id");
            if (target == 0) throw AdHttp.bad("user_id is required for an administrator export");
        }
        JSONObject user = publisher(db, target);
        return csv(db, target, user.optString("email"), range[0], range[1], null);
    }

    private static AdHttp.Res csv(AdDb db, long publisherId, String email, long from, long to, JSONArray snapshot) {
        List<JSONObject> rows = new ArrayList<>();
        if (snapshot != null) {
            for (int i = 0; i < snapshot.length(); i++) rows.add(snapshot.optJSONObject(i));
        } else {
            rows = details(db, publisherId, from, to);
        }
        StringBuilder sb = new StringBuilder("date,site,impressions,clicks,earnings,");
        sb.append(db.setting("currency", "USD")).append("\r\n");
        for (JSONObject d : rows) {
            sb.append(AdReports.dayString(d.optLong("day"))).append(',')
                    .append(csvCell(d.optString("site_domain"))).append(',')
                    .append(d.optLong("imps")).append(',').append(d.optLong("clicks")).append(',')
                    .append(money(d.optLong("earnings_micros"))).append("\r\n");
        }
        sb.append("TOTAL,,,,").append(money(total(rows))).append("\r\n");
        String name = email.replaceAll("[^A-Za-z0-9._-]", "_");
        return AdHttp.text(200, "text/csv; charset=utf-8", sb.toString())
                .header("Content-Disposition", "attachment; filename=\"adnet-payout-" + name + "-"
                        + AdReports.dayString(from) + "-" + AdReports.dayString(to) + ".csv\"");
    }

    private static String money(long micros) {
        return String.format(Locale.US, "%.4f", micros / 1_000_000.0);
    }

    private static String csvCell(String s) {
        if (s == null) s = "";
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static long parseId(String v, String name) {
        if (!v.matches("[0-9]{1,15}")) throw AdHttp.bad(name + " must be a number");
        return Long.parseLong(v);
    }
}
