package com.yadaventerprise.phoneserver.ads;

import android.util.Base64;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public conversion pixel/postback and the admin conversion ledger. */
final class AdConversion {
    private static final byte[] GIF = Base64.decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT);

    private AdConversion() {}

    static AdHttp.Res pixel(AdHttp.Req r, AdDb db) {
        try {
            handle(r, db, false);
        } catch (RuntimeException ignored) {
        }
        AdHttp.Res res = AdHttp.bytes(200, "image/gif", GIF);
        res.header("Cache-Control", "no-store, no-cache, must-revalidate");
        res.header("Access-Control-Allow-Origin", "*");
        return res;
    }

    static AdHttp.Res postback(AdHttp.Req r, AdDb db) {
        try {
            return AdHttp.ok(handle(r, db, true));
        } catch (AdHttp.HttpError e) {
            return AdHttp.json(e.status, AdHttp.obj("error", e.getMessage()));
        }
    }

    private static JSONObject handle(AdHttp.Req r, AdDb db, boolean signed) {
        if (!"1".equals(db.setting("conversion_tracking", "0"))) throw AdHttp.bad("Conversion tracking is disabled");
        String token = r.param("t");
        String event = cleanEvent(r.param("e"));
        if (token == null || token.isEmpty()) throw AdHttp.bad("Missing token");
        if (event.isEmpty()) throw AdHttp.bad("Missing event");
        String tx = AdSecurity.clean(r.param("tx"));
        if (tx.length() > 100) throw AdHttp.bad("Transaction id is too long");
        String payload = AdSecurity.verifyToken(db, token);
        if (payload == null) throw AdHttp.bad("Invalid token");
        String[] p = payload.split("\\|", -1);
        if (p.length != 8 || !"v1".equals(p[0])) throw AdHttp.bad("Invalid token");
        long campaignId, creativeId, placementId, siteId, issued;
        String nonce, visitor;
        try {
            nonce = p[1];
            campaignId = Long.parseLong(p[2]);
            creativeId = Long.parseLong(p[3]);
            placementId = Long.parseLong(p[4]);
            siteId = Long.parseLong(p[5]);
            issued = Long.parseLong(p[6]);
            visitor = p[7];
        } catch (NumberFormatException e) {
            throw AdHttp.bad("Invalid token");
        }

        JSONObject campaign = db.copy(AdDb.CAMPAIGNS, campaignId);
        if (signed) {
            if (campaign == null) throw AdHttp.forbidden("Invalid postback signature");
            String sig = r.param("sig");
            String expected = AdSecurity.hex(AdSecurity.hmac(postbackKey(db, campaign.optLong("advertiser_id")),
                    (token + "|" + event + "|" + safe(r.param("v")) + "|" + tx).getBytes(StandardCharsets.UTF_8)));
            if (sig == null || !AdSecurity.constantEquals(sig.toLowerCase(Locale.ROOT), expected)) {
                throw AdHttp.forbidden("Invalid postback signature");
            }
        }
        JSONObject click = db.find(AdDb.EVENTS, e -> nonce.equals(e.optString("nonce")) && "click".equals(e.optString("type")));
        long attribution = Math.max(1, db.settingLong("conversion_attribution_days", 30)) * 86_400_000L;
        long now = System.currentTimeMillis();
        String reason = null;
        if (campaign == null) reason = "unknown_campaign";
        else if (click == null) reason = "no_click";
        else if (click.optLong("valid") != 1) reason = "invalid_click";
        else if (now - click.optLong("ts") > attribution || now - issued > attribution) reason = "outside_window";
        else if (!campaign.optString("conversion_event").isEmpty()
                && !campaign.optString("conversion_event").equalsIgnoreCase(event)) reason = "wrong_event";
        long value = valueMicros(r.param("v"), campaign == null ? 0 : campaign.optLong("conversion_value_micros"));
        if (value < 0) reason = "bad_value";

        String dedupe = tx.isEmpty() ? nonce + "|" + event : nonce + "|" + event + "|" + AdSecurity.sha256Hex(tx);
        long dedupeWindow = Math.max(1, db.settingLong("conversion_dedupe_hours", 24)) * 3_600_000L;
        if (db.find(AdDb.CONVERSIONS, c -> dedupe.equals(c.optString("dedupe_key"))
                && c.optLong("ts") >= now - dedupeWindow) != null) {
            return AdHttp.obj("ok", true, "duplicate", true);
        }

        String resolvedCountry = click.optString("country", "");
        if (resolvedCountry.isEmpty() && !signed) resolvedCountry = AdGeo.country(db, r.ip);
        final String country = resolvedCountry;
        boolean valid = reason == null;
        JSONObject row = AdHttp.obj("ts", now, "day", AdDb.today(), "campaign_id", campaignId, "creative_id", creativeId,
                "placement_id", placementId, "site_id", siteId, "click_nonce", nonce, "visitor", visitor,
                "event", event, "value_micros", value, "currency", db.setting("currency", "USD"),
                "status", valid ? "valid" : "invalid", "reason", valid ? "" : reason, "source", signed ? "postback" : "pixel",
                "transaction_id", tx, "dedupe_key", dedupe, "country", country);
        db.transaction(() -> {
            db.insert(AdDb.CONVERSIONS, row);
            if (valid) db.adjustConversion(AdDb.today(), country, campaignId, creativeId, placementId, siteId, 1, value);
        });
        if (!valid) throw AdHttp.bad("Conversion rejected: " + reason.replace('_', ' '));
        return AdHttp.obj("ok", true, "duplicate", false);
    }

    private static byte[] postbackKey(AdDb db, long advertiserId) {
        byte[] root = Base64.decode(db.setting("postback_secret", ""), Base64.NO_WRAP);
        return AdSecurity.hmac(root, ("advertiser:" + advertiserId).getBytes(StandardCharsets.UTF_8));
    }

    static AdHttp.Res key(AdSecurity.User u, AdDb db) {
        if (!u.isAdmin() && !"advertiser".equals(u.role)) throw AdHttp.forbidden("Only advertisers can use conversion postbacks");
        if (u.isAdmin()) return AdHttp.ok(AdHttp.obj("secret", "", "format", "Postback keys are unique to each advertiser account"));
        return AdHttp.ok(AdHttp.obj("secret", AdSecurity.hex(postbackKey(db, u.id)),
                "format", "hex HMAC-SHA256 of t|e|v|tx using this key"));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String cleanEvent(String s) {
        String v = AdSecurity.clean(s);
        return v.length() <= 40 && v.matches("[A-Za-z0-9._-]+") ? v : "";
    }

    private static long valueMicros(String v, long fallback) {
        if (v == null || v.trim().isEmpty()) return fallback;
        try {
            BigDecimal amount = new BigDecimal(v.trim());
            if (amount.signum() < 0) return -1;
            if (amount.compareTo(new BigDecimal("1000000000")) > 0) return -1;
            return amount.setScale(6, RoundingMode.HALF_UP).movePointRight(6).longValueExact();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static AdHttp.Res list(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        long from = dayParam(r.param("from"), AdDb.today() - 29);
        long to = dayParam(r.param("to"), AdDb.today());
        if (to < from) throw AdHttp.bad("'to' must not be before 'from'");
        if (to - from > 366) throw AdHttp.bad("Date range is limited to one year");
        long campaign = longParam(r.param("campaign_id"));
        long site = longParam(r.param("site_id"));
        String status = r.param("status");
        Map<Long, JSONObject> campaigns = new HashMap<>();
        for (JSONObject c : db.list(AdDb.CAMPAIGNS, x -> true)) campaigns.put(c.optLong("id"), c);
        Map<Long, JSONObject> sites = new HashMap<>();
        for (JSONObject s : db.list(AdDb.SITES, x -> true)) sites.put(s.optLong("id"), s);
        Map<Long, String> eventNames = new HashMap<>();
        for (JSONObject c : campaigns.values()) eventNames.put(c.optLong("id"), c.optString("name"));
        List<JSONObject> rows = new ArrayList<>();
        for (JSONObject c : db.list(AdDb.CONVERSIONS, x -> x.optLong("day") >= from && x.optLong("day") <= to
                && (campaign == 0 || x.optLong("campaign_id") == campaign)
                && (site == 0 || x.optLong("site_id") == site)
                && (status == null || status.isEmpty() || status.equals(x.optString("status"))))) {
            JSONObject camp = campaigns.get(c.optLong("campaign_id"));
            JSONObject st = sites.get(c.optLong("site_id"));
            if ("advertiser".equals(u.role) && (camp == null || camp.optLong("advertiser_id") != u.id)) continue;
            if ("publisher".equals(u.role) && (st == null || st.optLong("owner_id") != u.id)) continue;
            AdHttp.put(c, "campaign_name", camp == null ? "" : camp.optString("name"));
            AdHttp.put(c, "site_domain", st == null ? "" : st.optString("domain"));
            rows.add(c);
        }
        rows.sort((a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < rows.size() && i < 1000; i++) out.put(rows.get(i));
        return AdHttp.ok(out);
    }

    static AdHttp.Res review(AdDb db, AdSecurity.User admin, long id, boolean approve) {
        JSONObject c = db.copy(AdDb.CONVERSIONS, id);
        if (c == null) throw AdHttp.notFound("Conversion not found");
        boolean wasValid = "valid".equals(c.optString("status"));
        if (wasValid == approve) return AdHttp.ok(AdHttp.obj("ok", true));
        String status = approve ? "valid" : "invalid";
        String reason = approve ? "" : "rejected_by_admin";
        db.transaction(() -> {
            db.update(AdDb.CONVERSIONS, id, x -> {
                AdHttp.put(x, "status", status);
                AdHttp.put(x, "reason", reason);
            });
            long delta = approve ? 1 : -1;
            long value = approve ? c.optLong("value_micros") : -c.optLong("value_micros");
            db.adjustConversion(c.optLong("day"), c.optString("country"), c.optLong("campaign_id"), c.optLong("creative_id"),
                    c.optLong("placement_id"), c.optLong("site_id"), delta, value);
            db.audit(admin.id, approve ? "conversion.approve" : "conversion.reject", "#" + id);
        });
        return AdHttp.ok(AdHttp.obj("ok", true));
    }

    private static long longParam(String v) {
        return v != null && v.matches("[0-9]{1,15}") ? Long.parseLong(v) : 0;
    }

    private static long dayParam(String v, long def) {
        if (v == null || v.isEmpty()) return def;
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(v).getTime() / 86_400_000L;
        } catch (java.text.ParseException e) {
            throw AdHttp.bad("Dates must look like 2026-10-22");
        }
    }
}
