package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Entry point for the Custom Ads Network Manager. It is opt-in (disabled until switched on in Ad Network settings)
 * and fully separate from the existing PhoneServer HTTP server: its own port, its own database, its own files.
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

    /** Called from ServerService: starts only if the owner has enabled the ad network. */
    public static void startIfEnabled(Context c) {
        if (isEnabled(c)) start(c);
    }

    /** Returns null on success, otherwise a short reason. */
    public static synchronized String start(Context c) {
        if (isRunning()) return null;
        Context app = c.getApplicationContext();
        try {
            AdDb db = AdDb.get(app);
            db.getWritableDatabase(); // creates or opens the schema
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

    /** One-time code, shown only on the phone, required to create the first administrator. */
    public static synchronized String setupCode(Context c) {
        SharedPreferences p = prefs(c);
        String code = p.getString("setup_code", "");
        if (code.isEmpty()) {
            code = AdSecurity.randomHex(4).toUpperCase(java.util.Locale.ROOT); // 8 characters
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
        return AdDb.get(c).scalar("SELECT COUNT(*) FROM users", 0) == 0;
    }

    /** Housekeeping: expired sessions, fraud review, event pruning, campaign end dates. */
    private static void startSweeper(final Context app, final AdDb db) {
        final Thread t = new Thread(() -> {
            long tick = 0;
            while (sweeper == Thread.currentThread()) {
                try {
                    long now = System.currentTimeMillis();
                    db.run("DELETE FROM sessions WHERE expires_at<?", now);
                    AdRateLimiter.sweep();
                    if (tick % 10 == 0) AdFraud.reviewSites(db);
                    if (tick % 60 == 0) {
                        long keep = db.settingLong("retention_days", 30) * 86_400_000L;
                        db.run("DELETE FROM events WHERE ts<?", now - keep);
                        db.run("DELETE FROM fraud_log WHERE day<?", AdDb.today() - 90);
                        db.run("UPDATE campaigns SET status='ended' WHERE status IN('approved','paused') AND end_at IS NOT NULL AND end_at<=?", now);
                        removeOrphanFiles(app, db);
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

    /** Deletes creative image files no database row refers to any more. */
    private static void removeOrphanFiles(Context app, AdDb db) {
        File[] files = AdDelivery.creativesDir(app).listFiles();
        if (files == null) return;
        JSONArray rows = db.query("SELECT image_file FROM creatives WHERE image_file<>''");
        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) used.add(rows.optJSONObject(i).optString("image_file"));
        long cutoff = System.currentTimeMillis() - 3600_000L; // ignore files still being written
        for (File f : files) {
            if (!used.contains(f.getName()) && f.lastModified() < cutoff) f.delete();
        }
    }
}
