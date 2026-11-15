package com.yadaventerprise.phoneserver.ads;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Public ad delivery used by the JavaScript SDK on external websites:
 * /sdk.js, /serve (pick an ad), /imp (viewable impression beacon), /c (click redirect), /media/<id>.
 */
final class AdDelivery {
    private static final Pattern KEY = Pattern.compile("^pl_[a-f0-9]{16}$");
    private static final Pattern FILE = Pattern.compile("^[a-f0-9]{24}\\.(png|jpg|gif|webp)$");
    private static final long IMP_TTL_MS = 6L * 3600_000L;
    private static final long CLICK_TTL_MS = 24L * 3600_000L;

    private final Context ctx;
    private final AdDb db;
    private final Map<String, byte[]> assetCache = new HashMap<>();

    AdDelivery(Context ctx, AdDb db) {
        this.ctx = ctx;
        this.db = db;
    }

    static File creativesDir(Context c) {
        File d = new File(c.getFilesDir(), "adnet/creatives");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static AdHttp.Res pub(AdHttp.Res r) {
        r.header("Access-Control-Allow-Origin", "*");
        r.header("Cross-Origin-Resource-Policy", "cross-origin");
        return r;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    synchronized byte[] asset(String name) throws IOException {
        byte[] b = assetCache.get(name);
        if (b == null) {
            try (InputStream in = ctx.getAssets().open("adnet/" + name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                b = out.toByteArray();
            }
            assetCache.put(name, b);
        }
        return b;
    }

    AdHttp.Res sdk() throws IOException {
        return pub(AdHttp.bytes(200, "application/javascript; charset=utf-8", asset("sdk.js"))
                .header("Cache-Control", "public, max-age=3600"));
    }

    AdHttp.Res serve(AdHttp.Req r) {
        if (!AdRateLimiter.allow("serve:" + r.ip, 90, 60_000)) return pub(AdHttp.json(429, AdHttp.obj("error", "Too many requests")));
        String key = r.param("p");
        if (key == null || !KEY.matcher(key).matches()) return pub(AdHttp.json(400, AdHttp.obj("error", "Bad placement")));

        String host = AdSecurity.hostOf(r.header("origin") != null ? r.header("origin") : r.header("referer"));
        if (host == null) return pub(AdHttp.json(403, AdHttp.obj("error", "Missing Origin")));
        JSONObject pl = db.find(AdDb.PLACEMENTS, p -> key.equals(p.optString("key")) && "active".equals(p.optString("status")));
        JSONObject site = pl == null ? null : db.copy(AdDb.SITES, pl.optLong("site_id"));
        if (pl == null || site == null || !"approved".equals(site.optString("status"))) {
            return pub(AdHttp.json(404, AdHttp.obj("error", "Unknown placement")));
        }
        if (!AdSecurity.domainMatches(host, site.optString("domain"))) {
            return pub(AdHttp.json(403, AdHttp.obj("error", "Domain not allowed for this placement")));
        }

        String ua = nz(r.header("user-agent"));
        if (AdFraud.isBot(ua)) return pub(AdHttp.empty(204));
        boolean dnt = "1".equals(r.header("dnt")) || "1".equals(r.header("sec-gpc"));
        String visitor = dnt ? "" : AdSecurity.visitor(db, r.ip, ua);

        String page = nz(r.param("u"));
        int cut = page.length();
        for (char ch : new char[]{'?', '#'}) {
            int i = page.indexOf(ch);
            if (i >= 0 && i < cut) cut = i;
        }
        page = page.substring(0, Math.min(cut, 300)).toLowerCase(Locale.ROOT);
        String lang = nz(r.param("l")).toLowerCase(Locale.ROOT);
        lang = lang.length() >= 2 && Character.isLetter(lang.charAt(0)) && Character.isLetter(lang.charAt(1)) ? lang.substring(0, 2) : "";

        JSONObject ad = select(pl, site, deviceOf(ua), lang, page, visitor);
        if (ad == null) return pub(AdHttp.empty(204));

        long now = System.currentTimeMillis();
        long campaign = ad.optLong("campaign_id"), creative = ad.optLong("cid");
        String country = AdGeo.country(db, r.ip);
        String nonce = AdSecurity.randomToken(12);
        String token = AdSecurity.signToken(db, "v1|" + nonce + "|" + campaign + "|" + creative + "|" + pl.optLong("id")
                + "|" + pl.optLong("site_id") + "|" + now + "|" + visitor);
        db.stat(AdDb.today(), campaign, creative, pl.optLong("id"), pl.optLong("site_id"), 1, 0, 0, 0, 0, 0, 0);
        if (!"ZZ".equals(country)) {
            db.statGeo(AdDb.today(), country, campaign, creative, pl.optLong("id"), pl.optLong("site_id"), 1, 0, 0, 0, 0, 0, 0);
        }

        String img = ad.optString("image_file", "");
        String adv = ad.optString("company", "");
        if (adv.isEmpty()) adv = ad.optString("uname", "");
        JSONObject out = AdHttp.obj("t", token, "f", ad.optString("type"), "w", ad.optLong("width"), "h", ad.optLong("height"),
                "ad", AdHttp.obj("title", ad.optString("title"), "body", ad.optString("body"), "cta", ad.optString("cta"),
                        "adv", adv, "img", img.isEmpty() ? JSONObject.NULL : "/media/" + creative));
        return pub(AdHttp.json(200, out).header("Cache-Control", "no-store"));
    }

    static String deviceOf(String ua) {
        String u = ua.toLowerCase(Locale.ROOT);
        if (u.contains("ipad") || u.contains("tablet") || (u.contains("android") && !u.contains("mobile"))) return "tablet";
        if (u.contains("mobi") || u.contains("iphone") || u.contains("android")) return "mobile";
        return "desktop";
    }

    private static JSONObject experimentFor(long campaignId, List<JSONObject> experiments) {
        for (JSONObject e : experiments) {
            if (e.optLong("campaign_id") == campaignId && "running".equals(e.optString("status"))) return e;
        }
        return null;
    }

    private static JSONObject variant(JSONObject experiment, long creativeId) {
        if (experiment == null) return null;
        JSONArray variants = experiment.optJSONArray("variants");
        if (variants == null) return null;
        for (int i = 0; i < variants.length(); i++) {
            JSONObject v = variants.optJSONObject(i);
            if (v != null && v.optLong("creative_id") == creativeId) return v;
        }
        return null;
    }

    private JSONObject select(JSONObject pl, JSONObject site, String device, String lang, String page, String visitor) {
        long now = System.currentTimeMillis();
        String fmt = pl.optString("format");
        Map<Long, JSONObject> campaigns = new HashMap<>();
        for (JSONObject c : db.list(AdDb.CAMPAIGNS, x -> "approved".equals(x.optString("status")))) campaigns.put(c.optLong("id"), c);
        Map<Long, JSONObject> users = new HashMap<>();
        for (JSONObject u : db.list(AdDb.USERS, x -> "active".equals(x.optString("status")))) users.put(u.optLong("id"), u);
        List<JSONObject> experiments = db.list(AdDb.EXPERIMENTS, x -> "running".equals(x.optString("status")));

        List<JSONObject> candidates = new ArrayList<>();
        for (JSONObject cr : db.list(AdDb.CREATIVES, x -> "approved".equals(x.optString("status")) && fmt.equals(x.optString("type")))) {
            JSONObject c = campaigns.get(cr.optLong("campaign_id"));
            if (c == null) continue;
            JSONObject u = users.get(c.optLong("advertiser_id"));
            if (u == null || u.optLong("balance_micros") <= 0) continue;
            if (c.optLong("spent_micros") >= c.optLong("total_budget_micros")) continue;
            if (!c.isNull("start_at") && c.optLong("start_at") > now) continue;
            if (!c.isNull("end_at") && c.optLong("end_at") <= now) continue;
            if ("banner".equals(fmt) && (cr.optString("image_file").isEmpty()
                    || cr.optLong("width") != pl.optLong("width") || cr.optLong("height") != pl.optLong("height"))) continue;
            JSONObject row = new JSONObject();
            copyFields(row, cr, "id", "cid");
            copyFields(row, cr, "campaign_id", "campaign_id");
            copyFields(row, cr, "type", "type");
            copyFields(row, cr, "title", "title");
            copyFields(row, cr, "body", "body");
            copyFields(row, cr, "cta", "cta");
            copyFields(row, cr, "image_file", "image_file");
            copyFields(row, cr, "width", "width");
            copyFields(row, cr, "height", "height");
            copyFields(row, c, "pricing", "pricing");
            copyFields(row, c, "bid_micros", "bid_micros");
            copyFields(row, c, "daily_budget_micros", "daily_budget_micros");
            copyFields(row, c, "targeting", "targeting");
            AdHttp.put(row, "company", u.optString("company"));
            AdHttp.put(row, "uname", u.optString("name"));
            candidates.add(row);
        }
        if (candidates.isEmpty()) return null;

        long day = AdDb.today();
        Map<Long, Long> spentToday = new HashMap<>();
        Map<Long, long[]> perf = new HashMap<>();
        for (JSONObject s : db.list(AdDb.STATS, x -> x.optLong("day") >= day - 30)) {
            long cid = s.optLong("campaign_id");
            if (s.optLong("day") == day) spentToday.merge(cid, s.optLong("spend_micros"), Long::sum);
            long[] p = perf.get(cid);
            if (p == null) {
                p = new long[2];
                perf.put(cid, p);
            }
            p[0] += s.optLong("imps");
            p[1] += s.optLong("clicks");
        }

        Map<Long, Long> seen = new HashMap<>();
        if (!visitor.isEmpty()) {
            for (JSONObject e : db.list(AdDb.EVENTS, x -> "imp".equals(x.optString("type")) && x.optLong("valid") == 1
                    && visitor.equals(x.optString("visitor")) && x.optLong("ts") >= AdDb.startOfDayMs())) {
                seen.merge(e.optLong("campaign_id"), 1L, Long::sum);
            }
        }

        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int weekday = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1;

        Map<Long, JSONObject> targeting = new HashMap<>();
        Map<Long, Integer> perCampaign = new HashMap<>();
        List<JSONObject> eligible = new ArrayList<>();
        for (JSONObject row : candidates) {
            long cid = row.optLong("campaign_id");
            long daily = row.optLong("daily_budget_micros");
            if (daily > 0 && spentToday.getOrDefault(cid, 0L) >= daily) continue;
            JSONObject exp = experimentFor(cid, experiments);
            JSONObject variant = variant(exp, row.optLong("cid"));
            if (exp != null && variant == null) continue;
            JSONObject t = targeting.get(cid);
            if (t == null) {
                try {
                    t = new JSONObject(row.optString("targeting", "{}"));
                } catch (JSONException e) {
                    t = new JSONObject();
                }
                targeting.put(cid, t);
            }
            if (!matches(t, device, lang, page, hour, weekday, pl.optLong("site_id"))) continue;
            long cap = t.optLong("freq_cap_per_day", 0);
            if (cap > 0 && !visitor.isEmpty() && seen.getOrDefault(cid, 0L) >= cap) continue;
            if (variant != null) AdHttp.put(row, "experiment_weight", Math.max(0, variant.optLong("weight", 50)));
            eligible.add(row);
            perCampaign.merge(cid, 1, Integer::sum);
        }
        if (eligible.isEmpty()) return null;

        double[] w = new double[eligible.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            JSONObject row = eligible.get(i);
            long cid = row.optLong("campaign_id");
            double bid = row.optLong("bid_micros");
            double ecpm = bid;
            if ("CPC".equals(row.optString("pricing"))) {
                long[] p = perf.get(cid);
                double imps = p == null ? 0 : p[0], clicks = p == null ? 0 : p[1];
                ecpm = bid * ((clicks + 2.0) / (imps + 200.0)) * 1000.0;
            }
            double ew = row.has("experiment_weight") ? row.optDouble("experiment_weight", 50) / 50.0 : 1.0;
            w[i] = Math.max(ecpm, 1.0) * Math.max(0.01, ew) / perCampaign.get(cid);
            total += w[i];
        }
        double x = AdSecurity.randomDouble() * total;
        for (int i = 0; i < w.length; i++) {
            x -= w[i];
            if (x <= 0) return eligible.get(i);
        }
        return eligible.get(eligible.size() - 1);
    }

    private static void copyFields(JSONObject target, JSONObject source, String from, String to) {
        AdHttp.put(target, to, source.opt(from));
    }

    private static boolean listHas(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    private static boolean listHas(JSONArray a, long v) {
        for (int i = 0; i < a.length(); i++) if (a.optLong(i, Long.MIN_VALUE) == v) return true;
        return false;
    }

    static boolean matches(JSONObject t, String device, String lang, String page, int hour, int weekday, long siteId) {
        JSONArray a = t.optJSONArray("devices");
        if (a != null && a.length() > 0 && !listHas(a, device)) return false;
        a = t.optJSONArray("languages");
        if (a != null && a.length() > 0 && !listHas(a, lang)) return false;
        a = t.optJSONArray("sites");
        if (a != null && a.length() > 0 && !listHas(a, siteId)) return false;
        a = t.optJSONArray("hours");
        if (a != null && a.length() > 0 && !listHas(a, hour)) return false;
        a = t.optJSONArray("weekdays");
        if (a != null && a.length() > 0 && !listHas(a, weekday)) return false;
        a = t.optJSONArray("url_contains");
        if (a != null && a.length() > 0) {
            boolean any = false;
            for (int i = 0; i < a.length(); i++) if (page.contains(a.optString(i))) any = true;
            if (!any) return false;
        }
        a = t.optJSONArray("url_excludes");
        if (a != null) for (int i = 0; i < a.length(); i++) if (page.contains(a.optString(i))) return false;
        return true;
    }

    private static final class Tok {
        String nonce, visitor;
        long campaign, creative, placement, site, issued;
    }

    private Tok parse(String token, long ttl) {
        String payload = AdSecurity.verifyToken(db, token);
        if (payload == null) {
            db.fraudLog("bad_token");
            return null;
        }
        String[] p = payload.split("\\|", -1);
        try {
            if (p.length != 8 || !p[0].equals("v1")) throw new NumberFormatException();
            Tok t = new Tok();
            t.nonce = p[1];
            t.campaign = Long.parseLong(p[2]);
            t.creative = Long.parseLong(p[3]);
            t.placement = Long.parseLong(p[4]);
            t.site = Long.parseLong(p[5]);
            t.issued = Long.parseLong(p[6]);
            t.visitor = p[7];
            long age = System.currentTimeMillis() - t.issued;
            if (age > ttl || age < -60_000) {
                db.fraudLog("expired_token");
                return null;
            }
            return t;
        } catch (NumberFormatException e) {
            db.fraudLog("bad_token");
            return null;
        }
    }

    private boolean domainOk(AdHttp.Req r, long placementId) {
        String host = AdSecurity.hostOf(r.header("origin") != null ? r.header("origin") : r.header("referer"));
        if (host == null) return true;
        JSONObject pl = db.copy(AdDb.PLACEMENTS, placementId);
        JSONObject s = pl == null ? null : db.copy(AdDb.SITES, pl.optLong("site_id"));
        return s != null && AdSecurity.domainMatches(host, s.optString("domain"));
    }

    private long[] settle(long campaignId, boolean click) {
        JSONObject c = db.copy(AdDb.CAMPAIGNS, campaignId);
        if (c == null) return new long[]{0, 0};
        JSONObject user = db.copy(AdDb.USERS, c.optLong("advertiser_id"));
        if (user == null) return new long[]{0, 0};
        boolean cpc = "CPC".equals(c.optString("pricing"));
        long bid = c.optLong("bid_micros");
        long wanted = click ? (cpc ? bid : 0) : (cpc ? 0 : bid / 1000);
        long cost = Math.max(0, Math.min(wanted, Math.min(c.optLong("total_budget_micros") - c.optLong("spent_micros"),
                user.optLong("balance_micros"))));
        long share = Math.max(0, Math.min(100, db.settingLong("rev_share_pct", 70)));
        long pub = cost * share / 100;
        if (cost > 0) {
            long finalCost = cost;
            db.update(AdDb.CAMPAIGNS, campaignId, row -> {
                long spent = row.optLong("spent_micros") + finalCost;
                AdHttp.put(row, "spent_micros", spent);
                AdHttp.put(row, "updated_at", System.currentTimeMillis());
                if ("approved".equals(row.optString("status")) && spent >= row.optLong("total_budget_micros")) {
                    AdHttp.put(row, "status", "ended");
                }
            });
            db.update(AdDb.USERS, user.optLong("id"), row -> AdHttp.put(row, "balance_micros",
                    row.optLong("balance_micros") - finalCost));
        }
        return new long[]{cost, pub};
    }

    private String record(Tok t, boolean click, String reason, String country) {
        String type = click ? "click" : "imp";
        return db.transaction(() -> {
            long day = AdDb.today();
            JSONObject event = new JSONObject();
            AdHttp.put(event, "ts", System.currentTimeMillis());
            AdHttp.put(event, "type", type);
            AdHttp.put(event, "nonce", t.nonce);
            AdHttp.put(event, "campaign_id", t.campaign);
            AdHttp.put(event, "creative_id", t.creative);
            AdHttp.put(event, "placement_id", t.placement);
            AdHttp.put(event, "site_id", t.site);
            AdHttp.put(event, "visitor", t.visitor);
            AdHttp.put(event, "country", country);
            AdHttp.put(event, "valid", reason == null ? 1 : 0);
            AdHttp.put(event, "reason", reason == null ? "" : reason);
            AdHttp.put(event, "cost_micros", 0);
            AdHttp.put(event, "pub_micros", 0);
            boolean first = db.insertEvent(event);
            if (!first) {
                db.stat(day, t.campaign, t.creative, t.placement, t.site, 0, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, 0);
                if (!country.isEmpty()) db.statGeo(day, country, t.campaign, t.creative, t.placement, t.site, 0, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, 0);
                db.fraudLog("duplicate_" + type);
                return "duplicate";
            }
            if (reason == null) {
                long[] cp = settle(t.campaign, click);
                if (cp[0] > 0) {
                    String nonce = t.nonce;
                    db.updateWhere(AdDb.EVENTS, e -> nonce.equals(e.optString("nonce")) && type.equals(e.optString("type")), e -> {
                        AdHttp.put(e, "cost_micros", cp[0]);
                        AdHttp.put(e, "pub_micros", cp[1]);
                    });
                }
                db.stat(day, t.campaign, t.creative, t.placement, t.site, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, cp[0], cp[1]);
                if (!country.isEmpty()) db.statGeo(day, country, t.campaign, t.creative, t.placement, t.site, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, cp[0], cp[1]);
            } else {
                db.stat(day, t.campaign, t.creative, t.placement, t.site, 0, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, 0);
                if (!country.isEmpty()) db.statGeo(day, country, t.campaign, t.creative, t.placement, t.site, 0, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, 0);
                db.fraudLog(reason);
            }
            return reason;
        });
    }

    AdHttp.Res imp(AdHttp.Req r) {
        if (!"POST".equals(r.method)) return pub(AdHttp.empty(405));
        if (!AdRateLimiter.allow("beacon:" + r.ip, 300, 60_000)) return pub(AdHttp.empty(429));
        Tok t = parse(r.param("t"), IMP_TTL_MS);
        if (t == null) return pub(AdHttp.empty(204));
        String reason = AdFraud.checkImpression(db, nz(r.header("user-agent")), r.ip, t.visitor, t.campaign, domainOk(r, t.placement));
        record(t, false, reason, AdGeo.country(db, r.ip));
        return pub(AdHttp.empty(204));
    }

    AdHttp.Res click(AdHttp.Req r) {
        if (!AdRateLimiter.allow("click-rl:" + r.ip, 60, 60_000)) return AdHttp.text(429, "text/plain; charset=utf-8", "Too many requests");
        Tok t = parse(r.param("t"), CLICK_TTL_MS);
        if (t == null) return AdHttp.text(400, "text/plain; charset=utf-8", "This ad link is invalid or has expired.");
        JSONObject cr = db.copy(AdDb.CREATIVES, t.creative);
        if (cr == null) return AdHttp.text(404, "text/plain; charset=utf-8", "This ad is no longer available.");

        JSONObject imp = db.find(AdDb.EVENTS, e -> t.nonce.equals(e.optString("nonce")) && "imp".equals(e.optString("type")));
        String reason;
        if (imp == null) reason = "no_impression";
        else if (imp.optLong("valid") != 1) reason = "invalid_impression";
        else reason = AdFraud.checkClick(db, nz(r.header("user-agent")), r.ip, t.visitor, t.campaign, domainOk(r, t.placement), imp.optLong("ts"));
        record(t, true, reason, AdGeo.country(db, r.ip));
        return AdHttp.redirect(cr.optString("click_url")).header("Referrer-Policy", "no-referrer");
    }

    static byte[] readFile(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    AdHttp.Res media(AdHttp.Req r) {
        String id = r.path.substring("/media/".length());
        if (!id.matches("^[0-9]{1,12}$")) return AdHttp.empty(404);
        JSONObject cr = db.copy(AdDb.CREATIVES, Long.parseLong(id));
        if (cr == null || !"approved".equals(cr.optString("status")) || cr.optString("image_file").isEmpty()
                || !FILE.matcher(cr.optString("image_file")).matches()) return AdHttp.empty(404);
        File f = new File(creativesDir(ctx), cr.optString("image_file"));
        if (!f.isFile()) return AdHttp.empty(404);
        try {
            return pub(AdHttp.bytes(200, cr.optString("image_mime"), readFile(f))
                    .header("Cache-Control", "public, max-age=86400")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; sandbox"));
        } catch (IOException e) {
            return AdHttp.empty(404);
        }
    }
}
