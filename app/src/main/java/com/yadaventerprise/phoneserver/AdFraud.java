package com.yadaventerprise.phoneserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

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

    /** Returns the reason an impression is invalid, or null if it looks genuine. */
    static String checkImpression(AdDb db, String ua, String ip, String visitor, long campaignId, boolean domainOk) {
        if (isBot(ua)) return "bot_ua";
        if (!domainOk) return "domain_mismatch";
        if (!AdRateLimiter.allow("imp:" + ip, 120, 60_000)) return "imp_flood";
        if (!visitor.isEmpty()) {
            long cap = db.settingLong("fraud_max_imps_visitor_day", 50);
            long n = db.scalar("SELECT COUNT(*) FROM events WHERE visitor=? AND campaign_id=? AND type='imp' AND valid=1 AND ts>=?",
                    0, visitor, campaignId, AdDb.startOfDayMs());
            if (n >= cap) return "visitor_cap";
        }
        return null;
    }

    /** Returns the reason a click is invalid, or null if it looks genuine. impTs is when the impression was recorded. */
    static String checkClick(AdDb db, String ua, String ip, String visitor, long campaignId, boolean domainOk, long impTs) {
        if (isBot(ua)) return "bot_ua";
        if (!domainOk) return "domain_mismatch";
        if (!AdRateLimiter.allow("clk:" + ip, (int) db.settingLong("fraud_ip_clicks_min", 10), 60_000)) return "ip_click_rate";
        long now = System.currentTimeMillis();
        if (now - impTs < db.settingLong("fraud_min_click_ms", 500)) return "too_fast";
        if (!visitor.isEmpty()) {
            long n = db.scalar("SELECT COUNT(*) FROM events WHERE visitor=? AND campaign_id=? AND type='click' AND valid=1 AND ts>=?",
                    0, visitor, campaignId, now - 3_600_000L);
            if (n >= db.settingLong("fraud_repeat_clicks_hour", 3)) return "repeat_click";
        }
        return null;
    }

    /** Flags (never auto-bans) sites whose recent traffic looks abnormal, for an admin to review. */
    static void reviewSites(AdDb db) {
        JSONArray rows = db.query("SELECT site_id, SUM(imps) imps, SUM(clicks) clicks, SUM(invalid_imps) inv_i,"
                + " SUM(invalid_clicks) inv_c FROM stats_daily WHERE day>=? GROUP BY site_id", AdDb.today() - 1);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            long imps = r.optLong("imps"), clicks = r.optLong("clicks");
            long invalid = r.optLong("inv_i") + r.optLong("inv_c");
            long total = imps + clicks + invalid;
            String reason = null;
            if (imps >= 100 && clicks * 100 / imps > 15) {
                reason = "Unusually high click-through rate (" + (clicks * 100 / imps) + "%) in the last 48 hours";
            } else if (total >= 50 && invalid * 100 / total > 40) {
                reason = "High share of invalid traffic (" + (invalid * 100 / total) + "%) in the last 48 hours";
            }
            if (reason != null) {
                db.run("UPDATE sites SET flagged=1, flag_reason=? WHERE id=? AND flagged=0 AND status<>'removed'",
                        reason, r.optLong("site_id"));
            }
        }
    }
}
