package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Public ad delivery used by the JavaScript SDK on external websites:
 * /sdk.js, /serve (pick an ad), /imp (viewable impression beacon), /c (click redirect), /media/<id>.
 * Everything that is counted comes from a real request carrying a server-signed token. Nothing is simulated.
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

    /** Public responses may be requested cross-origin from any website that embeds the SDK. */
    private static AdHttp.Res pub(AdHttp.Res r) {
        r.header("Access-Control-Allow-Origin", "*");
        r.header("Cross-Origin-Resource-Policy", "cross-origin");
        return r;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static long val(Map<Long, Long> m, long k) {
        Long v = m.get(k);
        return v == null ? 0 : v;
    }

    // ---------------------------------------------------------------- SDK + assets

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

    // ---------------------------------------------------------------- serve

    AdHttp.Res serve(AdHttp.Req r) {
        if (!AdRateLimiter.allow("serve:" + r.ip, 90, 60_000)) return pub(AdHttp.json(429, AdHttp.obj("error", "Too many requests")));
        String key = r.param("p");
        if (key == null || !KEY.matcher(key).matches()) return pub(AdHttp.json(400, AdHttp.obj("error", "Bad placement")));

        // The page asking for ads must belong to the site registered for this placement, so tags cannot be reused elsewhere.
        String host = AdSecurity.hostOf(r.header("origin") != null ? r.header("origin") : r.header("referer"));
        if (host == null) return pub(AdHttp.json(403, AdHttp.obj("error", "Missing Origin")));
        JSONObject pl = db.one("SELECT p.id, p.format, p.width, p.height, p.site_id, s.domain FROM placements p"
                + " JOIN sites s ON s.id=p.site_id WHERE p.key=? AND p.status='active' AND s.status='approved'", key);
        if (pl == null) return pub(AdHttp.json(404, AdHttp.obj("error", "Unknown placement")));
        if (!AdSecurity.domainMatches(host, pl.optString("domain"))) {
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

        JSONObject ad = select(pl, deviceOf(ua), lang, page, visitor);
        if (ad == null) return pub(AdHttp.empty(204)); // no fill - never substitute fake ads

        long now = System.currentTimeMillis();
        long campaign = ad.optLong("campaign_id"), creative = ad.optLong("cid");
        String nonce = AdSecurity.randomToken(12);
        String token = AdSecurity.signToken(db, "v1|" + nonce + "|" + campaign + "|" + creative + "|" + pl.optLong("id")
                + "|" + pl.optLong("site_id") + "|" + now + "|" + visitor);
        db.stat(db.getWritableDatabase(), AdDb.today(), campaign, creative, pl.optLong("id"), pl.optLong("site_id"), 1, 0, 0, 0, 0, 0, 0);

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

    /** Picks one approved creative: filter by schedule, budget, balance and targeting, then weighted-random rotation by value. */
    private JSONObject select(JSONObject pl, String device, String lang, String page, String visitor) {
        long now = System.currentTimeMillis();
        String fmt = pl.optString("format");
        List<Object> args = new ArrayList<>();
        args.add(fmt);
        args.add(now);
        args.add(now);
        StringBuilder sql = new StringBuilder("SELECT cr.id cid, cr.campaign_id, cr.type, cr.title, cr.body, cr.cta, cr.image_file,"
                + " cr.width, cr.height, c.pricing, c.bid_micros, c.daily_budget_micros, c.targeting, u.company, u.name uname"
                + " FROM creatives cr JOIN campaigns c ON c.id=cr.campaign_id JOIN users u ON u.id=c.advertiser_id"
                + " WHERE cr.status='approved' AND cr.type=? AND c.status='approved' AND u.status='active' AND u.balance_micros>0"
                + " AND c.spent_micros<c.total_budget_micros AND (c.start_at IS NULL OR c.start_at<=?) AND (c.end_at IS NULL OR c.end_at>?)");
        if ("banner".equals(fmt)) {
            sql.append(" AND cr.image_file<>'' AND cr.width=? AND cr.height=?");
            args.add(pl.optLong("width"));
            args.add(pl.optLong("height"));
        }
        JSONArray rows = db.query(sql.toString(), args.toArray());
        if (rows.length() == 0) return null;

        long day = AdDb.today();
        Map<Long, Long> spentToday = new HashMap<>();
        JSONArray s1 = db.query("SELECT campaign_id, SUM(spend_micros) s FROM stats_daily WHERE day=? GROUP BY campaign_id", day);
        for (int i = 0; i < s1.length(); i++) spentToday.put(s1.optJSONObject(i).optLong("campaign_id"), s1.optJSONObject(i).optLong("s"));

        Map<Long, long[]> perf = new HashMap<>();
        JSONArray s2 = db.query("SELECT campaign_id, SUM(imps) i, SUM(clicks) c FROM stats_daily WHERE day>=? GROUP BY campaign_id", day - 30);
        for (int i = 0; i < s2.length(); i++) {
            JSONObject o = s2.optJSONObject(i);
            perf.put(o.optLong("campaign_id"), new long[]{o.optLong("i"), o.optLong("c")});
        }

        Map<Long, Long> seen = new HashMap<>();
        if (!visitor.isEmpty()) {
            JSONArray s3 = db.query("SELECT campaign_id, COUNT(*) n FROM events WHERE type='imp' AND valid=1 AND visitor=? AND ts>=?"
                    + " GROUP BY campaign_id", visitor, AdDb.startOfDayMs());
            for (int i = 0; i < s3.length(); i++) seen.put(s3.optJSONObject(i).optLong("campaign_id"), s3.optJSONObject(i).optLong("n"));
        }

        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int weekday = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1; // Monday = 1 ... Sunday = 7

        Map<Long, JSONObject> targeting = new HashMap<>();
        Map<Long, Integer> perCampaign = new HashMap<>();
        List<JSONObject> eligible = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            long cid = r.optLong("campaign_id");
            long daily = r.optLong("daily_budget_micros");
            if (daily > 0 && val(spentToday, cid) >= daily) continue;
            JSONObject t = targeting.get(cid);
            if (t == null) {
                try {
                    t = new JSONObject(r.optString("targeting", "{}"));
                } catch (JSONException e) {
                    t = new JSONObject();
                }
                targeting.put(cid, t);
            }
            if (!matches(t, device, lang, page, hour, weekday, pl.optLong("site_id"))) continue;
            long cap = t.optLong("freq_cap_per_day", 0);
            if (cap > 0 && !visitor.isEmpty() && val(seen, cid) >= cap) continue;
            eligible.add(r);
            Integer n = perCampaign.get(cid);
            perCampaign.put(cid, n == null ? 1 : n + 1);
        }
        if (eligible.isEmpty()) return null;

        double[] w = new double[eligible.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            JSONObject r = eligible.get(i);
            long cid = r.optLong("campaign_id");
            double bid = r.optLong("bid_micros");
            double ecpm = bid; // CPM bids are already "per 1000 impressions"
            if ("CPC".equals(r.optString("pricing"))) {
                long[] p = perf.get(cid);
                double imps = p == null ? 0 : p[0], clicks = p == null ? 0 : p[1];
                ecpm = bid * ((clicks + 2.0) / (imps + 200.0)) * 1000.0; // expected value per 1000 impressions, smoothed
            }
            w[i] = Math.max(ecpm, 1.0) / perCampaign.get(cid); // campaigns with more creatives do not get extra weight
            total += w[i];
        }
        double x = AdSecurity.randomDouble() * total;
        for (int i = 0; i < w.length; i++) {
            x -= w[i];
            if (x <= 0) return eligible.get(i);
        }
        return eligible.get(eligible.size() - 1);
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

    // ---------------------------------------------------------------- tracking

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

    /** If the browser tells us which page it is on, that page must belong to the site that owns the placement. */
    private boolean domainOk(AdHttp.Req r, long placementId) {
        String host = AdSecurity.hostOf(r.header("origin") != null ? r.header("origin") : r.header("referer"));
        if (host == null) return true; // header stripped by the browser: cannot judge, do not penalise
        JSONObject s = db.one("SELECT s.domain FROM placements p JOIN sites s ON s.id=p.site_id WHERE p.id=?", placementId);
        return s != null && AdSecurity.domainMatches(host, s.optString("domain"));
    }

    /** Charges for one valid event inside the caller's transaction. Returns {advertiserCost, publisherEarning}. */
    private long[] settle(SQLiteDatabase w, long campaignId, boolean click) {
        JSONObject c = db.one("SELECT c.pricing, c.bid_micros, c.total_budget_micros, c.spent_micros, u.balance_micros, u.id uid"
                + " FROM campaigns c JOIN users u ON u.id=c.advertiser_id WHERE c.id=?", campaignId);
        if (c == null) return new long[]{0, 0};
        boolean cpc = "CPC".equals(c.optString("pricing"));
        long bid = c.optLong("bid_micros");
        long wanted = click ? (cpc ? bid : 0) : (cpc ? 0 : bid / 1000); // CPM: bid is per 1000 impressions
        long cost = Math.max(0, Math.min(wanted, Math.min(c.optLong("total_budget_micros") - c.optLong("spent_micros"), c.optLong("balance_micros"))));
        long share = Math.max(0, Math.min(100, db.settingLong("rev_share_pct", 70)));
        long pub = cost * share / 100;
        if (cost > 0) {
            w.execSQL("UPDATE campaigns SET spent_micros=spent_micros+?, updated_at=? WHERE id=?", new Object[]{cost, System.currentTimeMillis(), campaignId});
            w.execSQL("UPDATE users SET balance_micros=balance_micros-? WHERE id=?", new Object[]{cost, c.optLong("uid")});
            w.execSQL("UPDATE campaigns SET status='ended' WHERE id=? AND status='approved' AND spent_micros>=total_budget_micros", new Object[]{campaignId});
        }
        return new long[]{cost, pub};
    }

    /** Records one tracked event (impression or click) exactly once. Returns the reason it was invalid, or null if billed. */
    private String record(Tok t, boolean click, String reason) {
        SQLiteDatabase w = db.getWritableDatabase();
        String type = click ? "click" : "imp";
        w.beginTransaction();
        try {
            long day = AdDb.today();
            try {
                w.execSQL("INSERT INTO events(ts,type,nonce,campaign_id,creative_id,placement_id,site_id,visitor,valid,reason,cost_micros,pub_micros)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,0,0)",
                        new Object[]{System.currentTimeMillis(), type, t.nonce, t.campaign, t.creative, t.placement, t.site,
                                t.visitor, reason == null ? 1 : 0, reason == null ? "" : reason});
            } catch (SQLiteConstraintException duplicate) {
                // The same token was replayed. Nothing is charged; it is counted as invalid traffic.
                db.stat(w, day, t.campaign, t.creative, t.placement, t.site, 0, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, 0);
                db.fraudLog("duplicate_" + type);
                w.setTransactionSuccessful();
                return "duplicate";
            }
            if (reason == null) {
                long[] cp = settle(w, t.campaign, click);
                if (cp[0] > 0) {
                    w.execSQL("UPDATE events SET cost_micros=?, pub_micros=? WHERE nonce=? AND type=?", new Object[]{cp[0], cp[1], t.nonce, type});
                }
                db.stat(w, day, t.campaign, t.creative, t.placement, t.site, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, cp[0], cp[1]);
            } else {
                db.stat(w, day, t.campaign, t.creative, t.placement, t.site, 0, 0, click ? 0 : 1, 0, click ? 1 : 0, 0, 0);
                db.fraudLog(reason);
            }
            w.setTransactionSuccessful();
            return reason;
        } finally {
            w.endTransaction();
        }
    }

    /** POST /imp?t=TOKEN - sent by the SDK once the ad has been at least 50% visible for one second. */
    AdHttp.Res imp(AdHttp.Req r) {
        if (!"POST".equals(r.method)) return pub(AdHttp.empty(405));
        if (!AdRateLimiter.allow("beacon:" + r.ip, 300, 60_000)) return pub(AdHttp.empty(429));
        Tok t = parse(r.param("t"), IMP_TTL_MS);
        if (t == null) return pub(AdHttp.empty(204));
        String reason = AdFraud.checkImpression(db, nz(r.header("user-agent")), r.ip, t.visitor, t.campaign, domainOk(r, t.placement));
        record(t, false, reason);
        return pub(AdHttp.empty(204));
    }

    /** GET /c?t=TOKEN - counts the click (if genuine) and always sends the visitor on to the advertiser's page. */
    AdHttp.Res click(AdHttp.Req r) {
        if (!AdRateLimiter.allow("click-rl:" + r.ip, 60, 60_000)) return AdHttp.text(429, "text/plain; charset=utf-8", "Too many requests");
        Tok t = parse(r.param("t"), CLICK_TTL_MS);
        if (t == null) return AdHttp.text(400, "text/plain; charset=utf-8", "This ad link is invalid or has expired.");
        JSONObject cr = db.one("SELECT click_url FROM creatives WHERE id=?", t.creative);
        if (cr == null) return AdHttp.text(404, "text/plain; charset=utf-8", "This ad is no longer available.");

        JSONObject imp = db.one("SELECT ts, valid FROM events WHERE nonce=? AND type='imp'", t.nonce);
        String reason;
        if (imp == null) reason = "no_impression";
        else if (imp.optLong("valid") != 1) reason = "invalid_impression";
        else reason = AdFraud.checkClick(db, nz(r.header("user-agent")), r.ip, t.visitor, t.campaign, domainOk(r, t.placement), imp.optLong("ts"));
        record(t, true, reason);
        return AdHttp.redirect(cr.optString("click_url")).header("Referrer-Policy", "no-referrer");
    }

    // ---------------------------------------------------------------- media

    static byte[] readFile(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Serves an approved creative's image. Files are looked up by id, never by a client-supplied path. */
    AdHttp.Res media(AdHttp.Req r) {
        String id = r.path.substring("/media/".length());
        if (!id.matches("^[0-9]{1,12}$")) return AdHttp.empty(404);
        JSONObject cr = db.one("SELECT image_file, image_mime FROM creatives WHERE id=? AND status='approved' AND image_file<>''", id);
        if (cr == null || !FILE.matcher(cr.optString("image_file")).matches()) return AdHttp.empty(404);
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
