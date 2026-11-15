package com.yadaventerprise.phoneserver.ads;

import static com.yadaventerprise.phoneserver.ads.AdHttp.bad;
import static com.yadaventerprise.phoneserver.ads.AdHttp.obj;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reports, CSV export and billing records. Every number comes from stats_daily (real tracked events)
 * or the billing ledger. Advertisers never see publisher earnings; publishers never see advertiser spend.
 */
final class AdReports {
    private final AdDb db;

    AdReports(AdDb db) {
        this.db = db;
    }

    private static SimpleDateFormat dayFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        f.setLenient(false);
        return f;
    }

    private static long parseDay(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return dayFormat().parse(s).getTime() / 86_400_000L;
        } catch (ParseException e) {
            throw bad("Dates must look like 2026-10-22");
        }
    }

    static String dayString(long day) {
        return dayFormat().format(new Date(day * 86_400_000L));
    }

    private static long id(AdHttp.Req r, String name) {
        String v = r.param(name);
        if (v == null || v.isEmpty()) return 0;
        if (!v.matches("[0-9]{1,15}")) throw bad(name + " must be a number");
        return Long.parseLong(v);
    }

    private static final class Scope {
        long from;
        long to;
        long campaign;
        long site;
        AdSecurity.User user;
        Map<Long, JSONObject> campaigns = new HashMap<>();
        Map<Long, JSONObject> sites = new HashMap<>();
        Map<Long, JSONObject> placements = new HashMap<>();
        Map<Long, JSONObject> creatives = new HashMap<>();
    }

    private Scope scope(AdHttp.Req r, AdSecurity.User u) {
        Scope sc = new Scope();
        sc.to = parseDay(r.param("to"), AdDb.today());
        sc.from = parseDay(r.param("from"), sc.to - 29);
        if (sc.to < sc.from) throw bad("'to' must not be before 'from'");
        if (sc.to - sc.from > 366) throw bad("Date range is limited to one year");
        sc.campaign = id(r, "campaign_id");
        sc.site = id(r, "site_id");
        sc.user = u;
        for (JSONObject c : db.list(AdDb.CAMPAIGNS, x -> true)) sc.campaigns.put(c.optLong("id"), c);
        for (JSONObject s : db.list(AdDb.SITES, x -> true)) sc.sites.put(s.optLong("id"), s);
        for (JSONObject p : db.list(AdDb.PLACEMENTS, x -> true)) sc.placements.put(p.optLong("id"), p);
        for (JSONObject c : db.list(AdDb.CREATIVES, x -> true)) sc.creatives.put(c.optLong("id"), c);
        return sc;
    }

    private boolean allowed(Scope sc, JSONObject row) {
        long day = row.optLong("day");
        if (day < sc.from || day > sc.to) return false;
        if (sc.campaign > 0 && row.optLong("campaign_id") != sc.campaign) return false;
        if (sc.site > 0 && row.optLong("site_id") != sc.site) return false;
        JSONObject c = sc.campaigns.get(row.optLong("campaign_id"));
        JSONObject s = sc.sites.get(row.optLong("site_id"));
        if ("advertiser".equals(sc.user.role) && (c == null || c.optLong("advertiser_id") != sc.user.id)) return false;
        return !"publisher".equals(sc.user.role) || (s != null && s.optLong("owner_id") == sc.user.id);
    }

    private static JSONObject emptyMetrics() {
        return obj("served", 0L, "imps", 0L, "invalid_imps", 0L, "clicks", 0L, "invalid_clicks", 0L,
                "spend_micros", 0L, "earnings_micros", 0L, "conversions", 0L);
    }

    private static void add(JSONObject total, JSONObject row, AdSecurity.User u) {
        add(total, "served", row.optLong("served"));
        add(total, "imps", row.optLong("imps"));
        add(total, "invalid_imps", row.optLong("invalid_imps"));
        add(total, "clicks", row.optLong("clicks"));
        add(total, "invalid_clicks", row.optLong("invalid_clicks"));
        add(total, "conversions", row.optLong("conversions"));
        if (u.isAdmin() || "advertiser".equals(u.role)) add(total, "spend_micros", row.optLong("spend_micros"));
        if (u.isAdmin() || "publisher".equals(u.role)) add(total, "earnings_micros", row.optLong("pub_micros"));
    }

    private static void add(JSONObject total, String key, long n) {
        AdHttp.put(total, key, total.optLong(key) + n);
    }

    private static final String[] METRICS = {"served", "imps", "invalid_imps", "clicks", "invalid_clicks", "spend_micros", "earnings_micros", "conversions"};

    AdHttp.Res summary(AdHttp.Req r, AdSecurity.User u) {
        Scope sc = scope(r, u);
        Map<Long, JSONObject> days = new HashMap<>();
        JSONObject total = emptyMetrics();
        for (JSONObject row : db.list(AdDb.STATS, x -> true)) {
            if (!allowed(sc, row)) continue;
            long day = row.optLong("day");
            JSONObject d = days.get(day);
            if (d == null) {
                d = emptyMetrics();
                AdHttp.put(d, "day", day);
                days.put(day, d);
            }
            add(d, row, u);
            add(total, row, u);
        }
        List<JSONObject> sorted = new ArrayList<>(days.values());
        sorted.sort(Comparator.comparingLong(o -> o.optLong("day")));
        JSONArray out = new JSONArray();
        for (JSONObject d : sorted) {
            AdHttp.put(d, "date", dayString(d.optLong("day")));
            out.put(d);
        }
        return AdHttp.ok(obj("from", dayString(sc.from), "to", dayString(sc.to), "total", total, "days", out));
    }

    private static String groupKey(String by, JSONObject row, Scope sc) {
        switch (by) {
            case "day": return String.valueOf(row.optLong("day"));
            case "site": return String.valueOf(row.optLong("site_id"));
            case "placement": return String.valueOf(row.optLong("placement_id"));
            case "campaign": return String.valueOf(row.optLong("campaign_id"));
            default: return String.valueOf(row.optLong("creative_id"));
        }
    }

    private static String label(String by, JSONObject row, Scope sc) {
        switch (by) {
            case "day": return dayString(row.optLong("day"));
            case "site": {
                JSONObject s = sc.sites.get(row.optLong("site_id"));
                return s == null ? "#" + row.optLong("site_id") : s.optString("domain");
            }
            case "placement": {
                JSONObject p = sc.placements.get(row.optLong("placement_id"));
                return p == null ? "#" + row.optLong("placement_id") : p.optString("name");
            }
            case "campaign": {
                JSONObject c = sc.campaigns.get(row.optLong("campaign_id"));
                return c == null ? "#" + row.optLong("campaign_id") : c.optString("name");
            }
            default: {
                JSONObject c = sc.creatives.get(row.optLong("creative_id"));
                if (c == null) return "deleted creative #" + row.optLong("creative_id");
                String title = c.optString("title");
                return title.isEmpty() ? c.optString("type") + " #" + c.optLong("id") : title;
            }
        }
    }

    private JSONArray breakdownRows(AdHttp.Req r, AdSecurity.User u) {
        String by = r.param("by");
        if (by == null) by = "";
        boolean publisher = "publisher".equals(u.role);
        if (!by.matches(publisher ? "day|site|placement" : "day|campaign|creative|site|placement")) {
            throw bad(publisher ? "by must be day, site or placement" : "by must be day, campaign, creative, site or placement");
        }
        Scope sc = scope(r, u);
        Map<String, JSONObject> groups = new HashMap<>();
        for (JSONObject row : db.list(AdDb.STATS, x -> true)) {
            if (!allowed(sc, row)) continue;
            String key = groupKey(by, row, sc);
            JSONObject g = groups.get(key);
            if (g == null) {
                g = emptyMetrics();
                AdHttp.put(g, "key", "day".equals(by) ? row.optLong("day") : (long) 0);
                AdHttp.put(g, "label", label(by, row, sc));
                if ("day".equals(by)) AdHttp.put(g, "key", row.optLong("day"));
                else {
                    try {
                        AdHttp.put(g, "key", Long.parseLong(key));
                    } catch (NumberFormatException e) {
                        AdHttp.put(g, "key", 0L);
                    }
                }
                groups.put(key, g);
            }
            add(g, row, u);
        }
        List<JSONObject> rows = new ArrayList<>(groups.values());
        rows.sort((a, b) -> {
            int cmp = Long.compare(b.optLong("imps"), a.optLong("imps"));
            return cmp != 0 ? cmp : Long.compare(b.optLong("served"), a.optLong("served"));
        });
        JSONArray out = new JSONArray();
        for (int i = 0; i < rows.size() && i < 500; i++) {
            JSONObject g = rows.get(i);
            if ("day".equals(by)) AdHttp.put(g, "label", dayString(g.optLong("key")));
            out.put(g);
        }
        return out;
    }

    AdHttp.Res breakdown(AdHttp.Req r, AdSecurity.User u) {
        return AdHttp.ok(breakdownRows(r, u));
    }

    private static String money(long micros) {
        return String.format(Locale.US, "%.4f", micros / 1_000_000.0);
    }

    private static String csv(String s) {
        if (s == null) s = "";
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    AdHttp.Res export(AdHttp.Req r, AdSecurity.User u) {
        JSONArray rows = breakdownRows(r, u);
        boolean spend = u.isAdmin() || "advertiser".equals(u.role), earn = u.isAdmin() || "publisher".equals(u.role);
        StringBuilder sb = new StringBuilder("name,served,impressions,invalid_impressions,clicks,invalid_clicks,conversions,ctr_percent");
        if (spend) sb.append(",spend");
        if (earn) sb.append(",earnings");
        sb.append("\r\n");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject d = rows.optJSONObject(i);
            long imps = d.optLong("imps"), clicks = d.optLong("clicks");
            sb.append(csv(d.optString("label"))).append(',').append(d.optLong("served")).append(',').append(imps).append(',')
                    .append(d.optLong("invalid_imps")).append(',').append(clicks).append(',').append(d.optLong("invalid_clicks")).append(',')
                    .append(d.optLong("conversions")).append(',')
                    .append(imps == 0 ? "0.00" : String.format(Locale.US, "%.2f", clicks * 100.0 / imps));
            if (spend) sb.append(',').append(money(d.optLong("spend_micros")));
            if (earn) sb.append(',').append(money(d.optLong("earnings_micros")));
            sb.append("\r\n");
        }
        String by = r.param("by") == null ? "" : r.param("by").replaceAll("[^a-z]", "");
        return AdHttp.text(200, "text/csv; charset=utf-8", sb.toString())
                .header("Content-Disposition", "attachment; filename=\"adnet-report-" + by + ".csv\"");
    }

    AdHttp.Res billing(AdHttp.Req r, AdSecurity.User u) {
        long target = u.id;
        if (u.isAdmin()) target = id(r, "user_id");
        JSONArray lines = new JSONArray();
        JSONObject summary = new JSONObject();
        Map<Long, JSONObject> users = new HashMap<>();
        for (JSONObject x : db.list(AdDb.USERS, y -> true)) users.put(x.optLong("id"), x);
        Map<Long, JSONObject> campaigns = new HashMap<>();
        for (JSONObject x : db.list(AdDb.CAMPAIGNS, y -> true)) campaigns.put(x.optLong("id"), x);
        Map<Long, JSONObject> sites = new HashMap<>();
        for (JSONObject x : db.list(AdDb.SITES, y -> true)) sites.put(x.optLong("id"), x);

        if (u.isAdmin() && target == 0) {
            List<JSONObject> ledger = db.list(AdDb.BILLING, x -> true);
            ledger.sort((a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
            for (int i = 0; i < ledger.size() && i < 300; i++) {
                JSONObject b = ledger.get(i);
                JSONObject us = users.get(b.optLong("user_id"));
                String email = us == null ? "#" + b.optLong("user_id") : us.optString("email");
                lines.put(obj("ts", b.optLong("ts"), "type", b.optString("type"),
                        "description", email + (b.optString("note").isEmpty() ? "" : " - " + b.optString("note")),
                        "amount_micros", b.optLong("amount_micros")));
            }
            long spend = 0, earn = 0;
            for (JSONObject s : db.list(AdDb.STATS, x -> true)) {
                spend += s.optLong("spend_micros");
                earn += s.optLong("pub_micros");
            }
            long balances = 0;
            for (JSONObject us : users.values()) if ("advertiser".equals(us.optString("role"))) balances += us.optLong("balance_micros");
            long paid = 0;
            for (JSONObject b : db.list(AdDb.BILLING, x -> "payout".equals(x.optString("type")))) paid -= b.optLong("amount_micros");
            summary = obj("advertiser_spend_micros", spend, "publisher_earnings_micros", earn,
                    "platform_margin_micros", spend - earn, "advertiser_balances_micros", balances, "paid_out_micros", paid);
            return AdHttp.ok(obj("summary", summary, "lines", lines));
        }

        JSONObject user = db.copy(AdDb.USERS, target);
        if (user == null) throw AdHttp.notFound("User not found");
        final long userId = target;
        List<JSONObject> ledger = db.list(AdDb.BILLING, x -> x.optLong("user_id") == userId);
        ledger.sort((a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
        for (int i = 0; i < ledger.size() && i < 300; i++) {
            JSONObject b = ledger.get(i);
            lines.put(obj("ts", b.optLong("ts"), "type", b.optString("type"),
                    "description", b.optString("note"), "amount_micros", b.optLong("amount_micros")));
        }
        if ("advertiser".equals(user.optString("role"))) {
            Map<String, JSONObject> spend = new HashMap<>();
            long totalSpent = 0;
            for (JSONObject s : db.list(AdDb.STATS, x -> true)) {
                JSONObject c = campaigns.get(s.optLong("campaign_id"));
                if (c == null || c.optLong("advertiser_id") != userId) continue;
                totalSpent += s.optLong("spend_micros");
                if (s.optLong("spend_micros") <= 0) continue;
                String key = s.optLong("day") + ":" + s.optLong("campaign_id");
                JSONObject g = spend.get(key);
                if (g == null) {
                    g = obj("day", s.optLong("day"), "name", c.optString("name"), "amt", 0L);
                    spend.put(key, g);
                }
                AdHttp.put(g, "amt", g.optLong("amt") + s.optLong("spend_micros"));
            }
            List<JSONObject> rows = new ArrayList<>(spend.values());
            rows.sort((a, b) -> Long.compare(b.optLong("day"), a.optLong("day")));
            for (int i = 0; i < rows.size() && i < 300; i++) {
                JSONObject d = rows.get(i);
                lines.put(obj("ts", d.optLong("day") * 86_400_000L, "type", "spend",
                        "description", d.optString("name") + " (" + dayString(d.optLong("day")) + ")",
                        "amount_micros", -d.optLong("amt")));
            }
            summary = obj("balance_micros", user.optLong("balance_micros"), "total_spent_micros", totalSpent);
        } else if ("publisher".equals(user.optString("role"))) {
            Map<String, JSONObject> earn = new HashMap<>();
            long totalEarned = 0;
            for (JSONObject s : db.list(AdDb.STATS, x -> true)) {
                JSONObject site = sites.get(s.optLong("site_id"));
                if (site == null || site.optLong("owner_id") != userId) continue;
                totalEarned += s.optLong("pub_micros");
                if (s.optLong("pub_micros") <= 0) continue;
                String key = s.optLong("day") + ":" + s.optLong("site_id");
                JSONObject g = earn.get(key);
                if (g == null) {
                    g = obj("day", s.optLong("day"), "domain", site.optString("domain"), "amt", 0L);
                    earn.put(key, g);
                }
                AdHttp.put(g, "amt", g.optLong("amt") + s.optLong("pub_micros"));
            }
            List<JSONObject> rows = new ArrayList<>(earn.values());
            rows.sort((a, b) -> Long.compare(b.optLong("day"), a.optLong("day")));
            for (int i = 0; i < rows.size() && i < 300; i++) {
                JSONObject d = rows.get(i);
                lines.put(obj("ts", d.optLong("day") * 86_400_000L, "type", "earning",
                        "description", d.optString("domain") + " (" + dayString(d.optLong("day")) + ")",
                        "amount_micros", d.optLong("amt")));
            }
            long paid = 0;
            for (JSONObject b : db.list(AdDb.BILLING, x -> x.optLong("user_id") == userId && "payout".equals(x.optString("type")))) {
                paid -= b.optLong("amount_micros");
            }
            summary = obj("earned_micros", totalEarned, "paid_micros", paid, "unpaid_micros", totalEarned - paid);
        }
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) sorted.add(lines.optJSONObject(i));
        sorted.sort((a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
        JSONArray out = new JSONArray();
        for (JSONObject o : sorted) out.put(o);
        return AdHttp.ok(obj("summary", summary, "lines", out));
    }
}
