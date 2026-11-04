package com.yadaventerprise.phoneserver;

import static com.yadaventerprise.phoneserver.AdHttp.bad;
import static com.yadaventerprise.phoneserver.AdHttp.forbidden;
import static com.yadaventerprise.phoneserver.AdHttp.notFound;
import static com.yadaventerprise.phoneserver.AdHttp.obj;

import android.content.Context;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
                return AdHttp.ok(obj("needs_setup", db.scalar("SELECT COUNT(*) FROM users", 0) == 0));
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
                db.run("DELETE FROM sessions WHERE token_hash=?", AdSecurity.sha256Hex(r.token));
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
            case "GET /api/billing": return reports.billing(r, u);

            case "GET /api/admin/overview": needAdmin(u); return overview();
            case "GET /api/admin/fraud": needAdmin(u); return fraud();
            case "GET /api/admin/settings": needAdmin(u); return AdHttp.ok(settingsJson());
            case "PUT /api/admin/settings": needAdmin(u); return putSettings(r, u);
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

    // ================================================================ auth + users

    private AdHttp.Res setup(AdHttp.Req r) {
        if (!AdRateLimiter.allow("setup:" + r.ip, 10, 3600_000)) return AdHttp.json(429, obj("error", "Too many attempts"));
        if (db.scalar("SELECT COUNT(*) FROM users", 0) > 0) return AdHttp.json(409, obj("error", "Already set up"));
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
        JSONObject row = db.one("SELECT id, pass_hash, status FROM users WHERE email=?", email);
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
        JSONObject row = db.one("SELECT id,email,name,company,role,balance_micros FROM users WHERE id=?", userId);
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
        if (db.scalar("SELECT COUNT(*) FROM users WHERE email=?", 0, email) > 0) throw new AdHttp.HttpError(409, "That email is already registered");
        return db.insert("INSERT INTO users(email,name,company,role,pass_hash,created_at) VALUES(?,?,?,?,?,?)",
                email, name, company, role, AdSecurity.hashPassword(pw), System.currentTimeMillis());
    }

    private static JSONObject userJson(AdSecurity.User u) {
        return obj("id", u.id, "email", u.email, "name", u.name, "company", u.company, "role", u.role, "balance_micros", u.balance);
    }

    private AdHttp.Res changePassword(AdHttp.Req r, AdSecurity.User u) {
        JSONObject b = AdHttp.body(r);
        JSONObject row = db.one("SELECT pass_hash FROM users WHERE id=?", u.id);
        if (row == null || !AdSecurity.verifyPassword(b.optString("old_password", ""), row.optString("pass_hash"))) throw forbidden("Current password is wrong");
        String np = b.optString("new_password", "");
        AdSecurity.checkPasswordPolicy(np);
        db.run("UPDATE users SET pass_hash=? WHERE id=?", AdSecurity.hashPassword(np), u.id);
        db.run("DELETE FROM sessions WHERE user_id=? AND token_hash<>?", u.id, AdSecurity.sha256Hex(r.token));
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res meta(AdSecurity.User u) {
        return AdHttp.ok(obj("user", userJson(u), "currency", db.setting("currency", "USD"),
                "rev_share_pct", db.settingLong("rev_share_pct", 70), "allow_signup", "1".equals(db.setting("allow_signup", "0")),
                "public_base_url", db.setting("public_base_url", "")));
    }

    private AdHttp.Res listUsers(AdHttp.Req r) {
        String role = r.param("role");
        String sql = "SELECT id,email,name,company,role,status,balance_micros,created_at,"
                + " (SELECT COALESCE(SUM(s.pub_micros),0) FROM stats_daily s JOIN sites st ON st.id=s.site_id WHERE st.owner_id=users.id) earned_micros,"
                + " (SELECT COALESCE(-SUM(amount_micros),0) FROM billing b WHERE b.user_id=users.id AND b.type='payout') paid_micros"
                + " FROM users";
        JSONArray rows = role != null && role.matches("admin|publisher|advertiser")
                ? db.query(sql + " WHERE role=? ORDER BY id", role) : db.query(sql + " ORDER BY id");
        return AdHttp.ok(rows);
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
        if (db.scalar("SELECT COUNT(*) FROM users WHERE id=?", 0, id) == 0) throw notFound("User not found");
        if (b.has("status")) {
            String st = b.optString("status");
            if (!st.equals("active") && !st.equals("disabled")) throw bad("status must be active or disabled");
            if (id == admin.id && st.equals("disabled")) throw bad("You cannot disable your own account");
            db.run("UPDATE users SET status=? WHERE id=?", st, id);
            if (st.equals("disabled")) db.run("DELETE FROM sessions WHERE user_id=?", id);
        }
        if (b.has("name")) db.run("UPDATE users SET name=? WHERE id=?", AdHttp.str(b, "name", 1, 80), id);
        if (b.has("company")) db.run("UPDATE users SET company=? WHERE id=?", AdHttp.str(b, "company", 0, 80), id);
        if (b.has("password")) {
            String pw = b.optString("password", "");
            AdSecurity.checkPasswordPolicy(pw);
            db.run("UPDATE users SET pass_hash=? WHERE id=?", AdSecurity.hashPassword(pw), id);
            db.run("DELETE FROM sessions WHERE user_id=?", id);
        }
        db.audit(admin.id, "user.update", "#" + id);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res deleteUser(AdSecurity.User admin, long id) {
        JSONObject target = db.one("SELECT id,email,role FROM users WHERE id=?", id);
        if (target == null) throw notFound("User not found");
        if (id == admin.id) throw bad("You cannot delete your own account");
        if ("admin".equals(target.optString("role")) && db.scalar("SELECT COUNT(*) FROM users WHERE role='admin'", 0) <= 1) {
            throw bad("The last administrator cannot be deleted");
        }
        JSONArray images = db.query("SELECT k.image_file f FROM creatives k JOIN campaigns c ON c.id=k.campaign_id"
                + " WHERE c.advertiser_id=? AND k.image_file<>''", id);
        db.run("DELETE FROM stats_daily WHERE campaign_id IN (SELECT id FROM campaigns WHERE advertiser_id=?)"
                + " OR site_id IN (SELECT id FROM sites WHERE owner_id=?)", id, id);
        db.run("DELETE FROM events WHERE campaign_id IN (SELECT id FROM campaigns WHERE advertiser_id=?)"
                + " OR site_id IN (SELECT id FROM sites WHERE owner_id=?)", id, id);
        db.run("DELETE FROM users WHERE id=?", id);
        for (int i = 0; i < images.length(); i++) {
            String f = images.optJSONObject(i).optString("f");
            if (f.matches("^[a-f0-9]{24}\\.(png|jpg|gif|webp)$")) new File(AdDelivery.creativesDir(ctx), f).delete();
        }
        db.audit(admin.id, "user.delete", target.optString("email") + " (#" + id + ")");
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res credit(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject b = AdHttp.body(r);
        JSONObject user = db.one("SELECT role, balance_micros FROM users WHERE id=?", id);
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
        db.run("UPDATE users SET balance_micros=? WHERE id=?", after, id);
        db.run("INSERT INTO billing(ts,user_id,type,amount_micros,balance_after_micros,note,created_by) VALUES(?,?,?,?,?,?,?)",
                System.currentTimeMillis(), id, kind, signed, after, AdHttp.str(b, "note", 0, 200), admin.id);
        db.audit(admin.id, "billing." + kind, "user #" + id + " " + signed);
        return AdHttp.ok(obj("balance_micros", after));
    }

    private AdHttp.Res payout(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject b = AdHttp.body(r);
        JSONObject user = db.one("SELECT role FROM users WHERE id=?", id);
        if (user == null) throw notFound("User not found");
        if (!"publisher".equals(user.optString("role"))) throw bad("Payouts are only recorded for publishers");
        long amount = AdSecurity.micros(b, "amount", true);
        if (amount == 0) throw bad("amount must be greater than zero");
        long earned = db.scalar("SELECT COALESCE(SUM(s.pub_micros),0) FROM stats_daily s JOIN sites st ON st.id=s.site_id WHERE st.owner_id=?", 0, id);
        long paid = db.scalar("SELECT COALESCE(-SUM(amount_micros),0) FROM billing WHERE user_id=? AND type='payout'", 0, id);
        if (amount > earned - paid) throw new AdHttp.HttpError(409, "Payout exceeds the publisher's unpaid earnings");
        db.run("INSERT INTO billing(ts,user_id,type,amount_micros,balance_after_micros,note,created_by) VALUES(?,?,?,?,?,?,?)",
                System.currentTimeMillis(), id, "payout", -amount, earned - paid - amount, AdHttp.str(b, "note", 0, 200), admin.id);
        db.audit(admin.id, "billing.payout", "user #" + id + " " + amount);
        return AdHttp.ok(obj("unpaid_micros", earned - paid - amount));
    }

    // ================================================================ sites + placements

    private JSONObject siteFor(AdSecurity.User u, long id) {
        JSONObject s = db.one("SELECT * FROM sites WHERE id=? AND status<>'removed'", id);
        if (s == null || (!u.isAdmin() && s.optLong("owner_id") != u.id)) throw notFound("Site not found");
        return s;
    }

    private AdHttp.Res listSites(AdSecurity.User u) {
        String sql = "SELECT s.id, s.owner_id, o.email owner_email, s.name, s.domain, s.status, s.flagged, s.flag_reason, s.created_at,"
                + " (SELECT COUNT(*) FROM placements p WHERE p.site_id=s.id AND p.status<>'removed') placements"
                + " FROM sites s JOIN users o ON o.id=s.owner_id WHERE s.status<>'removed'";
        return AdHttp.ok(u.isAdmin() ? db.query(sql + " ORDER BY s.id DESC") : db.query(sql + " AND s.owner_id=? ORDER BY s.id DESC", u.id));
    }

    private AdHttp.Res createSite(AdHttp.Req r, AdSecurity.User u) {
        if (!u.isAdmin() && !"publisher".equals(u.role)) throw forbidden("Only publishers can register websites");
        JSONObject b = AdHttp.body(r);
        long owner = u.id;
        if (u.isAdmin() && b.has("owner_id")) {
            owner = AdHttp.num(b, "owner_id", 1, Long.MAX_VALUE, u.id);
            if (db.scalar("SELECT COUNT(*) FROM users WHERE id=? AND role='publisher'", 0, owner) == 0) throw bad("owner_id must be a publisher");
        }
        String name = AdHttp.str(b, "name", 1, 80);
        String domain = AdSecurity.normalizeDomain(b.optString("domain", ""));
        if (domain == null) throw bad("Enter a domain like example.com (no path or port)");
        if (db.scalar("SELECT COUNT(*) FROM sites WHERE domain=? AND status<>'removed'", 0, domain) > 0) throw new AdHttp.HttpError(409, "That domain is already registered");
        if (db.scalar("SELECT COUNT(*) FROM sites WHERE owner_id=? AND status<>'removed'", 0, owner) >= 50) throw bad("Site limit reached (50)");
        long id = db.insert("INSERT INTO sites(owner_id,name,domain,status,created_at) VALUES(?,?,?,?,?)",
                owner, name, domain, u.isAdmin() ? "approved" : "pending", System.currentTimeMillis());
        return AdHttp.ok(obj("id", id));
    }

    private AdHttp.Res patchSite(AdHttp.Req r, AdSecurity.User u, long id) {
        JSONObject s = siteFor(u, id);
        JSONObject b = AdHttp.body(r);
        if (b.has("name")) db.run("UPDATE sites SET name=? WHERE id=?", AdHttp.str(b, "name", 1, 80), id);
        if (b.has("domain")) {
            String d = AdSecurity.normalizeDomain(b.optString("domain", ""));
            if (d == null) throw bad("Enter a domain like example.com");
            if (!d.equals(s.optString("domain"))) {
                if (db.scalar("SELECT COUNT(*) FROM sites WHERE domain=? AND status<>'removed'", 0, d) > 0) throw new AdHttp.HttpError(409, "That domain is already registered");
                db.run("UPDATE sites SET domain=?, status=? WHERE id=?", d, u.isAdmin() ? s.optString("status") : "pending", id);
            }
        }
        if (b.has("status")) {
            needAdmin(u);
            String st = b.optString("status");
            if (!st.matches("pending|approved|rejected")) throw bad("status must be pending, approved or rejected");
            db.run("UPDATE sites SET status=? WHERE id=?", st, id);
            db.audit(u.id, "site." + st, "#" + id);
        }
        if (b.has("flagged")) {
            needAdmin(u);
            db.run("UPDATE sites SET flagged=?, flag_reason=CASE WHEN ?=0 THEN '' ELSE flag_reason END WHERE id=?", b.optBoolean("flagged") ? 1 : 0, b.optBoolean("flagged") ? 1 : 0, id);
        }
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res deleteSite(AdSecurity.User u, long id) {
        siteFor(u, id);
        db.run("UPDATE placements SET status='removed' WHERE site_id=?", id);
        db.run("UPDATE sites SET status='removed' WHERE id=?", id);
        db.audit(u.id, "site.remove", "#" + id);
        return AdHttp.ok(obj("ok", true));
    }

    private JSONObject placementFor(AdSecurity.User u, long id) {
        JSONObject p = db.one("SELECT p.*, s.owner_id, s.domain FROM placements p JOIN sites s ON s.id=p.site_id WHERE p.id=? AND p.status<>'removed' AND s.status<>'removed'", id);
        if (p == null || (!u.isAdmin() && p.optLong("owner_id") != u.id)) throw notFound("Placement not found");
        return p;
    }

    private AdHttp.Res listPlacements(AdHttp.Req r, AdSecurity.User u) {
        long site = idParam(r, "site_id");
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT p.id, p.site_id, p.name, p.format, p.width, p.height, p.key, p.status, s.domain"
                + " FROM placements p JOIN sites s ON s.id=p.site_id WHERE p.status<>'removed' AND s.status<>'removed'");
        if (!u.isAdmin()) {
            sql.append(" AND s.owner_id=?");
            args.add(u.id);
        }
        if (site > 0) {
            sql.append(" AND p.site_id=?");
            args.add(site);
        }
        return AdHttp.ok(db.query(sql.append(" ORDER BY p.id DESC").toString(), args.toArray()));
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
        if (db.scalar("SELECT COUNT(*) FROM placements WHERE site_id=? AND status<>'removed'", 0, siteId) >= 100) throw bad("Placement limit reached (100 per site)");
        String key = "pl_" + AdSecurity.randomHex(8);
        long id = db.insert("INSERT INTO placements(site_id,name,format,width,height,key,created_at) VALUES(?,?,?,?,?,?,?)",
                siteId, name, format, w, h, key, System.currentTimeMillis());
        return AdHttp.ok(obj("id", id, "key", key));
    }

    private AdHttp.Res patchPlacement(AdHttp.Req r, AdSecurity.User u, long id) {
        placementFor(u, id);
        JSONObject b = AdHttp.body(r);
        if (b.has("name")) db.run("UPDATE placements SET name=? WHERE id=?", AdHttp.str(b, "name", 1, 80), id);
        if (b.has("status")) {
            String st = b.optString("status");
            if (!st.equals("active") && !st.equals("paused")) throw bad("status must be active or paused");
            db.run("UPDATE placements SET status=? WHERE id=?", st, id);
        }
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res deletePlacement(AdSecurity.User u, long id) {
        placementFor(u, id);
        db.run("UPDATE placements SET status='removed' WHERE id=?", id);
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

    // ================================================================ campaigns

    private JSONObject campaignRow(AdSecurity.User u, long id) {
        JSONObject c = db.one("SELECT * FROM campaigns WHERE id=?", id);
        if (c == null || (!u.isAdmin() && c.optLong("advertiser_id") != u.id)) throw notFound("Campaign not found");
        return c;
    }

    private static final String CAMPAIGN_SQL = "SELECT c.id, c.advertiser_id, u.email advertiser_email, u.company advertiser_company, c.name, c.status,"
            + " c.pricing, c.bid_micros, c.daily_budget_micros, c.total_budget_micros, c.spent_micros, c.start_at, c.end_at, c.targeting,"
            + " c.reject_reason, c.created_at,"
            + " (SELECT COALESCE(SUM(imps),0) FROM stats_daily s WHERE s.campaign_id=c.id) imps,"
            + " (SELECT COALESCE(SUM(clicks),0) FROM stats_daily s WHERE s.campaign_id=c.id) clicks,"
            + " (SELECT COUNT(*) FROM creatives k WHERE k.campaign_id=c.id) creatives"
            + " FROM campaigns c JOIN users u ON u.id=c.advertiser_id";

    private static JSONObject withTargeting(JSONObject c) {
        try {
            AdHttp.put(c, "targeting", new JSONObject(c.optString("targeting", "{}")));
        } catch (JSONException e) {
            AdHttp.put(c, "targeting", new JSONObject());
        }
        // Dates are formatted here, in the same time zone that parsed them, so the form shows exactly what was saved.
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        AdHttp.put(c, "start_date", c.isNull("start_at") ? "" : f.format(new Date(c.optLong("start_at"))));
        AdHttp.put(c, "end_date", c.isNull("end_at") ? "" : f.format(new Date(c.optLong("end_at") - 1)));
        return c;
    }

    private AdHttp.Res listCampaigns(AdSecurity.User u) {
        JSONArray rows = u.isAdmin() ? db.query(CAMPAIGN_SQL + " ORDER BY c.id DESC")
                : db.query(CAMPAIGN_SQL + " WHERE c.advertiser_id=? ORDER BY c.id DESC", u.id);
        for (int i = 0; i < rows.length(); i++) withTargeting(rows.optJSONObject(i));
        return AdHttp.ok(rows);
    }

    private JSONObject campaignJson(AdSecurity.User u, long id) {
        campaignRow(u, id);
        return withTargeting(db.one(CAMPAIGN_SQL + " WHERE c.id=?", id));
    }

    /** Local-midnight timestamp for "yyyy-MM-dd"; an end date is inclusive, so it becomes the start of the next day. */
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
                default: { // "id:min:max"
                    String[] mm = kind.split(":");
                    long v = in.optLong(i, Long.MIN_VALUE);
                    if (v < Long.parseLong(mm[1]) || v > Long.parseLong(mm[2])) throw bad(key + " has a value out of range");
                    res.put(v);
                }
            }
        }
        AdHttp.put(out, key, res);
    }

    /** Keeps only known targeting keys with validated values. Geo targeting is intentionally absent: the server has no IP-location data. */
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
            if (db.scalar("SELECT COUNT(*) FROM users WHERE id=? AND role='advertiser'", 0, adv) == 0) throw bad("advertiser_id must be an advertiser account");
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
        long id = db.insert("INSERT INTO campaigns(advertiser_id,name,pricing,bid_micros,daily_budget_micros,total_budget_micros,start_at,end_at,targeting,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)", adv, name, pricing, bid, daily, total, start, end, targeting, now, now);
        return AdHttp.ok(obj("id", id));
    }

    private AdHttp.Res patchCampaign(AdHttp.Req r, AdSecurity.User u, long id) {
        JSONObject c = campaignRow(u, id);
        String st = c.optString("status");
        if (!st.matches("draft|rejected|paused")) throw new AdHttp.HttpError(409, "Pause the campaign before editing it");
        JSONObject b = AdHttp.body(r);
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (b.has("name")) { sets.add("name=?"); args.add(AdHttp.str(b, "name", 1, 100)); }
        long bid = c.optLong("bid_micros"), total = c.optLong("total_budget_micros"), daily = c.optLong("daily_budget_micros");
        if (b.has("pricing")) {
            String p = b.optString("pricing");
            if (!p.equals("CPM") && !p.equals("CPC")) throw bad("pricing must be CPM or CPC");
            sets.add("pricing=?"); args.add(p);
        }
        if (b.has("bid")) { bid = AdSecurity.micros(b, "bid", true); if (bid < 1000) throw bad("Minimum bid is 0.001"); sets.add("bid_micros=?"); args.add(bid); }
        if (b.has("total_budget")) { total = AdSecurity.micros(b, "total_budget", true); sets.add("total_budget_micros=?"); args.add(total); }
        if (b.has("daily_budget")) { daily = AdSecurity.micros(b, "daily_budget", false); sets.add("daily_budget_micros=?"); args.add(daily); }
        if (total <= 0 || daily > total) throw bad("Budgets are invalid: total must be positive and at least the daily budget");
        if (total < c.optLong("spent_micros")) throw bad("total_budget cannot be below what is already spent");
        Object start = c.isNull("start_at") ? null : c.optLong("start_at"), end = c.isNull("end_at") ? null : c.optLong("end_at");
        if (b.has("start")) { start = parseDate(b, "start", false); sets.add("start_at=?"); args.add(start); }
        if (b.has("end")) { end = parseDate(b, "end", true); sets.add("end_at=?"); args.add(end); }
        if (start != null && end != null && (Long) end <= (Long) start) throw bad("End date must be after the start date");
        if (b.has("targeting")) { sets.add("targeting=?"); args.add(normalizeTargeting(b.optJSONObject("targeting"))); }
        if (sets.isEmpty()) return AdHttp.ok(obj("ok", true));
        sets.add("updated_at=?");
        args.add(System.currentTimeMillis());
        args.add(id);
        db.run("UPDATE campaigns SET " + join(sets) + " WHERE id=?", args.toArray());
        return AdHttp.ok(obj("ok", true));
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(sb.length() == 0 ? "" : ", ").append(p);
        return sb.toString();
    }

    private AdHttp.Res submitCampaign(AdSecurity.User u, long id) {
        JSONObject c = campaignRow(u, id);
        if (!c.optString("status").matches("draft|rejected")) throw new AdHttp.HttpError(409, "Only draft or rejected campaigns can be submitted");
        if (db.scalar("SELECT COUNT(*) FROM creatives WHERE campaign_id=?", 0, id) == 0) throw bad("Add at least one creative before submitting");
        db.run("UPDATE campaigns SET status='pending', reject_reason='', updated_at=? WHERE id=?", System.currentTimeMillis(), id);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res approveCampaign(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject c = campaignRow(admin, id);
        if (!"pending".equals(c.optString("status"))) throw new AdHttp.HttpError(409, "Only pending campaigns can be approved");
        db.run("UPDATE campaigns SET status='approved', reject_reason='', updated_at=? WHERE id=?", System.currentTimeMillis(), id);
        boolean withCreatives = r.body.length == 0 || AdHttp.body(r).optBoolean("approve_creatives", true);
        if (withCreatives) {
            db.run("UPDATE creatives SET status='approved', reject_reason='' WHERE campaign_id=? AND status='pending' AND (type<>'banner' OR image_file<>'')", id);
        }
        db.audit(admin.id, "campaign.approve", "#" + id);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res rejectCampaign(AdHttp.Req r, AdSecurity.User admin, long id) {
        JSONObject c = campaignRow(admin, id);
        if (!"pending".equals(c.optString("status"))) throw new AdHttp.HttpError(409, "Only pending campaigns can be rejected");
        String reason = AdHttp.str(AdHttp.body(r), "reason", 1, 300);
        db.run("UPDATE campaigns SET status='rejected', reject_reason=?, updated_at=? WHERE id=?", reason, System.currentTimeMillis(), id);
        db.audit(admin.id, "campaign.reject", "#" + id);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res setCampaignStatus(AdSecurity.User u, long id, String from, String to) {
        JSONObject c = campaignRow(u, id);
        if (!from.equals(c.optString("status"))) throw new AdHttp.HttpError(409, "Campaign must be " + from + " to become " + to);
        db.run("UPDATE campaigns SET status=?, updated_at=? WHERE id=?", to, System.currentTimeMillis(), id);
        return AdHttp.ok(obj("ok", true));
    }

    // ================================================================ creatives

    private JSONObject creativeFor(AdSecurity.User u, long id) {
        JSONObject cr = db.one("SELECT k.*, c.advertiser_id, c.status campaign_status FROM creatives k JOIN campaigns c ON c.id=k.campaign_id WHERE k.id=?", id);
        if (cr == null || (!u.isAdmin() && cr.optLong("advertiser_id") != u.id)) throw notFound("Creative not found");
        return cr;
    }

    private AdHttp.Res listCreatives(AdHttp.Req r, AdSecurity.User u) {
        long campaign = idParam(r, "campaign_id");
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT k.id, k.campaign_id, c.name campaign_name, k.type, k.title, k.body, k.cta, k.click_url,"
                + " k.width, k.height, k.status, k.reject_reason, (k.image_file<>'') has_image, k.created_at"
                + " FROM creatives k JOIN campaigns c ON c.id=k.campaign_id WHERE 1=1");
        if (!u.isAdmin()) { sql.append(" AND c.advertiser_id=?"); args.add(u.id); }
        if (campaign > 0) { sql.append(" AND k.campaign_id=?"); args.add(campaign); }
        String status = r.param("status");
        if (status != null && status.matches("pending|approved|rejected")) { sql.append(" AND k.status=?"); args.add(status); }
        return AdHttp.ok(db.query(sql.append(" ORDER BY k.id DESC LIMIT 500").toString(), args.toArray()));
    }

    /** Validates text fields for a creative of the given type; returns {title, body, cta, click_url}. */
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
        if (db.scalar("SELECT COUNT(*) FROM creatives WHERE campaign_id=?", 0, campaign) >= 30) throw bad("Creative limit reached (30 per campaign)");
        String type = b.optString("type", "");
        if (!type.matches("banner|native|text")) throw bad("type must be banner, native or text");
        String[] t = creativeText(b, type);
        long w = 0, h = 0;
        if (type.equals("banner")) {
            w = AdHttp.num(b, "width", 1, 2000, 0);
            h = AdHttp.num(b, "height", 1, 2000, 0);
            if (w == 0 || h == 0) throw bad("Banner creatives need a width and height");
        }
        long id = db.insert("INSERT INTO creatives(campaign_id,type,title,body,cta,click_url,width,height,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                campaign, type, t[0], t[1], t[2], t[3], w, h, System.currentTimeMillis());
        return AdHttp.ok(obj("id", id));
    }

    private AdHttp.Res patchCreative(AdHttp.Req r, AdSecurity.User u, long id) {
        JSONObject cr = creativeFor(u, id);
        JSONObject merged = AdHttp.body(r);
        for (String k : new String[]{"title", "body", "cta", "click_url"}) {
            if (!merged.has(k)) AdHttp.put(merged, k, cr.optString(k));
        }
        String[] t = creativeText(merged, cr.optString("type"));
        // Any edit sends the creative back to review so approved ads cannot be swapped for something else.
        db.run("UPDATE creatives SET title=?, body=?, cta=?, click_url=?, status='pending', reject_reason='' WHERE id=?", t[0], t[1], t[2], t[3], id);
        return AdHttp.ok(obj("ok", true));
    }

    private AdHttp.Res deleteCreative(AdSecurity.User u, long id) {
        JSONObject cr = creativeFor(u, id);
        String f = cr.optString("image_file");
        db.run("DELETE FROM creatives WHERE id=?", id);
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
        o.inJustDecodeBounds = true; // reads the header only: no pixel memory is allocated
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
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("could not store image"); }
        String old = cr.optString("image_file");
        String mime = ext.equals("jpg") ? "image/jpeg" : "image/" + ext;
        db.run("UPDATE creatives SET image_file=?, image_mime=?, width=?, height=?, status='pending', reject_reason='' WHERE id=?", name, mime, w, h, id);
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
            db.run("UPDATE creatives SET status='approved', reject_reason='' WHERE id=?", id);
        } else {
            db.run("UPDATE creatives SET status='rejected', reject_reason=? WHERE id=?", AdHttp.str(AdHttp.body(r), "reason", 1, 300), id);
        }
        db.audit(admin.id, approve ? "creative.approve" : "creative.reject", "#" + id);
        return AdHttp.ok(obj("ok", true));
    }

    // ================================================================ admin

    private AdHttp.Res overview() {
        long day = AdDb.today();
        JSONObject today = db.one("SELECT COALESCE(SUM(served),0) served, COALESCE(SUM(imps),0) imps, COALESCE(SUM(clicks),0) clicks,"
                + " COALESCE(SUM(invalid_imps+invalid_clicks),0) invalid, COALESCE(SUM(spend_micros),0) spend, COALESCE(SUM(pub_micros),0) earnings"
                + " FROM stats_daily WHERE day=?", day);
        JSONObject all = db.one("SELECT COALESCE(SUM(imps),0) imps, COALESCE(SUM(clicks),0) clicks, COALESCE(SUM(spend_micros),0) spend,"
                + " COALESCE(SUM(pub_micros),0) earnings FROM stats_daily");
        return AdHttp.ok(obj("today", today, "all_time", all,
                "pending_campaigns", db.scalar("SELECT COUNT(*) FROM campaigns WHERE status='pending'", 0),
                "pending_creatives", db.scalar("SELECT COUNT(*) FROM creatives WHERE status='pending' AND campaign_id IN (SELECT id FROM campaigns WHERE status<>'draft')", 0),
                "pending_sites", db.scalar("SELECT COUNT(*) FROM sites WHERE status='pending'", 0),
                "flagged_sites", db.scalar("SELECT COUNT(*) FROM sites WHERE flagged=1 AND status<>'removed'", 0),
                "advertisers", db.scalar("SELECT COUNT(*) FROM users WHERE role='advertiser'", 0),
                "publishers", db.scalar("SELECT COUNT(*) FROM users WHERE role='publisher'", 0)));
    }

    private AdHttp.Res fraud() {
        JSONArray flagged = db.query("SELECT s.id, s.name, s.domain, s.flag_reason, u.email owner_email FROM sites s JOIN users u ON u.id=s.owner_id"
                + " WHERE s.flagged=1 AND s.status<>'removed' ORDER BY s.id DESC");
        JSONArray reasons = db.query("SELECT reason, SUM(n) n FROM fraud_log WHERE day>=? GROUP BY reason ORDER BY n DESC", AdDb.today() - 6);
        JSONArray bySite = db.query("SELECT st.id, st.domain, SUM(s.invalid_imps) invalid_imps, SUM(s.invalid_clicks) invalid_clicks,"
                + " SUM(s.imps) imps, SUM(s.clicks) clicks FROM stats_daily s JOIN sites st ON st.id=s.site_id WHERE s.day>=?"
                + " GROUP BY st.id HAVING SUM(s.invalid_imps)+SUM(s.invalid_clicks)>0 ORDER BY SUM(s.invalid_imps)+SUM(s.invalid_clicks) DESC LIMIT 25", AdDb.today() - 6);
        return AdHttp.ok(obj("flagged_sites", flagged, "reasons_7d", reasons, "invalid_by_site_7d", bySite));
    }

    private static final String[] SETTING_KEYS = {"rev_share_pct", "currency", "public_base_url", "allow_signup", "trust_proxy",
            "fraud_min_click_ms", "fraud_repeat_clicks_hour", "fraud_ip_clicks_min", "fraud_max_imps_visitor_day", "retention_days"};

    private JSONObject settingsJson() {
        JSONObject o = new JSONObject();
        for (String k : SETTING_KEYS) AdHttp.put(o, k, db.setting(k, ""));
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
                case "rev_share_pct": if (!v.matches("[0-9]{1,3}") || Integer.parseInt(v) > 100) throw bad("rev_share_pct must be 0-100"); break;
                case "currency": if (!v.matches("[A-Z]{3}")) throw bad("currency must be a 3-letter code like USD"); break;
                case "public_base_url": if (!v.isEmpty() && (!AdSecurity.isHttpUrl(v) || v.endsWith("/"))) throw bad("public_base_url must be http(s)://host[:port] without a trailing slash"); break;
                case "allow_signup": case "trust_proxy": if (!v.equals("0") && !v.equals("1")) throw bad(k + " must be 0 or 1"); break;
                case "fraud_min_click_ms": if (!v.matches("[0-9]{3,5}") || Integer.parseInt(v) < 100 || Integer.parseInt(v) > 10000) throw bad("fraud_min_click_ms must be 100-10000"); break;
                case "fraud_repeat_clicks_hour": case "fraud_ip_clicks_min":
                    if (!v.matches("[0-9]{1,3}") || Integer.parseInt(v) < 1 || Integer.parseInt(v) > 100) throw bad(k + " must be 1-100"); break;
                case "fraud_max_imps_visitor_day": if (!v.matches("[0-9]{1,4}") || Integer.parseInt(v) < 1) throw bad("fraud_max_imps_visitor_day must be at least 1"); break;
                case "retention_days": if (!v.matches("[0-9]{1,3}") || Integer.parseInt(v) < 7 || Integer.parseInt(v) > 365) throw bad("retention_days must be 7-365"); break;
                default: break;
            }
            updates.add(new String[]{k, v});
        }
        for (String[] kv : updates) db.setSetting(kv[0], kv[1]);
        db.audit(admin.id, "settings.update", String.valueOf(updates.size()) + " values");
        return AdHttp.ok(settingsJson());
    }
}
