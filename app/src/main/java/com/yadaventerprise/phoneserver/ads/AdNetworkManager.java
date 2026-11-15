package com.yadaventerprise.phoneserver.ads;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/**
 * Entry point for the Custom Ads Network Manager. It is opt-in (disabled until switched on in Ad Network settings)
 * and fully separate from the existing PhoneServer HTTP server: its own port, its own files.
 */
public final class AdNetworkManager {
    public static final int PORT = 8090;
    private static final String PREFS = "adnet_prefs";

    private static AdHttpServer server;
    private static Thread sweeper;

    private AdNetworkManager() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
    }

    public static synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public static void startIfEnabled(Context c) {
        if (isEnabled(c)) start(c);
    }

    public static synchronized String start(Context c) {
        if (isRunning()) return null;
        Context app = c.getApplicationContext();
        try {
            AdDb db = AdDb.get(app);
            db.open();
            setupCode(app);
            AdHttpServer s = new AdHttpServer(app, db, PORT);
            s.start();
            server = s;
            startSweeper(app, db);
            return null;
        } catch (IOException e) {
            return "Could not start on port " + PORT + ": " + e.getMessage();
        } catch (RuntimeException e) {
            return "Could not start: " + e.getMessage();
        }
    }

    public static synchronized void stop() {
        if (server != null) server.stop();
        server = null;
        sweeper = null;
    }

    public static synchronized String setupCode(Context c) {
        SharedPreferences p = prefs(c);
        String code = p.getString("setup_code", "");
        if (code.isEmpty()) {
            code = AdSecurity.randomHex(4).toUpperCase(java.util.Locale.ROOT);
            p.edit().putString("setup_code", code).apply();
        }
        return code;
    }

    public static synchronized void clearSetupCode(Context c) {
        prefs(c).edit().putString("setup_code", "").apply();
    }

    public static synchronized String newSetupCode(Context c) {
        prefs(c).edit().putString("setup_code", "").apply();
        return setupCode(c);
    }

    public static boolean needsSetup(Context c) {
        return AdDb.get(c).count(AdDb.USERS) == 0;
    }

    private static void startSweeper(final Context app, final AdDb db) {
        final Thread t = new Thread(() -> {
            long tick = 0;
            while (sweeper == Thread.currentThread()) {
                try {
                    long now = System.currentTimeMillis();
                    db.deleteWhere(AdDb.SESSIONS, s -> s.optLong("expires_at") < now);
                    AdRateLimiter.sweep();
                    if (tick % 10 == 0) AdFraud.reviewSites(db);
                    if (tick % 60 == 0) {
                        long keep = db.settingLong("retention_days", 30) * 86_400_000L;
                        db.deleteWhere(AdDb.EVENTS, e -> e.optLong("ts") < now - keep);
                        db.deleteWhere(AdDb.FRAUD_LOG, f -> f.optLong("day") < AdDb.today() - 90);
                        db.updateWhere(AdDb.CAMPAIGNS, c -> "approved".equals(c.optString("status"))
                                        || "paused".equals(c.optString("status")),
                                c -> {
                                    if (!c.isNull("end_at") && c.optLong("end_at") <= now) AdHttp.put(c, "status", "ended");
                                });
                        removeOrphanFiles(app, db);
                        AdSummaryMailer.maybeSend(db);
                        AdExperimentEngine.finishExpired(db);
                    }
                    tick++;
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    android.util.Log.w("AdNet", "sweeper", e);
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "AdNet-Sweeper");
        sweeper = t;
        t.start();
    }

    private static void removeOrphanFiles(Context app, AdDb db) {
        File[] files = AdDelivery.creativesDir(app).listFiles();
        if (files == null) return;
        Set<String> used = new HashSet<>();
        for (JSONObject row : db.list(AdDb.CREATIVES, c -> !c.optString("image_file").isEmpty())) {
            used.add(row.optString("image_file"));
        }
        long cutoff = System.currentTimeMillis() - 3600_000L;
        for (File f : files) {
            if (!used.contains(f.getName()) && f.lastModified() < cutoff) f.delete();
        }
    }
}
