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

/** Country-level reporting from the privacy-preserving geo_daily counters. */
final class AdGeoReport {
    private AdGeoReport() {}

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

    private static long id(AdHttp.Req r, String name) {
        String v = r.param(name);
        if (v == null || v.isEmpty()) return 0;
        if (!v.matches("[0-9]{1,15}")) throw AdHttp.bad(name + " must be a number");
        return Long.parseLong(v);
    }

    private static final class Scope {
        long from, to, campaign, site;
        AdSecurity.User user;
        Map<Long, JSONObject> campaigns = new HashMap<>();
        Map<Long, JSONObject> sites = new HashMap<>();
    }

    private static boolean allowed(Scope sc, JSONObject row) {
        long day = row.optLong("day");
        if (day < sc.from || day > sc.to) return false;
        if (sc.campaign > 0 && row.optLong("campaign_id") != sc.campaign) return false;
        if (sc.site > 0 && row.optLong("site_id") != sc.site) return false;
        JSONObject c = sc.campaigns.get(row.optLong("campaign_id"));
        JSONObject s = sc.sites.get(row.optLong("site_id"));
        if ("advertiser".equals(sc.user.role) && (c == null || c.optLong("advertiser_id") != sc.user.id)) return false;
        return !"publisher".equals(sc.user.role) || (s != null && s.optLong("owner_id") == sc.user.id);
    }

    private static Scope scoped(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        Scope sc = new Scope();
        sc.to = parseDay(r.param("to"), AdDb.today());
        sc.from = parseDay(r.param("from"), sc.to - 29);
        if (sc.to < sc.from) throw AdHttp.bad("'to' must not be before 'from'");
        if (sc.to - sc.from > 366) throw AdHttp.bad("Date range is limited to one year");
        sc.campaign = id(r, "campaign_id");
        sc.site = id(r, "site_id");
        sc.user = u;
        for (JSONObject c : db.list(AdDb.CAMPAIGNS, x -> true)) sc.campaigns.put(c.optLong("id"), c);
        for (JSONObject s : db.list(AdDb.SITES, x -> true)) sc.sites.put(s.optLong("id"), s);
        return sc;
    }

    private static JSONObject rows(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        Scope sc = scoped(r, u, db);
        Map<String, JSONObject> byCountry = new HashMap<>();
        JSONObject total = AdHttp.obj("imps", 0L, "invalid_imps", 0L, "clicks", 0L, "conversions", 0L,
                "spend_micros", 0L, "earnings_micros", 0L);
        for (JSONObject row : db.list(AdDb.GEO, x -> true)) {
            if (!allowed(sc, row)) continue;
            String country = row.optString("country", "ZZ");
            if (country.isEmpty()) country = "ZZ";
            JSONObject g = byCountry.get(country);
            if (g == null) {
                g = AdHttp.obj("country", country, "imps", 0L, "invalid_imps", 0L, "clicks", 0L,
                        "conversions", 0L, "spend_micros", 0L, "earnings_micros", 0L);
                byCountry.put(country, g);
            }
            add(g, total, row, u);
        }
        List<JSONObject> list = new ArrayList<>(byCountry.values());
        list.sort((a, b) -> Long.compare(b.optLong("imps"), a.optLong("imps")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size() && i < 250; i++) out.put(list.get(i));
        return AdHttp.obj("from", AdReports.dayString(sc.from), "to", AdReports.dayString(sc.to),
                "total", total, "countries", out);
    }

    private static void add(JSONObject target, JSONObject total, JSONObject row, AdSecurity.User u) {
        for (String key : new String[]{"imps", "invalid_imps", "clicks", "conversions"}) {
            long n = row.optLong(key);
            AdHttp.put(target, key, target.optLong(key) + n);
            AdHttp.put(total, key, total.optLong(key) + n);
        }
        if (u.isAdmin() || "advertiser".equals(u.role)) {
            long n = row.optLong("spend_micros");
            AdHttp.put(target, "spend_micros", target.optLong("spend_micros") + n);
            AdHttp.put(total, "spend_micros", total.optLong("spend_micros") + n);
        }
        if (u.isAdmin() || "publisher".equals(u.role)) {
            long n = row.optLong("pub_micros");
            AdHttp.put(target, "earnings_micros", target.optLong("earnings_micros") + n);
            AdHttp.put(total, "earnings_micros", total.optLong("earnings_micros") + n);
        }
    }

    static AdHttp.Res breakdown(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        return AdHttp.ok(rows(r, u, db));
    }

    static AdHttp.Res export(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        JSONObject data = rows(r, u, db);
        JSONArray list = data.optJSONArray("countries");
        boolean spend = u.isAdmin() || "advertiser".equals(u.role);
        boolean earn = u.isAdmin() || "publisher".equals(u.role);
        StringBuilder sb = new StringBuilder("country,impressions,invalid_impressions,clicks,conversions,ctr_percent");
        if (spend) sb.append(",spend");
        if (earn) sb.append(",earnings");
        sb.append("\r\n");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject g = list.optJSONObject(i);
            long imps = g.optLong("imps"), clicks = g.optLong("clicks");
            sb.append(csv(g.optString("country"))).append(',').append(imps).append(',')
                    .append(g.optLong("invalid_imps")).append(',').append(clicks).append(',')
                    .append(g.optLong("conversions")).append(',')
                    .append(imps == 0 ? "0.00" : String.format(Locale.US, "%.2f", clicks * 100.0 / imps));
            if (spend) sb.append(',').append(money(g.optLong("spend_micros")));
            if (earn) sb.append(',').append(money(g.optLong("earnings_micros")));
            sb.append("\r\n");
        }
        return AdHttp.text(200, "text/csv; charset=utf-8", sb.toString())
                .header("Content-Disposition", "attachment; filename=\"adnet-geo-"
                        + data.optString("from") + "-" + data.optString("to") + ".csv\"");
    }

    private static String money(long micros) {
        return String.format(Locale.US, "%.4f", micros / 1_000_000.0);
    }

    private static String csv(String s) {
        if (s == null) s = "";
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
