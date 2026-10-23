package com.yadaventerprise.phoneserver;

import static com.yadaventerprise.phoneserver.AdHttp.bad;
import static com.yadaventerprise.phoneserver.AdHttp.obj;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Reports, CSV export and billing records. Every number comes from stats_daily (real tracked events) or the billing ledger.
 * Advertisers never see publisher earnings; publishers never see advertiser spend or which campaigns ran.
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

    private static final String FROM = " FROM stats_daily s JOIN campaigns c ON c.id=s.campaign_id JOIN sites st ON st.id=s.site_id"
            + " LEFT JOIN placements p ON p.id=s.placement_id LEFT JOIN creatives cr ON cr.id=s.creative_id";

    private static final class Scope {
        final StringBuilder where = new StringBuilder(" WHERE s.day BETWEEN ? AND ?");
        final List<Object> args = new ArrayList<>();
        long from, to;
    }

    private Scope scope(AdHttp.Req r, AdSecurity.User u) {
        Scope sc = new Scope();
        sc.to = parseDay(r.param("to"), AdDb.today());
        sc.from = parseDay(r.param("from"), sc.to - 29);
        if (sc.to < sc.from) throw bad("'to' must not be before 'from'");
        if (sc.to - sc.from > 366) throw bad("Date range is limited to one year");
        sc.args.add(sc.from);
        sc.args.add(sc.to);
        if ("advertiser".equals(u.role)) {
            sc.where.append(" AND c.advertiser_id=?");
            sc.args.add(u.id);
        } else if ("publisher".equals(u.role)) {
            sc.where.append(" AND st.owner_id=?");
            sc.args.add(u.id);
        }
        long campaign = id(r, "campaign_id"), site = id(r, "site_id");
        if (campaign > 0) { sc.where.append(" AND s.campaign_id=?"); sc.args.add(campaign); }
        if (site > 0) { sc.where.append(" AND s.site_id=?"); sc.args.add(site); }
        return sc;
    }

    private static String metrics(AdSecurity.User u) {
        String spend = u.isAdmin() || "advertiser".equals(u.role) ? "SUM(s.spend_micros)" : "0";
        String earn = u.isAdmin() || "publisher".equals(u.role) ? "SUM(s.pub_micros)" : "0";
        return "SUM(s.served) served, SUM(s.imps) imps, SUM(s.invalid_imps) invalid_imps, SUM(s.clicks) clicks,"
                + " SUM(s.invalid_clicks) invalid_clicks, " + spend + " spend_micros, " + earn + " earnings_micros";
    }

    AdHttp.Res summary(AdHttp.Req r, AdSecurity.User u) {
        Scope sc = scope(r, u);
        JSONArray days = db.query("SELECT s.day, " + metrics(u) + FROM + sc.where + " GROUP BY s.day ORDER BY s.day", sc.args.toArray());
        JSONObject total = obj("served", 0L, "imps", 0L, "invalid_imps", 0L, "clicks", 0L, "invalid_clicks", 0L, "spend_micros", 0L, "earnings_micros", 0L);
        String[] cols = {"served", "imps", "invalid_imps", "clicks", "invalid_clicks", "spend_micros", "earnings_micros"};
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            AdHttp.put(d, "date", dayString(d.optLong("day")));
            for (String c : cols) AdHttp.put(total, c, total.optLong(c) + d.optLong(c));
        }
        return AdHttp.ok(obj("from", dayString(sc.from), "to", dayString(sc.to), "total", total, "days", days));
    }

    private static String groupExpr(String by, AdSecurity.User u) {
        boolean publisher = "publisher".equals(u.role);
        switch (by == null ? "" : by) {
            case "day": return "s.day";
            case "site": return "s.site_id";
            case "placement": return "s.placement_id";
            case "campaign": if (publisher) break; return "s.campaign_id";
            case "creative": if (publisher) break; return "s.creative_id";
            default: break;
        }
        throw bad(publisher ? "by must be day, site or placement" : "by must be day, campaign, creative, site or placement");
    }

    private static String labelExpr(String by) {
        switch (by) {
            case "day": return "s.day";
            case "site": return "st.domain";
            case "placement": return "COALESCE(p.name, '#' || s.placement_id)";
            case "campaign": return "c.name";
            default: return "COALESCE(NULLIF(cr.title,''), cr.type || ' #' || s.creative_id, 'deleted creative #' || s.creative_id)";
        }
    }

    private JSONArray breakdownRows(AdHttp.Req r, AdSecurity.User u) {
        String by = r.param("by");
        String group = groupExpr(by, u);
        Scope sc = scope(r, u);
        JSONArray rows = db.query("SELECT " + group + " key, MAX(" + labelExpr(by) + ") label, " + metrics(u)
                + FROM + sc.where + " GROUP BY " + group + " ORDER BY SUM(s.imps) DESC, SUM(s.served) DESC LIMIT 500", sc.args.toArray());
        if ("day".equals(by)) {
            for (int i = 0; i < rows.length(); i++) AdHttp.put(rows.optJSONObject(i), "label", dayString(rows.optJSONObject(i).optLong("key")));
        }
        return rows;
    }

    AdHttp.Res breakdown(AdHttp.Req r, AdSecurity.User u) {
        return AdHttp.ok(breakdownRows(r, u));
    }

    private static String money(long micros) {
        return String.format(Locale.US, "%.4f", micros / 1_000_000.0);
    }

    /** Spreadsheet programs run cells that start with = + - @ as formulas, so those are neutralised. */
    private static String csv(String s) {
        if (s == null) s = "";
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    AdHttp.Res export(AdHttp.Req r, AdSecurity.User u) {
        JSONArray rows = breakdownRows(r, u);
        boolean spend = u.isAdmin() || "advertiser".equals(u.role), earn = u.isAdmin() || "publisher".equals(u.role);
        StringBuilder sb = new StringBuilder("name,served,impressions,invalid_impressions,clicks,invalid_clicks,ctr_percent");
        if (spend) sb.append(",spend");
        if (earn) sb.append(",earnings");
        sb.append("\r\n");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject d = rows.optJSONObject(i);
            long imps = d.optLong("imps"), clicks = d.optLong("clicks");
            sb.append(csv(d.optString("label"))).append(',').append(d.optLong("served")).append(',').append(imps).append(',')
                    .append(d.optLong("invalid_imps")).append(',').append(clicks).append(',').append(d.optLong("invalid_clicks")).append(',')
                    .append(imps == 0 ? "0.00" : String.format(Locale.US, "%.2f", clicks * 100.0 / imps));
            if (spend) sb.append(',').append(money(d.optLong("spend_micros")));
            if (earn) sb.append(',').append(money(d.optLong("earnings_micros")));
            sb.append("\r\n");
        }
        return AdHttp.text(200, "text/csv; charset=utf-8", sb.toString())
                .header("Content-Disposition", "attachment; filename=\"adnet-report-" + (r.param("by") == null ? "" : r.param("by").replaceAll("[^a-z]", "")) + ".csv\"");
    }

    /** Billing records: manual ledger rows plus daily spend / earnings lines derived from tracked, valid events. */
    AdHttp.Res billing(AdHttp.Req r, AdSecurity.User u) {
        long target = u.id;
        if (u.isAdmin()) target = id(r, "user_id");
        JSONArray lines = new JSONArray();
        JSONObject summary = new JSONObject();

        if (u.isAdmin() && target == 0) {
            JSONArray ledger = db.query("SELECT b.ts, b.type, b.amount_micros, b.note, us.email FROM billing b JOIN users us ON us.id=b.user_id ORDER BY b.ts DESC LIMIT 300");
            for (int i = 0; i < ledger.length(); i++) {
                JSONObject b = ledger.optJSONObject(i);
                lines.put(obj("ts", b.optLong("ts"), "type", b.optString("type"), "description", b.optString("email") + (b.optString("note").isEmpty() ? "" : " - " + b.optString("note")),
                        "amount_micros", b.optLong("amount_micros")));
            }
            JSONObject t = db.one("SELECT COALESCE(SUM(spend_micros),0) spend, COALESCE(SUM(pub_micros),0) earnings FROM stats_daily");
            long spend = t.optLong("spend"), earn = t.optLong("earnings");
            summary = obj("advertiser_spend_micros", spend, "publisher_earnings_micros", earn, "platform_margin_micros", spend - earn,
                    "advertiser_balances_micros", db.scalar("SELECT COALESCE(SUM(balance_micros),0) FROM users WHERE role='advertiser'", 0),
                    "paid_out_micros", db.scalar("SELECT COALESCE(-SUM(amount_micros),0) FROM billing WHERE type='payout'", 0));
            return AdHttp.ok(obj("summary", summary, "lines", lines));
        }

        JSONObject user = db.one("SELECT id, role, email, balance_micros FROM users WHERE id=?", target);
        if (user == null) throw AdHttp.notFound("User not found");
        JSONArray ledger = db.query("SELECT ts, type, amount_micros, note FROM billing WHERE user_id=? ORDER BY ts DESC LIMIT 300", target);
        for (int i = 0; i < ledger.length(); i++) {
            JSONObject b = ledger.optJSONObject(i);
            lines.put(obj("ts", b.optLong("ts"), "type", b.optString("type"), "description", b.optString("note"), "amount_micros", b.optLong("amount_micros")));
        }
        if ("advertiser".equals(user.optString("role"))) {
            JSONArray spend = db.query("SELECT s.day, c.name, SUM(s.spend_micros) amt FROM stats_daily s JOIN campaigns c ON c.id=s.campaign_id"
                    + " WHERE c.advertiser_id=? GROUP BY s.day, s.campaign_id HAVING SUM(s.spend_micros)>0 ORDER BY s.day DESC LIMIT 300", target);
            for (int i = 0; i < spend.length(); i++) {
                JSONObject d = spend.optJSONObject(i);
                lines.put(obj("ts", d.optLong("day") * 86_400_000L, "type", "spend", "description", d.optString("name") + " (" + dayString(d.optLong("day")) + ")", "amount_micros", -d.optLong("amt")));
            }
            summary = obj("balance_micros", user.optLong("balance_micros"),
                    "total_spent_micros", db.scalar("SELECT COALESCE(SUM(s.spend_micros),0) FROM stats_daily s JOIN campaigns c ON c.id=s.campaign_id WHERE c.advertiser_id=?", 0, target));
        } else if ("publisher".equals(user.optString("role"))) {
            JSONArray earn = db.query("SELECT s.day, st.domain, SUM(s.pub_micros) amt FROM stats_daily s JOIN sites st ON st.id=s.site_id"
                    + " WHERE st.owner_id=? GROUP BY s.day, s.site_id HAVING SUM(s.pub_micros)>0 ORDER BY s.day DESC LIMIT 300", target);
            for (int i = 0; i < earn.length(); i++) {
                JSONObject d = earn.optJSONObject(i);
                lines.put(obj("ts", d.optLong("day") * 86_400_000L, "type", "earning", "description", d.optString("domain") + " (" + dayString(d.optLong("day")) + ")", "amount_micros", d.optLong("amt")));
            }
            long earned = db.scalar("SELECT COALESCE(SUM(s.pub_micros),0) FROM stats_daily s JOIN sites st ON st.id=s.site_id WHERE st.owner_id=?", 0, target);
            long paid = db.scalar("SELECT COALESCE(-SUM(amount_micros),0) FROM billing WHERE user_id=? AND type='payout'", 0, target);
            summary = obj("earned_micros", earned, "paid_micros", paid, "unpaid_micros", earned - paid);
        }
        // newest first
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) sorted.add(lines.optJSONObject(i));
        java.util.Collections.sort(sorted, (a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
        JSONArray out = new JSONArray();
        for (JSONObject o : sorted) out.put(o);
        return AdHttp.ok(obj("summary", summary, "lines", out));
    }
}
