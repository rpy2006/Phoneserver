package com.yadaventerprise.phoneserver.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AdNetworkStorageTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private AdDb db() {
        return new AdDb(new File(tmp.getRoot(), "adnet/adnet.json"));
    }

    private static AdHttp.Req request(String method, String path, JSONObject body) {
        AdHttp.Req r = new AdHttp.Req();
        r.method = method;
        r.path = path;
        if (body != null) r.body = body.toString().getBytes(StandardCharsets.UTF_8);
        return r;
    }

    @Test
    public void jsonStoreIsEncryptedAndPersistsRoundTrip() throws Exception {
        File file = new File(tmp.getRoot(), "adnet/adnet.json");
        AdDb db = new AdDb(file);
        long site = db.insert(AdDb.SITES, AdHttp.obj("owner_id", 7L, "name", "Example", "domain", "example.com",
                "status", "approved", "flagged", 0L, "created_at", 1L));
        db.setSetting("currency", "EUR");
        db.open();

        assertTrue(file.isFile());
        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(raw.startsWith("ENC:"));
        assertFalse(raw.contains("example.com"));

        AdDb reopened = new AdDb(file);
        JSONObject row = reopened.copy(AdDb.SITES, site);
        assertNotNull(row);
        assertEquals("example.com", row.optString("domain"));
        assertEquals("EUR", reopened.setting("currency", "USD"));
        assertTrue(reopened.count(AdDb.SITES) == 1);
    }

    @Test
    public void transactionRollsBackAndDeleteWhereWorks() {
        AdDb db = db();
        db.insert(AdDb.USERS, AdHttp.obj("email", "a@example.com", "role", "admin"));
        assertThrows(IllegalStateException.class, () -> db.transaction(() -> {
            db.insert(AdDb.USERS, AdHttp.obj("email", "b@example.com", "role", "advertiser"));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(1, db.count(AdDb.USERS));
        assertNull(db.find(AdDb.USERS, u -> "b@example.com".equals(u.optString("email"))));

        assertEquals(1, db.deleteWhere(AdDb.USERS, u -> "admin".equals(u.optString("role"))));
        assertEquals(0, db.count(AdDb.USERS));
    }

    @Test
    public void geoDatabaseHandlesRangesPrivateAddressesAndUnknowns() throws Exception {
        AdDb db = db();
        AdGeo.importCsv(db, "test-1", ("1.0.0.0,1.0.0.255,AU\n"
                + "2.0.0.0/24,CA\n8.8.8.0,8.8.8.255,US\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("AU", AdGeo.country(db, "1.0.0.42"));
        assertEquals("CA", AdGeo.country(db, "2.0.0.200"));
        assertEquals("US", AdGeo.country(db, "8.8.8.8"));
        assertEquals("ZZ", AdGeo.country(db, "9.9.9.9"));
        assertEquals("", AdGeo.country(db, "10.1.2.3"));
        assertEquals("test-1", db.setting("geo_db_version", ""));
        assertTrue(AdGeo.status(db).optBoolean("installed"));
    }

    @Test
    public void conversionPixelNeedsAClickAndCountsOnlyValidValue() throws Exception {
        AdDb db = db();
        db.setSetting("conversion_tracking", "1");
        long campaign = db.insert(AdDb.CAMPAIGNS, AdHttp.obj("advertiser_id", 1L, "name", "Launch",
                "conversion_event", "purchase", "conversion_value_micros", 2_500_000L));
        long creative = db.insert(AdDb.CREATIVES, AdHttp.obj("campaign_id", campaign, "title", "Banner", "status", "approved"));
        long placement = db.insert(AdDb.PLACEMENTS, AdHttp.obj("site_id", 3L, "name", "Top"));
        long site = db.insert(AdDb.SITES, AdHttp.obj("owner_id", 2L, "domain", "publisher.test"));
        long now = System.currentTimeMillis();
        db.insert(AdDb.EVENTS, AdHttp.obj("ts", now - 60_000L, "type", "click", "nonce", "n1", "campaign_id", campaign,
                "creative_id", creative, "placement_id", placement, "site_id", site, "visitor", "v1", "country", "US",
                "valid", 1L));
        String token = AdSecurity.signToken(db, "v1|n1|" + campaign + "|" + creative + "|" + placement + "|" + site
                + "|" + now + "|v1");
        AdHttp.Req r = request("GET", "/conv", null);
        r.ip = "8.8.8.8";
        r.q.put("t", token);
        r.q.put("e", "purchase");
        r.q.put("v", "1.25");
        assertEquals(200, AdConversion.pixel(r, db).status);

        JSONObject conversion = db.find(AdDb.CONVERSIONS, c -> "n1".equals(c.optString("click_nonce")));
        assertNotNull(conversion);
        assertEquals("valid", conversion.optString("status"));
        assertEquals("US", conversion.optString("country"));
        assertEquals(1_250_000L, conversion.optLong("value_micros"));
        JSONObject stats = db.find(AdDb.STATS, s -> s.optLong("campaign_id") == campaign);
        assertNotNull(stats);
        assertEquals(1, stats.optLong("conversions"));
        assertEquals(1_250_000L, stats.optLong("conversion_value_micros"));

        AdHttp.Req again = request("GET", "/conv", null);
        again.q.put("t", token);
        again.q.put("e", "purchase");
        assertEquals(200, AdConversion.pixel(again, db).status);
        assertEquals(1, db.count(AdDb.CONVERSIONS));
    }

    @Test
    public void experimentsAndPayoutCsvRespectOwnership() throws Exception {
        AdDb db = db();
        long advertiser = db.insert(AdDb.USERS, AdHttp.obj("email", "adv@example.com", "role", "advertiser"));
        long publisher = db.insert(AdDb.USERS, AdHttp.obj("email", "pub@example.com", "name", "Pub", "role", "publisher"));
        long campaign = db.insert(AdDb.CAMPAIGNS, AdHttp.obj("advertiser_id", advertiser, "name", "Test", "status", "paused"));
        long a = db.insert(AdDb.CREATIVES, AdHttp.obj("campaign_id", campaign, "title", "A"));
        long b = db.insert(AdDb.CREATIVES, AdHttp.obj("campaign_id", campaign, "title", "B"));
        JSONObject body = AdHttp.obj("campaign_id", campaign, "name", "A/B", "variants",
                new org.json.JSONArray().put(AdHttp.obj("creative_id", a, "weight", 50))
                        .put(AdHttp.obj("creative_id", b, "weight", 50)));
        AdHttp.Req create = request("POST", "/api/experiments", body);
        assertEquals(200, AdExperimentEngine.create(create, new AdSecurity.User(db.copy(AdDb.USERS, advertiser)), db).status);
        assertEquals(1, db.count(AdDb.EXPERIMENTS));

        long site = db.insert(AdDb.SITES, AdHttp.obj("owner_id", publisher, "domain", "pub.test", "status", "approved"));
        long placement = db.insert(AdDb.PLACEMENTS, AdHttp.obj("site_id", site, "name", "Side"));
        db.stat(AdDb.today(), campaign, a, placement, site, 0, 100, 0, 10, 0, 0, 2_000_000L);
        AdHttp.Req export = request("GET", "/api/payouts/export", null);
        export.q.put("from", AdReports.dayString(AdDb.today()));
        export.q.put("to", AdReports.dayString(AdDb.today()));
        AdHttp.Res csv = AdPayouts.exportRange(export, new AdSecurity.User(db.copy(AdDb.USERS, publisher)), db);
        String text = new String(csv.body, StandardCharsets.UTF_8);
        assertTrue(text.contains("pub.test"));
        assertTrue(text.contains("TOTAL"));
        assertTrue(text.contains("2.0000"));
    }
    @Test
    public void signedPostbackUsesTheAdvertiserKey() throws Exception {
        AdDb db = db();
        db.setSetting("conversion_tracking", "1");
        long advertiser = db.insert(AdDb.USERS, AdHttp.obj("email", "adv@example.com", "role", "advertiser"));
        long campaign = db.insert(AdDb.CAMPAIGNS, AdHttp.obj("advertiser_id", advertiser, "name", "Signed",
                "conversion_event", "purchase"));
        long creative = db.insert(AdDb.CREATIVES, AdHttp.obj("campaign_id", campaign, "title", "Banner"));
        long placement = db.insert(AdDb.PLACEMENTS, AdHttp.obj("site_id", 3L, "name", "Top"));
        long site = db.insert(AdDb.SITES, AdHttp.obj("owner_id", 2L, "domain", "publisher.test"));
        long now = System.currentTimeMillis();
        db.insert(AdDb.EVENTS, AdHttp.obj("ts", now - 60_000L, "type", "click", "nonce", "n2", "campaign_id", campaign,
                "creative_id", creative, "placement_id", placement, "site_id", site, "visitor", "v2", "country", "CA",
                "valid", 1L));
        String token = AdSecurity.signToken(db, "v1|n2|" + campaign + "|" + creative + "|" + placement + "|" + site
                + "|" + now + "|v2");
        AdSecurity.User user = new AdSecurity.User(db.copy(AdDb.USERS, advertiser));
        JSONObject key = new JSONObject(new String(AdConversion.key(user, db).body, StandardCharsets.UTF_8));
        byte[] raw = new byte[key.optString("secret").length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(key.optString("secret").substring(i * 2, i * 2 + 2), 16);
        }
        String data = token + "|purchase|1.25|order-1";
        String sig = AdSecurity.hex(AdSecurity.hmac(raw, data.getBytes(StandardCharsets.UTF_8)));
        AdHttp.Req r = request("GET", "/postback", null);
        r.q.put("t", token);
        r.q.put("e", "purchase");
        r.q.put("v", "1.25");
        r.q.put("tx", "order-1");
        r.q.put("sig", sig);
        assertEquals(200, AdConversion.postback(r, db).status);
        JSONObject conversion = db.find(AdDb.CONVERSIONS, c -> "order-1".equals(c.optString("transaction_id"))
                || c.optString("dedupe_key").contains("order-1"));
        assertNotNull(conversion);
        assertEquals("valid", conversion.optString("status"));
        assertEquals("CA", conversion.optString("country"));

        AdHttp.Req bad = request("GET", "/postback", null);
        bad.q.put("t", token);
        bad.q.put("e", "purchase");
        bad.q.put("tx", "order-2");
        bad.q.put("sig", "deadbeef");
        assertEquals(403, AdConversion.postback(bad, db).status);
    }

    @Test
    public void adminPayoutListIsNotLimitedToTheAdminId() throws Exception {
        AdDb db = db();
        long admin = db.insert(AdDb.USERS, AdHttp.obj("email", "admin@example.com", "role", "admin"));
        long publisher = db.insert(AdDb.USERS, AdHttp.obj("email", "pub@example.com", "role", "publisher"));
        db.insert(AdDb.PAYOUT_REPORTS, AdHttp.obj("user_id", publisher, "amount_micros", 1_000_000L,
                "from_day", AdDb.today(), "to_day", AdDb.today(), "status", "pending"));
        AdHttp.Res res = AdPayouts.list(request("GET", "/api/payouts", null),
                new AdSecurity.User(db.copy(AdDb.USERS, admin)), db);
        assertEquals(200, res.status);
        assertEquals(1, new org.json.JSONArray(new String(res.body, StandardCharsets.UTF_8)).length());
    }

}
