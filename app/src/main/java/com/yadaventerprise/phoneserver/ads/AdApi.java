package com.yadaventerprise.phoneserver.ads;

import static com.yadaventerprise.phoneserver.ads.AdHttp.bad;
import static com.yadaventerprise.phoneserver.ads.AdHttp.forbidden;
import static com.yadaventerprise.phoneserver.ads.AdHttp.notFound;
import static com.yadaventerprise.phoneserver.ads.AdHttp.obj;

import android.content.Context;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Authenticated REST API (JSON). Roles: admin, advertiser, publisher. Unknown or foreign ids always answer 404. */
final class AdApi {
    private final Context ctx;
    private final AdDb db;
    private final AdReports reports;

    AdApi(Context ctx, AdDb db) {
        this.ctx = ctx;
        this.db = db;
        this.reports = new AdReports(db);
    }

    AdHttp.Res handle(AdHttp.Req r) throws IOException {
        switch (r.method + " " + r.path) {
            case "GET /api/setup/status":
                return AdHttp.ok(obj("needs_setup", db.count(AdDb.USERS) == 0));
            case "POST /api/setup": return setup(r);
            case "POST /api/auth/login": return login(r);
            case "POST /api/auth/signup": return signup(r);
            default: break;
        }

        AdSecurity.User u = AdSecurity.authenticate(db, r);
        if (u == null) return AdHttp.json(401, obj("error", "Not signed in"));
        r.user = u;
        if (!AdRateLimiter.allow("user:" + u.id, 300, 60_000)) return AdHttp.json(429, obj("error", "Too many requests"));

        List<Long> ids = new ArrayList<>();
        StringBuilder key = new StringBuilder(r.method).append(' ');
        for (String seg : r.path.split("/")) {
            if (seg.isEmpty()) continue;
            if (seg.matches("[0-9]{1,15}")) {
                ids.add(Long.parseLong(seg));
                key.append("/:id");
            } else {
                key.append('/').append(seg);
            }
        }

        switch (key.toString()) {
            case "GET /api/meta": return meta(u);
            case "GET /api/auth/me": return AdHttp.ok(userJson(u));
            case "POST /api/auth/logout":
                db.deleteWhere(AdDb.SESSIONS, s -> AdSecurity.sha256Hex(r.token).equals(s.optString("token_hash")));
                return AdHttp.ok(obj("ok", true));
            case "POST /api/auth/password": return changePassword(r, u);

            case "GET /api/users": needAdmin(u); return listUsers(r);
            case "POST /api/users": needAdmin(u); return createUser(r, u);
            case "PATCH /api/users/:id": needAdmin(u); return patchUser(r, u, ids.get(0));
            case "DELETE /api/users/:id": needAdmin(u); return deleteUser(u, ids.get(0));
            case "POST /api/users/:id/credit": needAdmin(u); return credit(r, u, ids.get(0));
            case "POST /api/users/:id/payout": needAdmin(u); return payout(r, u, ids.get(0));

            case "GET /api/sites": return listSites(u);
            case "POST /api/sites": return createSite(r, u);
            case "PATCH /api/sites/:id": return patchSite(r, u, ids.get(0));
            case "DELETE /api/sites/:id": return deleteSite(u, ids.get(0));

            case "GET /api/placements": return listPlacements(r, u);
            case "POST /api/placements": return createPlacement(r, u);
            case "PATCH /api/placements/:id": return patchPlacement(r, u, ids.get(0));
            case "DELETE /api/placements/:id": return deletePlacement(u, ids.get(0));
            case "GET /api/placements/:id/tag": return tag(r, u, ids.get(0));

            case "GET /api/campaigns": return listCampaigns(u);
            case "POST /api/campaigns": return createCampaign(r, u);
            case "GET /api/campaigns/:id": return AdHttp.ok(campaignJson(u, ids.get(0)));
            case "PATCH /api/campaigns/:id": return patchCampaign(r, u, ids.get(0));
            case "POST /api/campaigns/:id/submit": return submitCampaign(u, ids.get(0));
            case "POST /api/campaigns/:id/approve": needAdmin(u); return approveCampaign(r, u, ids.get(0));
            case "POST /api/campaigns/:id/reject": needAdmin(u); return rejectCampaign(r, u, ids.get(0));
            case "POST /api/campaigns/:id/pause": return setCampaignStatus(u, ids.get(0), "approved", "paused");
            case "POST /api/campaigns/:id/resume": return setCampaignStatus(u, ids.get(0), "paused", "approved");

            case "GET /api/creatives": return listCreatives(r, u);
            case "POST /api/creatives": return createCreative(r, u);
            case "PATCH /api/creatives/:id": return patchCreative(r, u, ids.get(0));
            case "DELETE /api/creatives/:id": return deleteCreative(u, ids.get(0));
            case "POST /api/creatives/:id/image": return uploadImage(r, u, ids.get(0));
            case "GET /api/creatives/:id/image": return getImage(u, ids.get(0));
            case "POST /api/creatives/:id/approve": needAdmin(u); return reviewCreative(r, u, ids.get(0), true);
            case "POST /api/creatives/:id/reject": needAdmin(u); return reviewCreative(r, u, ids.get(0), false);

            case "GET /api/reports/summary": return reports.summary(r, u);
            case "GET /api/reports/breakdown": return reports.breakdown(r, u);
            case "GET /api/reports/export": return reports.export(r, u);
            case "GET /api/reports/geo": return AdGeoReport.breakdown(r, u, db);
            case "GET /api/reports/geo/export": return AdGeoReport.export(r, u, db);
            case "GET /api/billing": return reports.billing(r, u);

            case "GET /api/conversions": return AdConversion.list(r, u, db);
            case "GET /api/conversions/postback-key": return AdConversion.key(u, db);
            case "POST /api/conversions/:id/approve": needAdmin(u); return AdConversion.review(db, u, ids.get(0), true);
            case "POST /api/conversions/:id/reject": needAdmin(u); return AdConversion.review(db, u, ids.get(0), false);

            case "GET /api/experiments": return AdExperimentEngine.list(r, u, db);
            case "POST /api/experiments": return AdExperimentEngine.create(r, u, db);
            case "PATCH /api/experiments/:id": return AdExperimentEngine.patch(r, u, db, ids.get(0));
            case "POST /api/experiments/:id/start": return AdExperimentEngine.status(u, db, ids.get(0), "running");
            case "POST /api/experiments/:id/pause": return AdExperimentEngine.status(u, db, ids.get(0), "paused");
            case "POST /api/experiments/:id/finish": return AdExperimentEngine.status(u, db, ids.get(0), "completed");
            case "POST /api/experiments/:id/winner": return AdExperimentEngine.chooseWinner(r, u, db, ids.get(0));

            case "GET /api/payouts": return AdPayouts.list(r, u, db);
            case "POST /api/payouts": return AdPayouts.create(r, u, db);
            case "GET /api/payouts/:id/export": return AdPayouts.export(r, u, db, ids.get(0));
            case "GET /api/payouts/export": return AdPayouts.exportRange(r, u, db);

            case "GET /api/admin/overview": needAdmin(u); return overview();
            case "GET /api/admin/fraud": needAdmin(u); return fraud();
            case "GET /api/admin/settings": needAdmin(u); return AdHttp.ok(settingsJson());
            case "PUT /api/admin/settings": needAdmin(u); return putSettings(r, u);
            case "GET /api/admin/geo": needAdmin(u); return AdHttp.ok(AdGeo.status(db));
            case "POST /api/admin/geo": needAdmin(u); return importGeo(r, u);
            case "POST /api/admin/summary/send": needAdmin(u); return AdSummaryMailer.sendNow(r, u, db);
            case "GET /api/admin/summary": needAdmin(u); return AdHttp.ok(AdSummaryMailer.history(db));
            default: throw notFound("Unknown endpoint");
        }
    }

    private static void needAdmin(AdSecurity.User u) {
        if (!u.isAdmin()) throw forbidden("Administrator access required");
    }

    private static long idParam(AdHttp.Req r, String name) {
        String v = r.param(name);
        if (v == null || v.isEmpty()) return 0;
        if (!v.matches("[0-9]{1,15}")) throw bad(name + " must be a number");
        return Long.parseLong(v);
    }

    private AdHttp.Res setup(AdHttp.Req r) {
        if (!AdRateLimiter.allow("setup:" + r.ip, 10, 3600_000)) return AdHttp.json(429, obj("error", "Too many attempts"));
        if (db.count(AdDb.USERS) > 0) return AdHttp.json(409, obj("error", "Already set up"));
        JSONObject b = AdHttp.body(r);
        String code = AdHttp.str(b, "setup_code", 1, 32).toUpperCase(Locale.ROOT);
        if (!AdSecurity.constantEquals(code, AdNetworkManager.setupCode(ctx))) throw forbidden("Wrong setup code (shown on the phone, in Ad Network settings)");
        long id = insertUser(b, "admin");
        AdNetworkManager.clearSetupCode(ctx);
        db.audit(id, "setup", "first administrator created");
        return loginResponse(id);
    }

    private AdHttp.Res login(AdHttp.Req r) {
        JSONObject b = AdHttp.body(r);
        String email = AdHttp.str(b, "email", 3, 254).toLowerCase(Locale.ROOT);
        String pw = b.optString("password", "");
        if (!AdRateLimiter.allow("login:" + r.ip + ":" + email, 8, 15 * 60_000) || !AdRateLimiter.allow("login-ip:" + r.ip, 40, 15 * 60_000)) {
            return AdHttp.json(429, obj("error", "Too many sign-in attempts. Try again in a few minutes."));
        }
        JSONObject row = db.find(AdDb.USERS, u -> email.equals(u.optString("email")));
        if (row == null) {
            AdSecurity.fakeVerify(pw);
            return AdHttp.json(401, obj("error", "Wrong email or password"));
        }
        if (!AdSecurity.verifyPassword(pw, row.optString("pass_hash")) || !"active".equals(row.optString("status"))) {
            return AdHttp.json(401, obj("error", "Wrong email or password"));
        }
        return loginResponse(row.optLong("id"));
    }

    private AdHttp.Res loginResponse(long userId) {
        JSONObject row = db.copy(AdDb.USERS, userId);
        return AdHttp.ok(obj("token", AdSecurity.createSession(db, userId), "user", userJson(new AdSecurity.User(row))));
    }

    private AdHttp.Res signup(AdHttp.Req r) {
        if (!"1".equals(db.setting("allow_signup", "0"))) throw forbidden("Sign-up is disabled. Ask the administrator for an account.");
        if (!AdRateLimiter.allow("signup:" + r.ip, 5, 3600_000)) return AdHttp.json(429, obj("error", "Too many sign-ups from this address"));
        JSONObject b = AdHttp.body(r);
        String role = b.optString("role", "");
        if (!role.equals("advertiser") && !role.equals("publisher")) throw bad("role must be advertiser or publisher");
        long id = insertUser(b, role);
        return loginResponse(id);
    }

    private long insertUser(JSONObject b, String role) {
        String email = AdHttp.str(b, "email", 3, 254).toLowerCase(Locale.ROOT);
        if (!AdSecurity.isEmail(email)) throw bad("Enter a valid email address");
        String name = AdHttp.str(b, "name", 1, 80);
        String company = b.has("company") ? AdHttp.str(b, "company", 0, 80) : "";
        String pw = b.optString("password", "");
        AdSecurity.checkPasswordPolicy(pw);
        if (db.count(AdDb.USERS, u -> email.equals(u.optString("email"))) > 0) throw new AdHttp.HttpError(409, "That email is already registered");
        JSONObject row = new JSONObject();
        AdHttp.put(row, "email", email);
        AdHttp.put(row, "name", name);
        AdHttp.put(row, "company", company);
        AdHttp.put(row, "role", role);
        AdHttp.put(row, "pass_hash", AdSecurity.hashPassword(pw));
        AdHttp.put(row, "status", "active");
        AdHttp.put(row, "balance_micros", 0L);
        AdHttp.put(row, "created_at", System.currentTimeMillis());
        return db.insert(AdDb.USERS, row);
    }

    private static JSONObject userJson(AdSecurity.User u) {
        return obj("id", u.id, "email", u.email, "name", u.name, "company", u.company, "role", u.role, "balance_micros", u.balance);
    }

    private AdHttp.Res changePassword(AdHttp.Req r, AdSecurity.User u) {
        JSONObject b = AdHttp.body(r);
        JSONObject row = db.copy(AdDb.USERS, u.id);
        if (row == null || !AdSecurity.verifyPassword(b.optString("old_password", ""), row.optString("pass_hash"))) throw forbidden("Current password is wrong");
        String np = b.optString("new_password", "");
        AdSecurity.checkPasswordPolicy(np);
        db.update(AdDb.USERS, u.id, x -> AdHttp.put(x, "pass_hash", AdSecurity.hashPassword(np)));
        String keep = AdSecurity.sha256Hex(r.token);
        db.deleteWhere(AdDb.SESSIONS, s -> s.optLong("user_id") == u.id && !keep.equals(s.optString("token_hash")));
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res meta(AdSecurity.User u) {
        return AdHttp.ok(obj("user", userJson(u), "currency", db.setting("currency", "USD"),
                "rev_share_pct", db.settingLong("rev_share_pct", 70), "allow_signup", "1".equals(db.setting("allow_signup", "0")),
                "public_base_url", db.setting("public_base_url", ""),
                "conversion_tracking", "1".equals(db.setting("conversion_tracking", "0")),
                "geo_db_version", db.setting("geo_db_version", "none"),
                "email_enabled", "1".equals(db.setting("email_enabled", "0"))));
    }

    private AdHttp.Res listUsers(AdHttp.Req r) {
        String role = r.param("role");
        Map<Long, Long> earned = new HashMap<>(), paid = new HashMap<>();
        for (JSONObject s : db.list(AdDb.STATS, x -> true)) {
            JSONObject site = db.copy(AdDb.SITES, s.optLong("site_id"));
            if (site != null) earned.merge(site.optLong("owner_id"), s.optLong("pub_micros"), Long::sum);
        }
        for (JSONObject b : db.list(AdDb.BILLING, x -> "payout".equals(x.optString("type")))) {
            paid.merge(b.optLong("user_id"), -b.optLong("amount_micros"), Long::sum);
        }
        JSONArray out = new JSONArray();
        for (JSONObject u : db.list(AdDb.USERS, x -> role == null || role.equals(x.optString("role")))) {
            AdHttp.put(u, "earned_micros", earned.getOrDefault(u.optLong("id"), 0L));
            AdHttp.put(u, "paid_micros", paid.getOrDefault(u.optLong("id"), 0L));
            out.put(u);
        }
        return AdHttp.ok(out);
    }

    private AdHttp.Res createUser(AdHttp.Req r, AdSecurity.User admin) {
        JSONObject b = AdHttp.body(r);
        String role = b.optString("role", "");
        if (!role.matches("admin|publisher|advertiser")) throw bad("role must be admin, publisher or advertiser");
        long id = insertUser(b, role);
        db.audit(admin.id, "user.create", role + " #" + id);
        return AdHttp.ok(obj("id", id));
    }

    private AdHttp.Res patchUser(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject b = AdHttp.body(r);
        if (db.copy(AdDb.USERS, id) == null) throw notFound("User not found");
        if (b.has("status")) {
            String st = b.optString("status");
            if (!st.equals("active") && !st.equals("disabled")) throw bad("status must be active or disabled");
            if (id == admin.id && st.equals("disabled")) throw bad("You cannot disable your own account");
            db.update(AdDb.USERS, id, x -> AdHttp.put(x, "status", st));
            if (st.equals("disabled")) db.deleteWhere(AdDb.SESSIONS, s -> s.optLong("user_id") == id);
        }
        if (b.has("name")) db.update(AdDb.USERS, id, x -> AdHttp.put(x, "name", AdHttp.str(b, "name", 1, 80)));
        if (b.has("company")) db.update(AdDb.USERS, id, x -> AdHttp.put(x, "company", AdHttp.str(b, "company", 0, 80)));
        if (b.has("password")) {
            String pw = b.optString("password", "");
            AdSecurity.checkPasswordPolicy(pw);
            db.update(AdDb.USERS, id, x -> AdHttp.put(x, "pass_hash", AdSecurity.hashPassword(pw)));
            db.deleteWhere(AdDb.SESSIONS, s -> s.optLong("user_id") == id);
        }
        db.audit(admin.id, "user.update", "#" + id);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res deleteUser(AdSecurity.User admin, long id) {
        JSONObject target = db.copy(AdDb.USERS, id);
        if (target == null) throw notFound("User not found");
        if (id == admin.id) throw bad("You cannot delete your own account");
        if ("admin".equals(target.optString("role")) && db.count(AdDb.USERS, u -> "admin".equals(u.optString("role"))) <= 1) {
            throw bad("The last administrator cannot be deleted");
        }
        List<Long> campaignIds = new ArrayList<>(), siteIds = new ArrayList<>();
        List<String> images = new ArrayList<>();
        for (JSONObject c : db.list(AdDb.CAMPAIGNS, x -> x.optLong("advertiser_id") == id)) campaignIds.add(c.optLong("id"));
        for (JSONObject s : db.list(AdDb.SITES, x -> x.optLong("owner_id") == id)) siteIds.add(s.optLong("id"));
        for (JSONObject cr : db.list(AdDb.CREATIVES, x -> campaignIds.contains(x.optLong("campaign_id")))) {
            if (!cr.optString("image_file").isEmpty()) images.add(cr.optString("image_file"));
        }
        db.transaction(() -> {
            db.deleteWhere(AdDb.STATS, x -> campaignIds.contains(x.optLong("campaign_id")) || siteIds.contains(x.optLong("site_id")));
            db.deleteWhere(AdDb.GEO, x -> campaignIds.contains(x.optLong("campaign_id")) || siteIds.contains(x.optLong("site_id")));
            db.deleteWhere(AdDb.EVENTS, x -> campaignIds.contains(x.optLong("campaign_id")) || siteIds.contains(x.optLong("site_id")));
            db.deleteWhere(AdDb.CONVERSIONS, x -> campaignIds.contains(x.optLong("campaign_id")) || siteIds.contains(x.optLong("site_id")));
            db.deleteWhere(AdDb.EXPERIMENTS, x -> campaignIds.contains(x.optLong("campaign_id")));
            db.deleteWhere(AdDb.CREATIVES, x -> campaignIds.contains(x.optLong("campaign_id")));
            db.deleteWhere(AdDb.CAMPAIGNS, x -> campaignIds.contains(x.optLong("id")));
            for (long site : siteIds) db.deleteWhere(AdDb.PLACEMENTS, x -> x.optLong("site_id") == site);
            db.deleteWhere(AdDb.SITES, x -> siteIds.contains(x.optLong("id")));
            db.deleteWhere(AdDb.SESSIONS, x -> x.optLong("user_id") == id);
            db.deleteWhere(AdDb.BILLING, x -> x.optLong("user_id") == id);
            db.deleteWhere(AdDb.PAYOUT_REPORTS, x -> x.optLong("user_id") == id);
            db.delete(AdDb.USERS, id);
            db.audit(admin.id, "user.delete", target.optString("email") + " (#" + id + ")");
        });
        for (String f : images) {
            if (f.matches("^[a-f0-9]{24}\\.(png|jpg|gif|webp)$")) new File(AdDelivery.creativesDir(ctx), f).delete();
        }
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res credit(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject b = AdHttp.body(r);
        JSONObject user = db.copy(AdDb.USERS, id);
        if (user == null) throw notFound("User not found");
        if (!"advertiser".equals(user.optString("role"))) throw bad("Only advertiser accounts hold a prepaid balance");
        String kind = b.optString("kind", "topup");
        if (!kind.equals("topup") && !kind.equals("adjustment")) throw bad("kind must be topup or adjustment");
        long amount = AdSecurity.micros(b, "amount", true);
        if (amount == 0) throw bad("amount must be greater than zero");
        boolean debit = kind.equals("adjustment") && b.optBoolean("debit", false);
        long signed = debit ? -amount : amount;
        long after = user.optLong("balance_micros") + signed;
        if (after < 0) throw new AdHttp.HttpError(409, "Balance cannot go below zero");
        db.transaction(() -> {
            db.update(AdDb.USERS, id, x -> AdHttp.put(x, "balance_micros", after));
            JSONObject row = obj("ts", System.currentTimeMillis(), "user_id", id, "type", kind, "amount_micros", signed,
                    "balance_after_micros", after, "note", AdHttp.str(b, "note", 0, 200), "created_by", admin.id);
            db.insert(AdDb.BILLING, row);
            db.audit(admin.id, "billing." + kind, "user #" + id + " " + signed);
        });
        return AdHttp.ok(obj("balance_micros", after));
    }

    private AdHttp.Res payout(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject b = AdHttp.body(r);
        JSONObject user = db.copy(AdDb.USERS, id);
        if (user == null) throw notFound("User not found");
        if (!"publisher".equals(user.optString("role"))) throw bad("Payouts are only recorded for publishers");
        long amount = AdSecurity.micros(b, "amount", true);
        if (amount == 0) throw bad("amount must be greater than zero");
        long earned = 0, paid = 0;
        for (JSONObject s : db.list(AdDb.STATS, x -> true)) {
            JSONObject site = db.copy(AdDb.SITES, s.optLong("site_id"));
            if (site != null && site.optLong("owner_id") == id) earned += s.optLong("pub_micros");
        }
        for (JSONObject x : db.list(AdDb.BILLING, y -> y.optLong("user_id") == id && "payout".equals(y.optString("type")))) {
            paid -= x.optLong("amount_micros");
        }
        if (amount > earned - paid) throw new AdHttp.HttpError(409, "Payout exceeds the publisher's unpaid earnings");
        long unpaid = earned - paid - amount;
        db.transaction(() -> {
            JSONObject row = obj("ts", System.currentTimeMillis(), "user_id", id, "type", "payout", "amount_micros", -amount,
                    "balance_after_micros", unpaid, "note", AdHttp.str(b, "note", 0, 200), "created_by", admin.id);
            db.insert(AdDb.BILLING, row);
            db.audit(admin.id, "billing.payout", "user #" + id + " " + amount);
        });
        return AdHttp.ok(obj("unpaid_micros", unpaid));
    }

    private JSONObject siteFor(AdSecurity.User u, long id) {
        JSONObject s = db.copy(AdDb.SITES, id);
        if (s == null || "removed".equals(s.optString("status")) || (!u.isAdmin() && s.optLong("owner_id") != u.id)) {
            throw notFound("Site not found");
        }
        return s;
    }

    private AdHttp.Res listSites(AdSecurity.User u) {
        Map<Long, String> emails = new HashMap<>();
        for (JSONObject x : db.list(AdDb.USERS, y -> true)) emails.put(x.optLong("id"), x.optString("email"));
        Map<Long, Integer> counts = new HashMap<>();
        for (JSONObject p : db.list(AdDb.PLACEMENTS, x -> !"removed".equals(x.optString("status")))) {
            counts.merge(p.optLong("site_id"), 1, Integer::sum);
        }
        JSONArray out = new JSONArray();
        for (JSONObject s : db.list(AdDb.SITES, x -> !"removed".equals(x.optString("status"))
                && (u.isAdmin() || x.optLong("owner_id") == u.id))) {
            AdHttp.put(s, "owner_email", emails.getOrDefault(s.optLong("owner_id"), ""));
            AdHttp.put(s, "placements", counts.getOrDefault(s.optLong("id"), 0));
            out.put(s);
        }
        List<JSONObject> sorted = AdDb.sorted(out, (a, b) -> Long.compare(b.optLong("id"), a.optLong("id")));
        JSONArray ordered = new JSONArray();
        for (JSONObject s : sorted) ordered.put(s);
        return AdHttp.ok(ordered);
    }

    private AdHttp.Res createSite(AdHttp.Req r, AdSecurity.User u) {
        if (!u.isAdmin() && !"publisher".equals(u.role)) throw forbidden("Only publishers can register websites");
        JSONObject b = AdHttp.body(r);
        long owner = u.id;
        if (u.isAdmin() && b.has("owner_id")) {
            owner = AdHttp.num(b, "owner_id", 1, Long.MAX_VALUE, u.id);
            JSONObject candidate = db.copy(AdDb.USERS, owner);
            if (candidate == null || !"publisher".equals(candidate.optString("role"))) throw bad("owner_id must be a publisher");
        }
        String name = AdHttp.str(b, "name", 1, 80);
        String domain = AdSecurity.normalizeDomain(b.optString("domain", ""));
        if (domain == null) throw bad("Enter a domain like example.com (no path or port)");
        String dom = domain;
        if (db.count(AdDb.SITES, s -> dom.equals(s.optString("domain")) && !"removed".equals(s.optString("status"))) > 0) {
            throw new AdHttp.HttpError(409, "That domain is already registered");
        }
        final long ownerId = owner;
        long owned = db.count(AdDb.SITES, s -> s.optLong("owner_id") == ownerId && !"removed".equals(s.optString("status")));
        if (owned >= 50) throw bad("Site limit reached (50)");
        JSONObject row = obj("owner_id", owner, "name", name, "domain", domain,
                "status", u.isAdmin() ? "approved" : "pending", "flagged", 0, "flag_reason", "",
                "created_at", System.currentTimeMillis());
        long id = db.insert(AdDb.SITES, row);
        return AdHttp.ok(obj("id", id));
    }

    private AdHttp.Res patchSite(AdHttp.Req r, AdSecurity.User u, long id) {
        JSONObject s = siteFor(u, id);
        JSONObject b = AdHttp.body(r);
        if (b.has("name")) db.update(AdDb.SITES, id, x -> AdHttp.put(x, "name", AdHttp.str(b, "name", 1, 80)));
        if (b.has("domain")) {
            String d = AdSecurity.normalizeDomain(b.optString("domain", ""));
            if (d == null) throw bad("Enter a domain like example.com");
            if (!d.equals(s.optString("domain"))) {
                if (db.count(AdDb.SITES, x -> d.equals(x.optString("domain")) && !"removed".equals(x.optString("status"))) > 0) {
                    throw new AdHttp.HttpError(409, "That domain is already registered");
                }
                db.update(AdDb.SITES, id, x -> {
                    AdHttp.put(x, "domain", d);
                    AdHttp.put(x, "status", u.isAdmin() ? s.optString("status") : "pending");
                });
            }
        }
        if (b.has("status")) {
            needAdmin(u);
            String st = b.optString("status");
            if (!st.matches("pending|approved|rejected")) throw bad("status must be pending, approved or rejected");
            db.update(AdDb.SITES, id, x -> AdHttp.put(x, "status", st));
            db.audit(u.id, "site." + st, "#" + id);
        }
        if (b.has("flagged")) {
            needAdmin(u);
            boolean flagged = b.optBoolean("flagged");
            db.update(AdDb.SITES, id, x -> {
                AdHttp.put(x, "flagged", flagged ? 1 : 0);
                if (!flagged) AdHttp.put(x, "flag_reason", "");
            });
        }
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res deleteSite(AdSecurity.User u, long id) {
        siteFor(u, id);
        db.transaction(() -> {
            db.updateWhere(AdDb.PLACEMENTS, x -> x.optLong("site_id") == id, x -> AdHttp.put(x, "status", "removed"));
            db.update(AdDb.SITES, id, x -> AdHttp.put(x, "status", "removed"));
            db.audit(u.id, "site.remove", "#" + id);
        });
        return AdHttp.ok(obj("ok", true));
    }

    private JSONObject placementFor(AdSecurity.User u, long id) {
        JSONObject p = db.copy(AdDb.PLACEMENTS, id);
        if (p == null || "removed".equals(p.optString("status"))) throw notFound("Placement not found");
        JSONObject s = db.copy(AdDb.SITES, p.optLong("site_id"));
        if (s == null || "removed".equals(s.optString("status")) || (!u.isAdmin() && s.optLong("owner_id") != u.id)) {
            throw notFound("Placement not found");
        }
        AdHttp.put(p, "domain", s.optString("domain"));
        AdHttp.put(p, "owner_id", s.optLong("owner_id"));
        return p;
    }

    private AdHttp.Res listPlacements(AdHttp.Req r, AdSecurity.User u) {
        long site = idParam(r, "site_id");
        Map<Long, JSONObject> sites = new HashMap<>();
        for (JSONObject s : db.list(AdDb.SITES, x -> !"removed".equals(x.optString("status")))) sites.put(s.optLong("id"), s);
        JSONArray out = new JSONArray();
        for (JSONObject p : db.list(AdDb.PLACEMENTS, x -> !"removed".equals(x.optString("status"))
                && (site == 0 || x.optLong("site_id") == site))) {
            JSONObject s = sites.get(p.optLong("site_id"));
            if (s == null || (!u.isAdmin() && s.optLong("owner_id") != u.id)) continue;
            AdHttp.put(p, "domain", s.optString("domain"));
            out.put(p);
        }
        List<JSONObject> sorted = AdDb.sorted(out, (a, b) -> Long.compare(b.optLong("id"), a.optLong("id")));
        JSONArray ordered = new JSONArray();
        for (JSONObject p : sorted) ordered.put(p);
        return AdHttp.ok(ordered);
    }

    private AdHttp.Res createPlacement(AdHttp.Req r, AdSecurity.User u) {
        JSONObject b = AdHttp.body(r);
        long siteId = AdHttp.num(b, "site_id", 1, Long.MAX_VALUE, 0);
        siteFor(u, siteId);
        String name = AdHttp.str(b, "name", 1, 80);
        String format = b.optString("format", "");
        if (!format.matches("banner|native|text")) throw bad("format must be banner, native or text");
        long w = 0, h = 0;
        if (format.equals("banner")) {
            w = AdHttp.num(b, "width", 1, 2000, 0);
            h = AdHttp.num(b, "height", 1, 2000, 0);
            if (w == 0 || h == 0) throw bad("Banner placements need a width and height");
        }
        if (db.count(AdDb.PLACEMENTS, p -> p.optLong("site_id") == siteId && !"removed".equals(p.optString("status"))) >= 100) {
            throw bad("Placement limit reached (100 per site)");
        }
        String key = "pl_" + AdSecurity.randomHex(8);
        JSONObject row = obj("site_id", siteId, "name", name, "format", format, "width", w, "height", h,
                "key", key, "status", "active", "created_at", System.currentTimeMillis());
        long id = db.insert(AdDb.PLACEMENTS, row);
        return AdHttp.ok(obj("id", id, "key", key));
    }

    private AdHttp.Res patchPlacement(AdHttp.Req r, AdSecurity.User u, long id) {
        placementFor(u, id);
        JSONObject b = AdHttp.body(r);
        if (b.has("name")) db.update(AdDb.PLACEMENTS, id, x -> AdHttp.put(x, "name", AdHttp.str(b, "name", 1, 80)));
        if (b.has("status")) {
            String st = b.optString("status");
            if (!st.equals("active") && !st.equals("paused")) throw bad("status must be active or paused");
            db.update(AdDb.PLACEMENTS, id, x -> AdHttp.put(x, "status", st));
        }
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res deletePlacement(AdSecurity.User u, long id) {
        placementFor(u, id);
        db.update(AdDb.PLACEMENTS, id, x -> AdHttp.put(x, "status", "removed"));
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res tag(AdHttp.Req r, AdSecurity.User u, long id) {
        JSONObject p = placementFor(u, id);
        String base = db.setting("public_base_url", "");
        String host = r.header("host");
        if (base.isEmpty()) base = "http://" + (host != null && host.matches("^[A-Za-z0-9.:\\[\\]-]{1,100}$") ? host : "YOUR-SERVER");
        String tag = "<div data-adnet-placement=\"" + p.optString("key") + "\"></div>\n<script async src=\"" + base + "/sdk.js\"></script>";
        return AdHttp.ok(obj("tag", tag, "base_url", base, "https_warning", !base.startsWith("https://")));
    }

    private JSONObject campaignRow(AdSecurity.User u, long id) {
        JSONObject c = db.copy(AdDb.CAMPAIGNS, id);
        if (c == null || (!u.isAdmin() && c.optLong("advertiser_id") != u.id)) throw notFound("Campaign not found");
        return c;
    }

    private static JSONObject withTargeting(JSONObject c) {
        try {
            AdHttp.put(c, "targeting", new JSONObject(c.optString("targeting", "{}")));
        } catch (JSONException e) {
            AdHttp.put(c, "targeting", new JSONObject());
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        AdHttp.put(c, "start_date", c.isNull("start_at") ? "" : f.format(new Date(c.optLong("start_at"))));
        AdHttp.put(c, "end_date", c.isNull("end_at") ? "" : f.format(new Date(c.optLong("end_at") - 1)));
        return c;
    }

    private JSONObject campaignJson(AdSecurity.User u, long id) {
        campaignRow(u, id);
        JSONObject c = db.copy(AdDb.CAMPAIGNS, id);
        JSONObject adv = db.copy(AdDb.USERS, c.optLong("advertiser_id"));
        AdHttp.put(c, "advertiser_email", adv == null ? "" : adv.optString("email"));
        AdHttp.put(c, "advertiser_company", adv == null ? "" : adv.optString("company"));
        long imps = 0, clicks = 0, conv = 0;
        for (JSONObject s : db.list(AdDb.STATS, x -> x.optLong("campaign_id") == id)) {
            imps += s.optLong("imps");
            clicks += s.optLong("clicks");
            conv += s.optLong("conversions");
        }
        AdHttp.put(c, "imps", imps);
        AdHttp.put(c, "clicks", clicks);
        AdHttp.put(c, "conversions", conv);
        AdHttp.put(c, "creatives", db.count(AdDb.CREATIVES, x -> x.optLong("campaign_id") == id));
        return withTargeting(c);
    }

    private AdHttp.Res listCampaigns(AdSecurity.User u) {
        Map<Long, JSONObject> advertisers = new HashMap<>();
        for (JSONObject x : db.list(AdDb.USERS, y -> true)) advertisers.put(x.optLong("id"), x);
        Map<Long, long[]> totals = new HashMap<>();
        for (JSONObject s : db.list(AdDb.STATS, x -> true)) {
            long[] t = totals.get(s.optLong("campaign_id"));
            if (t == null) {
                t = new long[3];
                totals.put(s.optLong("campaign_id"), t);
            }
            t[0] += s.optLong("imps");
            t[1] += s.optLong("clicks");
            t[2] += s.optLong("conversions");
        }
        Map<Long, Integer> creatives = new HashMap<>();
        for (JSONObject c : db.list(AdDb.CREATIVES, x -> true)) creatives.merge(c.optLong("campaign_id"), 1, Integer::sum);
        JSONArray out = new JSONArray();
        for (JSONObject c : db.list(AdDb.CAMPAIGNS, x -> u.isAdmin() || x.optLong("advertiser_id") == u.id)) {
            JSONObject adv = advertisers.get(c.optLong("advertiser_id"));
            AdHttp.put(c, "advertiser_email", adv == null ? "" : adv.optString("email"));
            AdHttp.put(c, "advertiser_company", adv == null ? "" : adv.optString("company"));
            long[] t = totals.get(c.optLong("id"));
            AdHttp.put(c, "imps", t == null ? 0 : t[0]);
            AdHttp.put(c, "clicks", t == null ? 0 : t[1]);
            AdHttp.put(c, "conversions", t == null ? 0 : t[2]);
            AdHttp.put(c, "creatives", creatives.getOrDefault(c.optLong("id"), 0));
            out.put(withTargeting(c));
        }
        List<JSONObject> sorted = AdDb.sorted(out, (a, b) -> Long.compare(b.optLong("id"), a.optLong("id")));
        JSONArray ordered = new JSONArray();
        for (JSONObject c : sorted) ordered.put(c);
        return AdHttp.ok(ordered);
    }

    private static Object parseDate(JSONObject b, String key, boolean end) {
        if (!b.has(key) || b.isNull(key) || b.optString(key).isEmpty()) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            Calendar c = Calendar.getInstance();
            c.setTime(f.parse(b.optString(key)));
            if (end) c.add(Calendar.DAY_OF_MONTH, 1);
            return c.getTimeInMillis();
        } catch (ParseException e) {
            throw bad(key + " must be a date like 2026-12-31");
        }
    }

    private static final Pattern WORD = Pattern.compile("^[a-z0-9._\\-/]{1,60}$");

    private static void listOf(JSONObject out, JSONObject t, String key, String kind, int maxItems) {
        JSONArray in = t.optJSONArray(key);
        if (in == null || in.length() == 0) return;
        if (in.length() > maxItems) throw bad(key + " has too many entries (max " + maxItems + ")");
        JSONArray res = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            switch (kind) {
                case "device": {
                    String v = in.optString(i);
                    if (!v.matches("mobile|tablet|desktop")) throw bad("devices must be mobile, tablet or desktop");
                    res.put(v);
                    break;
                }
                case "lang": {
                    String v = in.optString(i).toLowerCase(Locale.ROOT);
                    if (!v.matches("[a-z]{2}")) throw bad("languages must be 2-letter codes like en");
                    res.put(v);
                    break;
                }
                case "word": {
                    String v = in.optString(i).toLowerCase(Locale.ROOT);
                    if (!WORD.matcher(v).matches()) throw bad(key + " entries may use letters, digits and . _ - / only");
                    res.put(v);
                    break;
                }
                default: {
                    String[] mm = kind.split(":");
                    long v = in.optLong(i, Long.MIN_VALUE);
                    if (v < Long.parseLong(mm[1]) || v > Long.parseLong(mm[2])) throw bad(key + " has a value out of range");
                    res.put(v);
                }
            }
        }
        AdHttp.put(out, key, res);
    }

    private static String normalizeTargeting(JSONObject t) {
        JSONObject out = new JSONObject();
        if (t == null) return "{}";
        listOf(out, t, "devices", "device", 3);
        listOf(out, t, "languages", "lang", 20);
        listOf(out, t, "sites", "id:1:999999999", 50);
        listOf(out, t, "hours", "id:0:23", 24);
        listOf(out, t, "weekdays", "id:1:7", 7);
        listOf(out, t, "url_contains", "word", 10);
        listOf(out, t, "url_excludes", "word", 10);
        long cap = t.optLong("freq_cap_per_day", 0);
        if (cap < 0 || cap > 100) throw bad("freq_cap_per_day must be between 0 and 100");
        if (cap > 0) AdHttp.put(out, "freq_cap_per_day", cap);
        return out.toString();
    }

    private AdHttp.Res createCampaign(AdHttp.Req r, AdSecurity.User u) {
        JSONObject b = AdHttp.body(r);
        long adv = u.id;
        if (u.isAdmin()) {
            adv = AdHttp.num(b, "advertiser_id", 1, Long.MAX_VALUE, 0);
            JSONObject candidate = db.copy(AdDb.USERS, adv);
            if (candidate == null || !"advertiser".equals(candidate.optString("role"))) throw bad("advertiser_id must be an advertiser account");
        } else if (!"advertiser".equals(u.role)) {
            throw forbidden("Only advertisers can create campaigns");
        }
        String name = AdHttp.str(b, "name", 1, 100);
        String pricing = b.optString("pricing", "");
        if (!pricing.equals("CPM") && !pricing.equals("CPC")) throw bad("pricing must be CPM or CPC");
        long bid = AdSecurity.micros(b, "bid", true);
        if (bid < 1000) throw bad("Minimum bid is 0.001");
        long total = AdSecurity.micros(b, "total_budget", true);
        long daily = AdSecurity.micros(b, "daily_budget", false);
        if (total <= 0) throw bad("total_budget must be greater than zero");
        if (daily > total) throw bad("daily_budget cannot exceed total_budget");
        Object start = parseDate(b, "start", false), end = parseDate(b, "end", true);
        if (start != null && end != null && (Long) end <= (Long) start) throw bad("End date must be after the start date");
        String targeting = normalizeTargeting(b.optJSONObject("targeting"));
        long now = System.currentTimeMillis();
        JSONObject row = obj("advertiser_id", adv, "name", name, "status", "draft", "pricing", pricing,
                "bid_micros", bid, "daily_budget_micros", daily, "total_budget_micros", total, "spent_micros", 0L,
                "start_at", start == null ? JSONObject.NULL : start, "end_at", end == null ? JSONObject.NULL : end,
                "targeting", targeting, "reject_reason", "",
                "conversion_event", AdSecurity.clean(b.optString("conversion_event", "")),
                "conversion_value_micros", AdSecurity.micros(b, "conversion_value", false),
                "created_at", now, "updated_at", now);
        long id = db.insert(AdDb.CAMPAIGNS, row);
        return AdHttp.ok(obj("id", id));
    }

    private AdHttp.Res patchCampaign(AdHttp.Req r, AdSecurity.User u, long id) {
        JSONObject c = campaignRow(u, id);
        String st = c.optString("status");
        if (!st.matches("draft|rejected|paused")) throw new AdHttp.HttpError(409, "Pause the campaign before editing it");
        JSONObject b = AdHttp.body(r);
        JSONObject patch = new JSONObject();
        if (b.has("name")) AdHttp.put(patch, "name", AdHttp.str(b, "name", 1, 100));
        long bid = c.optLong("bid_micros"), total = c.optLong("total_budget_micros"), daily = c.optLong("daily_budget_micros");
        if (b.has("pricing")) {
            String p = b.optString("pricing");
            if (!p.equals("CPM") && !p.equals("CPC")) throw bad("pricing must be CPM or CPC");
            AdHttp.put(patch, "pricing", p);
        }
        if (b.has("bid")) {
            bid = AdSecurity.micros(b, "bid", true);
            if (bid < 1000) throw bad("Minimum bid is 0.001");
            AdHttp.put(patch, "bid_micros", bid);
        }
        if (b.has("total_budget")) {
            total = AdSecurity.micros(b, "total_budget", true);
            AdHttp.put(patch, "total_budget_micros", total);
        }
        if (b.has("daily_budget")) {
            daily = AdSecurity.micros(b, "daily_budget", false);
            AdHttp.put(patch, "daily_budget_micros", daily);
        }
        if (total <= 0 || daily > total) throw bad("Budgets are invalid: total must be positive and at least the daily budget");
        if (total < c.optLong("spent_micros")) throw bad("total_budget cannot be below what is already spent");
        Object start = c.isNull("start_at") ? null : c.optLong("start_at"), end = c.isNull("end_at") ? null : c.optLong("end_at");
        if (b.has("start")) {
            start = parseDate(b, "start", false);
            AdHttp.put(patch, "start_at", start == null ? JSONObject.NULL : start);
        }
        if (b.has("end")) {
            end = parseDate(b, "end", true);
            AdHttp.put(patch, "end_at", end == null ? JSONObject.NULL : end);
        }
        if (start != null && end != null && (Long) end <= (Long) start) throw bad("End date must be after the start date");
        if (b.has("targeting")) AdHttp.put(patch, "targeting", normalizeTargeting(b.optJSONObject("targeting")));
        if (b.has("conversion_event")) AdHttp.put(patch, "conversion_event", AdSecurity.clean(b.optString("conversion_event", "")));
        if (b.has("conversion_value")) AdHttp.put(patch, "conversion_value_micros", AdSecurity.micros(b, "conversion_value", false));
        if (patch.length() == 0) return AdHttp.ok(obj("ok", true));
        AdHttp.put(patch, "updated_at", System.currentTimeMillis());
        db.update(AdDb.CAMPAIGNS, id, patch);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res submitCampaign(AdSecurity.User u, long id) {
        JSONObject c = campaignRow(u, id);
        if (!c.optString("status").matches("draft|rejected")) throw new AdHttp.HttpError(409, "Only draft or rejected campaigns can be submitted");
        if (db.count(AdDb.CREATIVES, x -> x.optLong("campaign_id") == id) == 0) throw bad("Add at least one creative before submitting");
        db.update(AdDb.CAMPAIGNS, id, x -> {
            AdHttp.put(x, "status", "pending");
            AdHttp.put(x, "reject_reason", "");
            AdHttp.put(x, "updated_at", System.currentTimeMillis());
        });
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res approveCampaign(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject c = campaignRow(admin, id);
        if (!"pending".equals(c.optString("status"))) throw new AdHttp.HttpError(409, "Only pending campaigns can be approved");
        boolean withCreatives = r.body.length == 0 || AdHttp.body(r).optBoolean("approve_creatives", true);
        db.transaction(() -> {
            db.update(AdDb.CAMPAIGNS, id, x -> {
                AdHttp.put(x, "status", "approved");
                AdHttp.put(x, "reject_reason", "");
                AdHttp.put(x, "updated_at", System.currentTimeMillis());
            });
            if (withCreatives) {
                db.updateWhere(AdDb.CREATIVES, x -> x.optLong("campaign_id") == id && "pending".equals(x.optString("status"))
                                && (!"banner".equals(x.optString("type")) || !x.optString("image_file").isEmpty()),
                        x -> {
                            AdHttp.put(x, "status", "approved");
                            AdHttp.put(x, "reject_reason", "");
                        });
            }
            db.audit(admin.id, "campaign.approve", "#" + id);
        });
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res rejectCampaign(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject c = campaignRow(admin, id);
        if (!"pending".equals(c.optString("status"))) throw new AdHttp.HttpError(409, "Only pending campaigns can be rejected");
        String reason = AdHttp.str(AdHttp.body(r), "reason", 1, 300);
        db.update(AdDb.CAMPAIGNS, id, x -> {
            AdHttp.put(x, "status", "rejected");
            AdHttp.put(x, "reject_reason", reason);
            AdHttp.put(x, "updated_at", System.currentTimeMillis());
        });
        db.audit(admin.id, "campaign.reject", "#" + id);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res setCampaignStatus(AdSecurity.User u, long id, String from, String to) {
        JSONObject c = campaignRow(u, id);
        if (!from.equals(c.optString("status"))) throw new AdHttp.HttpError(409, "Campaign must be " + from + " to become " + to);
        db.update(AdDb.CAMPAIGNS, id, x -> {
            AdHttp.put(x, "status", to);
            AdHttp.put(x, "updated_at", System.currentTimeMillis());
        });
        return AdHttp.ok(obj("ok", true));
    }

    private JSONObject creativeFor(AdSecurity.User u, long id) {
        JSONObject cr = db.copy(AdDb.CREATIVES, id);
        if (cr == null) throw notFound("Creative not found");
        JSONObject c = db.copy(AdDb.CAMPAIGNS, cr.optLong("campaign_id"));
        if (c == null || (!u.isAdmin() && c.optLong("advertiser_id") != u.id)) throw notFound("Creative not found");
        AdHttp.put(cr, "advertiser_id", c.optLong("advertiser_id"));
        AdHttp.put(cr, "campaign_status", c.optString("status"));
        return cr;
    }

    private AdHttp.Res listCreatives(AdHttp.Req r, AdSecurity.User u) {
        long campaign = idParam(r, "campaign_id");
        String status = r.param("status");
        Map<Long, JSONObject> campaigns = new HashMap<>();
        for (JSONObject c : db.list(AdDb.CAMPAIGNS, x -> true)) campaigns.put(c.optLong("id"), c);
        JSONArray out = new JSONArray();
        for (JSONObject cr : db.list(AdDb.CREATIVES, x -> campaign == 0 || x.optLong("campaign_id") == campaign)) {
            if (status != null && status.matches("pending|approved|rejected") && !status.equals(cr.optString("status"))) continue;
            JSONObject c = campaigns.get(cr.optLong("campaign_id"));
            if (c == null || (!u.isAdmin() && c.optLong("advertiser_id") != u.id)) continue;
            AdHttp.put(cr, "campaign_name", c.optString("name"));
            AdHttp.put(cr, "has_image", cr.optString("image_file").isEmpty() ? 0 : 1);
            out.put(cr);
        }
        List<JSONObject> sorted = AdDb.sorted(out, (a, b) -> Long.compare(b.optLong("id"), a.optLong("id")));
        JSONArray ordered = new JSONArray();
        for (int i = 0; i < sorted.size() && i < 500; i++) ordered.put(sorted.get(i));
        return AdHttp.ok(ordered);
    }

    private static String[] creativeText(JSONObject b, String type) {
        int titleMax = type.equals("native") ? 60 : 70, bodyMax = type.equals("native") ? 140 : 120;
        String title = AdHttp.str(b, "title", type.equals("banner") ? 0 : 1, titleMax);
        String body = AdHttp.str(b, "body", type.equals("banner") ? 0 : 1, bodyMax);
        String cta = AdHttp.str(b, "cta", 0, 24);
        String url = AdHttp.str(b, "click_url", 8, 2048);
        if (!AdSecurity.isHttpUrl(url)) throw bad("click_url must be a full http:// or https:// address");
        return new String[]{title, body, cta, url};
    }

    private AdHttp.Res createCreative(AdHttp.Req r, AdSecurity.User u) {
        JSONObject b = AdHttp.body(r);
        long campaign = AdHttp.num(b, "campaign_id", 1, Long.MAX_VALUE, 0);
        campaignRow(u, campaign);
        if (db.count(AdDb.CREATIVES, x -> x.optLong("campaign_id") == campaign) >= 30) throw bad("Creative limit reached (30 per campaign)");
        String type = b.optString("type", "");
        if (!type.matches("banner|native|text")) throw bad("type must be banner, native or text");
        String[] t = creativeText(b, type);
        long w = 0, h = 0;
        if (type.equals("banner")) {
            w = AdHttp.num(b, "width", 1, 2000, 0);
            h = AdHttp.num(b, "height", 1, 2000, 0);
            if (w == 0 || h == 0) throw bad("Banner creatives need a width and height");
        }
        JSONObject row = obj("campaign_id", campaign, "type", type, "title", t[0], "body", t[1], "cta", t[2],
                "click_url", t[3], "image_file", "", "image_mime", "", "width", w, "height", h,
                "status", "pending", "reject_reason", "", "created_at", System.currentTimeMillis());
        long id = db.insert(AdDb.CREATIVES, row);
        return AdHttp.ok(obj("id", id));
    }

    private AdHttp.Res patchCreative(AdHttp.Req r, AdSecurity.User u, long id) {
        JSONObject cr = creativeFor(u, id);
        JSONObject merged = AdHttp.body(r);
        for (String k : new String[]{"title", "body", "cta", "click_url"}) {
            if (!merged.has(k)) AdHttp.put(merged, k, cr.optString(k));
        }
        String[] t = creativeText(merged, cr.optString("type"));
        db.update(AdDb.CREATIVES, id, x -> {
            AdHttp.put(x, "title", t[0]);
            AdHttp.put(x, "body", t[1]);
            AdHttp.put(x, "cta", t[2]);
            AdHttp.put(x, "click_url", t[3]);
            AdHttp.put(x, "status", "pending");
            AdHttp.put(x, "reject_reason", "");
        });
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res deleteCreative(AdSecurity.User u, long id) {
        JSONObject cr = creativeFor(u, id);
        String f = cr.optString("image_file");
        db.delete(AdDb.CREATIVES, id);
        if (!f.isEmpty()) new File(AdDelivery.creativesDir(ctx), f).delete();
        return AdHttp.ok(obj("ok", true));
    }

    private static String sniff(byte[] b) {
        if (b.length > 12 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b.length > 12 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (b.length > 12 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "gif";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        return null;
    }

    private AdHttp.Res uploadImage(AdHttp.Req r, AdSecurity.User u, long id) throws IOException {
        if (!AdRateLimiter.allow("upload:" + u.id, 20, 60_000)) return AdHttp.json(429, obj("error", "Too many uploads"));
        JSONObject cr = creativeFor(u, id);
        byte[] b = r.body;
        if (b.length > 2 * 1024 * 1024) throw new AdHttp.HttpError(413, "Image is larger than 2 MB");
        String ext = sniff(b);
        if (ext == null) throw bad("Only PNG, JPEG, GIF or WebP images are accepted (SVG is not allowed)");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw bad("That file is not a readable image");
        if (o.outWidth > 4000 || o.outHeight > 4000) throw bad("Image dimensions are too large (max 4000 px)");
        long w = cr.optLong("width"), h = cr.optLong("height");
        if ("banner".equals(cr.optString("type"))) {
            if (o.outWidth != w || o.outHeight != h) throw bad("Banner image must be exactly " + w + "x" + h + " px (this one is " + o.outWidth + "x" + o.outHeight + ")");
        } else {
            w = o.outWidth;
            h = o.outHeight;
        }
        String name = AdSecurity.randomHex(12) + "." + ext;
        File dir = AdDelivery.creativesDir(ctx);
        File tmp = new File(dir, name + ".tmp"), dst = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(b);
            out.getFD().sync();
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("could not store image");
        }
        String old = cr.optString("image_file");
        String mime = ext.equals("jpg") ? "image/jpeg" : "image/" + ext;
        final long width = w, height = h;
        db.update(AdDb.CREATIVES, id, x -> {
            AdHttp.put(x, "image_file", name);
            AdHttp.put(x, "image_mime", mime);
            AdHttp.put(x, "width", width);
            AdHttp.put(x, "height", height);
            AdHttp.put(x, "status", "pending");
            AdHttp.put(x, "reject_reason", "");
        });
        if (!old.isEmpty()) new File(dir, old).delete();
        return AdHttp.ok(obj("ok", true, "width", o.outWidth, "height", o.outHeight));
    }

    private AdHttp.Res getImage(AdSecurity.User u, long id) throws IOException {
        JSONObject cr = creativeFor(u, id);
        String f = cr.optString("image_file");
        if (f.isEmpty() || !f.matches("^[a-f0-9]{24}\\.(png|jpg|gif|webp)$")) throw notFound("No image");
        File file = new File(AdDelivery.creativesDir(ctx), f);
        if (!file.isFile()) throw notFound("No image");
        return AdHttp.bytes(200, cr.optString("image_mime"), AdDelivery.readFile(file)).header("Cache-Control", "private, max-age=60");
    }

    private AdHttp.Res reviewCreative(AdHttp.Req r, AdSecurity.User admin, long id, boolean approve) {
        JSONObject cr = creativeFor(admin, id);
        if (approve) {
            if ("banner".equals(cr.optString("type")) && cr.optString("image_file").isEmpty()) throw bad("Banner has no image yet");
            db.update(AdDb.CREATIVES, id, x -> {
                AdHttp.put(x, "status", "approved");
                AdHttp.put(x, "reject_reason", "");
            });
        } else {
            String reason = AdHttp.str(AdHttp.body(r), "reason", 1, 300);
            db.update(AdDb.CREATIVES, id, x -> {
                AdHttp.put(x, "status", "rejected");
                AdHttp.put(x, "reject_reason", reason);
            });
        }
        db.audit(admin.id, approve ? "creative.approve" : "creative.reject", "#" + id);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res overview() {
        long day = AdDb.today();
        JSONObject today = totals(x -> x.optLong("day") == day);
        JSONObject all = totals(x -> true);
        return AdHttp.ok(obj("today", today, "all_time", all,
                "pending_campaigns", db.count(AdDb.CAMPAIGNS, x -> "pending".equals(x.optString("status"))),
                "pending_creatives", db.count(AdDb.CREATIVES, x -> "pending".equals(x.optString("status"))
                        && !"draft".equals(campaignStatus(x.optLong("campaign_id")))),
                "pending_sites", db.count(AdDb.SITES, x -> "pending".equals(x.optString("status"))),
                "flagged_sites", db.count(AdDb.SITES, x -> x.optLong("flagged") == 1 && !"removed".equals(x.optString("status"))),
                "advertisers", db.count(AdDb.USERS, x -> "advertiser".equals(x.optString("role"))),
                "publishers", db.count(AdDb.USERS, x -> "publisher".equals(x.optString("role")))));
    }

    private String campaignStatus(long id) {
        JSONObject c = db.copy(AdDb.CAMPAIGNS, id);
        return c == null ? "" : c.optString("status");
    }

    private JSONObject totals(java.util.function.Predicate<JSONObject> filter) {
        long served = 0, imps = 0, clicks = 0, invalid = 0, spend = 0, earnings = 0, conversions = 0;
        for (JSONObject s : db.list(AdDb.STATS, filter)) {
            served += s.optLong("served");
            imps += s.optLong("imps");
            clicks += s.optLong("clicks");
            invalid += s.optLong("invalid_imps") + s.optLong("invalid_clicks");
            spend += s.optLong("spend_micros");
            earnings += s.optLong("pub_micros");
            conversions += s.optLong("conversions");
        }
        return obj("served", served, "imps", imps, "clicks", clicks, "invalid", invalid,
                "spend", spend, "earnings", earnings, "conversions", conversions);
    }

    private AdHttp.Res fraud() {
        Map<Long, JSONObject> users = new HashMap<>();
        for (JSONObject x : db.list(AdDb.USERS, y -> true)) users.put(x.optLong("id"), x);
        JSONArray flagged = new JSONArray();
        for (JSONObject s : db.list(AdDb.SITES, x -> x.optLong("flagged") == 1 && !"removed".equals(x.optString("status")))) {
            JSONObject owner = users.get(s.optLong("owner_id"));
            flagged.put(obj("id", s.optLong("id"), "name", s.optString("name"), "domain", s.optString("domain"),
                    "flag_reason", s.optString("flag_reason"), "owner_email", owner == null ? "" : owner.optString("email")));
        }
        Map<String, Long> reasons = new HashMap<>();
        for (JSONObject f : db.list(AdDb.FRAUD_LOG, x -> x.optLong("day") >= AdDb.today() - 6)) {
            reasons.merge(f.optString("reason"), f.optLong("n"), Long::sum);
        }
        JSONArray rs = new JSONArray();
        reasons.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e ->
                rs.put(obj("reason", e.getKey(), "n", e.getValue())));
        Map<Long, long[]> bySite = new HashMap<>();
        for (JSONObject s : db.list(AdDb.STATS, x -> x.optLong("day") >= AdDb.today() - 6)) {
            long[] v = bySite.get(s.optLong("site_id"));
            if (v == null) {
                v = new long[4];
                bySite.put(s.optLong("site_id"), v);
            }
            v[0] += s.optLong("invalid_imps");
            v[1] += s.optLong("invalid_clicks");
            v[2] += s.optLong("imps");
            v[3] += s.optLong("clicks");
        }
        List<Map.Entry<Long, long[]>> list = new ArrayList<>(bySite.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        JSONArray bs = new JSONArray();
        for (int i = 0; i < list.size() && i < 25; i++) {
            Map.Entry<Long, long[]> e = list.get(i);
            long[] v = e.getValue();
            if (v[0] + v[1] == 0) continue;
            JSONObject site = db.copy(AdDb.SITES, e.getKey());
            bs.put(obj("id", e.getKey(), "domain", site == null ? "" : site.optString("domain"),
                    "invalid_imps", v[0], "invalid_clicks", v[1], "imps", v[2], "clicks", v[3]));
        }
        return AdHttp.ok(obj("flagged_sites", flagged, "reasons_7d", rs, "invalid_by_site_7d", bs));
    }

    private static final String[] SETTING_KEYS = {"rev_share_pct", "currency", "public_base_url", "allow_signup", "trust_proxy",
            "fraud_min_click_ms", "fraud_repeat_clicks_hour", "fraud_ip_clicks_min", "fraud_max_imps_visitor_day", "retention_days",
            "conversion_tracking", "conversion_attribution_days", "conversion_dedupe_hours",
            "email_enabled", "email_recipients", "email_hour_utc",
            "smtp_host", "smtp_port", "smtp_security", "smtp_username", "smtp_password", "smtp_from"};

    private JSONObject settingsJson() {
        JSONObject o = new JSONObject();
        for (String k : SETTING_KEYS) {
            if ("smtp_password".equals(k)) continue;
            AdHttp.put(o, k, db.setting(k, ""));
        }
        AdHttp.put(o, "smtp_password_set", !db.setting("smtp_password", "").isEmpty());
        AdHttp.put(o, "geo_db_version", db.setting("geo_db_version", "none"));
        return o;
    }

    private AdHttp.Res putSettings(AdHttp.Req r, AdSecurity.User admin) {
        JSONObject b = AdHttp.body(r);
        Iterator<String> it = b.keys();
        List<String[]> updates = new ArrayList<>();
        while (it.hasNext()) {
            String k = it.next();
            String v = AdSecurity.clean(b.optString(k, ""));
            boolean known = false;
            for (String s : SETTING_KEYS) if (s.equals(k)) known = true;
            if (!known) throw bad("Unknown setting: " + k);
            switch (k) {
                case "rev_share_pct":
                    if (!v.matches("[0-9]{1,3}") || Integer.parseInt(v) > 100) throw bad("rev_share_pct must be 0-100");
                    break;
                case "currency":
                    if (!v.matches("[A-Z]{3}")) throw bad("currency must be a 3-letter code like USD");
                    break;
                case "public_base_url":
                    if (!v.isEmpty() && (!AdSecurity.isHttpUrl(v) || v.endsWith("/"))) throw bad("public_base_url must be http(s)://host[:port] without a trailing slash");
                    break;
                case "allow_signup": case "trust_proxy": case "conversion_tracking": case "email_enabled":
                    if (!v.equals("0") && !v.equals("1")) throw bad(k + " must be 0 or 1");
                    break;
                case "fraud_min_click_ms":
                    if (!v.matches("[0-9]{3,5}") || Integer.parseInt(v) < 100 || Integer.parseInt(v) > 10000) throw bad("fraud_min_click_ms must be 100-10000");
                    break;
                case "fraud_repeat_clicks_hour": case "fraud_ip_clicks_min":
                    if (!v.matches("[0-9]{1,3}") || Integer.parseInt(v) < 1 || Integer.parseInt(v) > 100) throw bad(k + " must be 1-100");
                    break;
                case "fraud_max_imps_visitor_day":
                    if (!v.matches("[0-9]{1,4}") || Integer.parseInt(v) < 1) throw bad("fraud_max_imps_visitor_day must be at least 1");
                    break;
                case "retention_days":
                    if (!v.matches("[0-9]{1,3}") || Integer.parseInt(v) < 7 || Integer.parseInt(v) > 365) throw bad("retention_days must be 7-365");
                    break;
                case "conversion_attribution_days":
                    if (!v.matches("[0-9]{1,3}") || Integer.parseInt(v) < 1 || Integer.parseInt(v) > 90) throw bad("conversion_attribution_days must be 1-90");
                    break;
                case "conversion_dedupe_hours":
                    if (!v.matches("[0-9]{1,4}") || Integer.parseInt(v) < 1 || Integer.parseInt(v) > 720) throw bad("conversion_dedupe_hours must be 1-720");
                    break;
                case "email_hour_utc":
                    if (!v.matches("[0-9]{1,2}") || Integer.parseInt(v) > 23) throw bad("email_hour_utc must be 0-23");
                    break;
                case "email_recipients":
                    if (v.length() > 500) throw bad("email_recipients is too long");
                    for (String addr : v.split("[,;\\s]+")) {
                        if (!addr.isEmpty() && !AdSecurity.isEmail(addr)) throw bad("Invalid recipient: " + addr);
                    }
                    break;
                case "smtp_host": case "smtp_from":
                    if (v.length() > 200) throw bad(k + " is too long");
                    break;
                case "smtp_port":
                    if (!v.matches("[0-9]{1,5}") || Integer.parseInt(v) < 1 || Integer.parseInt(v) > 65535) throw bad("smtp_port must be 1-65535");
                    break;
                case "smtp_security":
                    if (!v.matches("none|starttls|tls")) throw bad("smtp_security must be none, starttls or tls");
                    break;
                case "smtp_username": case "smtp_password":
                    if (v.length() > 200) throw bad(k + " is too long");
                    break;
                default:
                    break;
            }
            if ("smtp_password".equals(k) && v.isEmpty()) continue;
            updates.add(new String[]{k, v});
        }
        for (String[] kv : updates) db.setSetting(kv[0], kv[1]);
        db.audit(admin.id, "settings.update", updates.size() + " values");
        return AdHttp.ok(settingsJson());
    }

    private AdHttp.Res importGeo(AdHttp.Req r, AdSecurity.User admin) throws IOException {
        String version = AdSecurity.clean(r.header("x-geo-version"));
        if (version.isEmpty()) version = "custom-" + System.currentTimeMillis();
        AdGeo.importCsv(db, version, r.body);
        db.audit(admin.id, "geo.update", version);
        return AdHttp.ok(AdGeo.status(db));
    }
}
