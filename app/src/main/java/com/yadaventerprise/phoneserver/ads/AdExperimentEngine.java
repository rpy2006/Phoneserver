package com.yadaventerprise.phoneserver.ads;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Creative A/B experiments. Delivery only uses approved creatives that belong to a running experiment. */
final class AdExperimentEngine {
    private AdExperimentEngine() {}

    private static JSONObject campaignFor(AdSecurity.User u, AdDb db, long campaignId) {
        JSONObject c = db.copy(AdDb.CAMPAIGNS, campaignId);
        if (c == null || (!u.isAdmin() && c.optLong("advertiser_id") != u.id)) throw AdHttp.notFound("Campaign not found");
        return c;
    }

    private static JSONObject experimentFor(AdSecurity.User u, AdDb db, long id) {
        JSONObject e = db.copy(AdDb.EXPERIMENTS, id);
        if (e == null) throw AdHttp.notFound("Experiment not found");
        campaignFor(u, db, e.optLong("campaign_id"));
        return e;
    }

    private static void advertiserOnly(AdSecurity.User u) {
        if (!u.isAdmin() && !"advertiser".equals(u.role)) throw AdHttp.forbidden("Only advertisers can manage experiments");
    }

    private static JSONArray variants(JSONObject body, AdDb db, long campaignId) {
        JSONArray in = body.optJSONArray("variants");
        if (in == null || in.length() < 2 || in.length() > 5) throw AdHttp.bad("An experiment needs 2-5 variants");
        JSONArray out = new JSONArray();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < in.length(); i++) {
            JSONObject v = in.optJSONObject(i);
            if (v == null) throw AdHttp.bad("variants must be objects");
            long creativeId = v.optLong("creative_id", 0);
            JSONObject creative = db.copy(AdDb.CREATIVES, creativeId);
            if (creative == null || creative.optLong("campaign_id") != campaignId) throw AdHttp.bad("Every variant must use a creative from this campaign");
            if (!seen.add(creativeId)) throw AdHttp.bad("Each creative can only appear once");
            long weight = v.optLong("weight", 50);
            if (weight < 1 || weight > 100) throw AdHttp.bad("Variant weights must be 1-100");
            out.put(AdHttp.obj("creative_id", creativeId, "weight", weight,
                    "label", AdSecurity.clean(v.optString("label", ""))));
        }
        return out;
    }

    private static long endOfDay(JSONObject b, String key) {
        if (!b.has(key) || b.isNull(key) || b.optString(key).isEmpty()) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            return f.parse(b.optString(key)).getTime() + 86_400_000L;
        } catch (ParseException e) {
            throw AdHttp.bad(key + " must be a date like 2026-12-31");
        }
    }

    static AdHttp.Res list(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        advertiserOnly(u);
        long campaign = num(r.param("campaign_id"));
        Map<Long, JSONObject> campaigns = new HashMap<>();
        for (JSONObject c : db.list(AdDb.CAMPAIGNS, x -> u.isAdmin() || x.optLong("advertiser_id") == u.id)) {
            campaigns.put(c.optLong("id"), c);
        }
        List<JSONObject> rows = db.list(AdDb.EXPERIMENTS, x -> campaigns.containsKey(x.optLong("campaign_id"))
                && (campaign == 0 || x.optLong("campaign_id") == campaign));
        rows.sort((a, b) -> Long.compare(b.optLong("id"), a.optLong("id")));
        JSONArray out = new JSONArray();
        for (JSONObject e : rows) {
            JSONObject c = campaigns.get(e.optLong("campaign_id"));
            AdHttp.put(e, "campaign_name", c == null ? "" : c.optString("name"));
            AdHttp.put(e, "metrics", metrics(db, e));
            out.put(e);
        }
        return AdHttp.ok(out);
    }

    static AdHttp.Res create(AdHttp.Req r, AdSecurity.User u, AdDb db) {
        advertiserOnly(u);
        JSONObject b = AdHttp.body(r);
        long campaignId = AdHttp.num(b, "campaign_id", 1, Long.MAX_VALUE, 0);
        campaignFor(u, db, campaignId);
        if (db.count(AdDb.EXPERIMENTS, x -> x.optLong("campaign_id") == campaignId
                && !"completed".equals(x.optString("status"))) > 0) {
            throw new AdHttp.HttpError(409, "This campaign already has an active experiment");
        }
        String name = AdHttp.str(b, "name", 1, 100);
        String metric = b.optString("metric", "ctr");
        if (!metric.matches("ctr|clicks|conversions")) throw AdHttp.bad("metric must be ctr, clicks or conversions");
        long ends = endOfDay(b, "end");
        long now = System.currentTimeMillis();
        JSONObject row = AdHttp.obj("campaign_id", campaignId, "name", name, "status", "draft", "metric", metric,
                "variants", variants(b, db, campaignId), "winner_creative_id", 0L, "started_at", 0L, "ended_at", 0L,
                "ends_at", ends, "created_by", u.id, "created_at", now, "updated_at", now);
        long id = db.insert(AdDb.EXPERIMENTS, row);
        db.audit(u.id, "experiment.create", "#" + id + " campaign #" + campaignId);
        return AdHttp.ok(AdHttp.obj("id", id));
    }

    static AdHttp.Res patch(AdHttp.Req r, AdSecurity.User u, AdDb db, long id) {
        advertiserOnly(u);
        JSONObject e = experimentFor(u, db, id);
        if ("running".equals(e.optString("status"))) throw new AdHttp.HttpError(409, "Pause the experiment before editing it");
        if ("completed".equals(e.optString("status"))) throw new AdHttp.HttpError(409, "Completed experiments cannot be edited");
        JSONObject b = AdHttp.body(r);
        JSONObject patch = new JSONObject();
        if (b.has("name")) AdHttp.put(patch, "name", AdHttp.str(b, "name", 1, 100));
        if (b.has("metric")) {
            String m = b.optString("metric");
            if (!m.matches("ctr|clicks|conversions")) throw AdHttp.bad("metric must be ctr, clicks or conversions");
            AdHttp.put(patch, "metric", m);
        }
        if (b.has("variants")) AdHttp.put(patch, "variants", variants(b, db, e.optLong("campaign_id")));
        if (b.has("end")) AdHttp.put(patch, "ends_at", endOfDay(b, "end"));
        AdHttp.put(patch, "updated_at", System.currentTimeMillis());
        db.update(AdDb.EXPERIMENTS, id, patch);
        db.audit(u.id, "experiment.update", "#" + id);
        return AdHttp.ok(AdHttp.obj("ok", true));
    }

    static AdHttp.Res status(AdSecurity.User u, AdDb db, long id, String wanted) {
        advertiserOnly(u);
        JSONObject e = experimentFor(u, db, id);
        String current = e.optString("status");
        boolean allowed = ("running".equals(wanted) && ("draft".equals(current) || "paused".equals(current)))
                || ("paused".equals(wanted) && "running".equals(current))
                || ("completed".equals(wanted) && ("running".equals(current) || "paused".equals(current)));
        if (!allowed) throw new AdHttp.HttpError(409, "Cannot move this experiment from " + current + " to " + wanted);
        long now = System.currentTimeMillis();
        db.update(AdDb.EXPERIMENTS, id, x -> {
            AdHttp.put(x, "status", wanted);
            if ("running".equals(wanted) && x.optLong("started_at") == 0) AdHttp.put(x, "started_at", now);
            if ("completed".equals(wanted)) AdHttp.put(x, "ended_at", now);
            AdHttp.put(x, "updated_at", now);
        });
        db.audit(u.id, "experiment." + wanted, "#" + id);
        return AdHttp.ok(AdHttp.obj("ok", true));
    }

    static AdHttp.Res chooseWinner(AdHttp.Req r, AdSecurity.User u, AdDb db, long id) {
        advertiserOnly(u);
        JSONObject e = experimentFor(u, db, id);
        JSONObject b = AdHttp.body(r);
        JSONObject metrics = metrics(db, e);
        JSONArray variants = metrics.optJSONArray("variants");
        long creativeId = b.optLong("creative_id", 0);
        if (creativeId == 0) {
            double best = -1;
            for (int i = 0; variants != null && i < variants.length(); i++) {
                JSONObject v = variants.optJSONObject(i);
                double score = score(e.optString("metric", "ctr"), v);
                if (score > best) {
                    best = score;
                    creativeId = v.optLong("creative_id");
                }
            }
            if (creativeId == 0 || best < 0) throw AdHttp.bad("No variant has enough data to choose a winner");
        } else {
            boolean found = false;
            for (int i = 0; variants != null && i < variants.length(); i++) {
                if (variants.optJSONObject(i).optLong("creative_id") == creativeId) found = true;
            }
            if (!found) throw AdHttp.bad("creative_id is not part of this experiment");
        }
        long winner = creativeId;
        db.transaction(() -> {
            db.update(AdDb.EXPERIMENTS, id, x -> {
                AdHttp.put(x, "winner_creative_id", winner);
                AdHttp.put(x, "status", "completed");
                if (x.optLong("ended_at") == 0) AdHttp.put(x, "ended_at", System.currentTimeMillis());
                AdHttp.put(x, "updated_at", System.currentTimeMillis());
            });
            db.audit(u.id, "experiment.winner", "#" + id + " creative #" + winner);
        });
        return AdHttp.ok(AdHttp.obj("winner_creative_id", winner, "metrics", metrics(db, db.copy(AdDb.EXPERIMENTS, id))));
    }

    static void finishExpired(AdDb db) {
        long now = System.currentTimeMillis();
        for (JSONObject e : db.list(AdDb.EXPERIMENTS, x -> ("running".equals(x.optString("status"))
                || "paused".equals(x.optString("status"))) && x.optLong("ends_at") > 0 && x.optLong("ends_at") <= now)) {
            db.update(AdDb.EXPERIMENTS, e.optLong("id"), x -> {
                AdHttp.put(x, "status", "completed");
                AdHttp.put(x, "ended_at", now);
                AdHttp.put(x, "updated_at", now);
            });
        }
    }

    private static double score(String metric, JSONObject v) {
        long imps = v.optLong("imps");
        if (imps == 0) return -1;
        switch (metric) {
            case "clicks": return v.optLong("clicks");
            case "conversions": return v.optLong("conversions");
            default: return v.optLong("clicks") * 100.0 / imps;
        }
    }

    private static JSONObject metrics(AdDb db, JSONObject experiment) {
        long campaignId = experiment.optLong("campaign_id");
        long from = experiment.optLong("started_at") == 0 ? AdDb.today()
                : experiment.optLong("started_at") / 86_400_000L;
        JSONArray out = new JSONArray();
        JSONArray variants = experiment.optJSONArray("variants");
        for (int i = 0; variants != null && i < variants.length(); i++) {
            JSONObject v = variants.optJSONObject(i);
            long creativeId = v.optLong("creative_id");
            long imps = 0, clicks = 0, conversions = 0, spend = 0;
            for (JSONObject s : db.list(AdDb.STATS, x -> x.optLong("campaign_id") == campaignId
                    && x.optLong("creative_id") == creativeId && x.optLong("day") >= from)) {
                imps += s.optLong("imps");
                clicks += s.optLong("clicks");
                conversions += s.optLong("conversions");
                spend += s.optLong("spend_micros");
            }
            JSONObject creative = db.copy(AdDb.CREATIVES, creativeId);
            String label = v.optString("label");
            if (label.isEmpty()) label = creative == null ? "#" + creativeId : creative.optString("title");
            out.put(AdHttp.obj("creative_id", creativeId, "label", label, "weight", v.optLong("weight", 50),
                    "imps", imps, "clicks", clicks, "conversions", conversions, "spend_micros", spend,
                    "ctr_percent", imps == 0 ? 0.0 : Math.round(clicks * 10000.0 / imps) / 100.0));
        }
        return AdHttp.obj("from_day", AdReports.dayString(from), "variants", out,
                "winner_creative_id", experiment.optLong("winner_creative_id"));
    }

    private static long num(String v) {
        return v != null && v.matches("[0-9]{1,15}") ? Long.parseLong(v) : 0;
    }
}
