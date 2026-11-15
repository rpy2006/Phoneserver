package com.yadaventerprise.phoneserver.network.pairing;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import com.yadaventerprise.phoneserver.security.TokenUtil;
import java.io.File;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

public final class PairingStore {

    public static final int MAX_ATTEMPTS = 8;
    public static final long DEFAULT_TTL_MS = 10L * 60L * 1000L;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile PairingStore instance;

    private final File file;
    private final Object lock = new Object();
    private final Map<String, PairingCode> codes = new LinkedHashMap<>();
    private volatile boolean loaded;

    private PairingStore(File file) {
        this.file = file;
    }

    public static PairingStore get(Context context) {
        if (instance == null) {
            synchronized (PairingStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "pairing");
                    if (!dir.exists()) dir.mkdirs();
                    SecurityPaths.restrict(dir);
                    instance = new PairingStore(new File(dir, "pairing-codes.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public static String newCode() {
        StringBuilder raw = new StringBuilder(8);
        for (int i = 0; i < 8; i++) raw.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return raw.substring(0, 4) + "-" + raw.substring(4);
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return value.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.US);
    }

    public String create(String name, long ttlMillis) {
        String plain = newCode();
        long now = System.currentTimeMillis();
        PairingCode code = new PairingCode();
        code.id = TokenUtil.hash(plain + now + RANDOM.nextLong()).substring(0, 16);
        code.hash = TokenUtil.hash(normalize(plain));
        code.name = name == null ? "" : name.trim();
        code.createdAt = now;
        code.expiresAt = now + Math.max(60_000L, ttlMillis);
        synchronized (lock) {
            ensureLoaded();
            prune(now);
            codes.put(code.id, code);
            save();
        }
        return plain;
    }

    public PairingCode redeem(String supplied) {
        String normalized = normalize(supplied);
        if (normalized.length() != 8) return null;
        long now = System.currentTimeMillis();
        synchronized (lock) {
            ensureLoaded();
            prune(now);
            PairingCode match = null;
            for (PairingCode code : codes.values()) {
                if (TokenUtil.constantTimeEquals(code.hash, TokenUtil.hash(normalized))) {
                    match = code;
                    break;
                }
            }
            if (match == null) {
                save();
                return null;
            }
            match.attempts++;
            if (!match.isActive(now)) {
                save();
                return null;
            }
            match.used = true;
            save();
            return match;
        }
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, PairingCode>> iterator = codes.entrySet().iterator();
        while (iterator.hasNext()) {
            PairingCode code = iterator.next().getValue();
            if (code.expiresAt < now - 24L * 60L * 60L * 1000L) iterator.remove();
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject json = JsonStorage.readJson(file);
            JSONObject map = json.optJSONObject("codes");
            if (map == null) return;
            Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = map.optJSONObject(key);
                if (item != null) codes.put(key, PairingCode.fromJson(key, item));
            }
        } catch (Exception e) {
            Log.w("PairingStore", "could not read pairing codes", e);
        }
    }

    private void save() {
        try {
            JSONObject map = new JSONObject();
            for (Map.Entry<String, PairingCode> entry : codes.entrySet()) {
                map.put(entry.getKey(), entry.getValue().toJson());
            }
            JSONObject json = new JSONObject();
            json.put("codes", map);
            JsonStorage.writeJsonAtomic(file, json, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("PairingStore", "could not save pairing codes", e);
        }
    }
}
