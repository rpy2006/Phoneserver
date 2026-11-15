package com.yadaventerprise.phoneserver.ads;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Invalid-traffic rules. An event that fails a rule is still recorded (so reports show it as invalid)
 * but is never billed to the advertiser and never earns the publisher anything.
 */
final class AdFraud {
    private AdFraud() {}

    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|headless|phantom|selenium|puppeteer|playwright|curl/|wget|python-requests|python-urllib"
                    + "|httpclient|go-http|libwww|scrapy|postman|lighthouse|facebookexternalhit|monitor|preview",
            Pattern.CASE_INSENSITIVE);

    static boolean isBot(String ua) {
        return ua == null || ua.length() < 12 || BOT.matcher(ua).find();
    }

    static String checkImpression(AdDb db, String ua, String ip, String visitor, long campaignId, boolean domainOk) {
        if (isBot(ua)) return "bot_ua";
        if (!domainOk) return "domain_mismatch";
        if (!AdRateLimiter.allow("imp:" + ip, 120, 60_000)) return "imp_flood";
        if (!visitor.isEmpty()) {
            long cap = db.settingLong("fraud_max_imps_visitor_day", 50);
            long n = db.count(AdDb.EVENTS, e -> visitor.equals(e.optString("visitor")) && e.optLong("campaign_id") == campaignId
                    && "imp".equals(e.optString("type")) && e.optLong("valid") == 1 && e.optLong("ts") >= AdDb.startOfDayMs());
            if (n >= cap) return "visitor_cap";
        }
        return null;
    }

    static String checkClick(AdDb db, String ua, String ip, String visitor, long campaignId, boolean domainOk, long impTs) {
        if (isBot(ua)) return "bot_ua";
        if (!domainOk) return "domain_mismatch";
        if (!AdRateLimiter.allow("clk:" + ip, (int) db.settingLong("fraud_ip_clicks_min", 10), 60_000)) return "ip_click_rate";
        long now = System.currentTimeMillis();
        if (now - impTs < db.settingLong("fraud_min_click_ms", 500)) return "too_fast";
        if (!visitor.isEmpty()) {
            long n = db.count(AdDb.EVENTS, e -> visitor.equals(e.optString("visitor")) && e.optLong("campaign_id") == campaignId
                    && "click".equals(e.optString("type")) && e.optLong("valid") == 1 && e.optLong("ts") >= now - 3_600_000L);
            if (n >= db.settingLong("fraud_repeat_clicks_hour", 3)) return "repeat_click";
        }
        return null;
    }

    static void reviewSites(AdDb db) {
        long from = AdDb.today() - 1;
        Map<Long, long[]> bySite = new HashMap<>();
        for (JSONObject s : db.list(AdDb.STATS, x -> x.optLong("day") >= from)) {
            long[] v = bySite.get(s.optLong("site_id"));
            if (v == null) {
                v = new long[4];
                bySite.put(s.optLong("site_id"), v);
            }
            v[0] += s.optLong("imps");
            v[1] += s.optLong("clicks");
            v[2] += s.optLong("invalid_imps");
            v[3] += s.optLong("invalid_clicks");
        }
        for (Map.Entry<Long, long[]> e : bySite.entrySet()) {
            long[] v = e.getValue();
            long imps = v[0], clicks = v[1], invalid = v[2] + v[3];
            long total = imps + clicks + invalid;
            String reason = null;
            if (imps >= 100 && clicks * 100 / imps > 15) {
                reason = "Unusually high click-through rate (" + (clicks * 100 / imps) + "%) in the last 48 hours";
            } else if (total >= 50 && invalid * 100 / total > 40) {
                reason = "High share of invalid traffic (" + (invalid * 100 / total) + "%) in the last 48 hours";
            }
            if (reason != null) {
                String why = reason;
                db.update(AdDb.SITES, e.getKey(), row -> {
                    if (row.optLong("flagged") == 0 && !"removed".equals(row.optString("status"))) {
                        AdHttp.put(row, "flagged", 1);
                        AdHttp.put(row, "flag_reason", why);
                    }
                });
            }
        }
    }
}
