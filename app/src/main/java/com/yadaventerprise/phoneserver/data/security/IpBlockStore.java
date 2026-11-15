package com.yadaventerprise.phoneserver.data.security;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent IP block list used by the HTTP server before routing. */
public final class IpBlockStore {

    private static final String TAG = "IpBlockStore";
    private static volatile IpBlockStore instance;

    private final File file;
    private final Object lock = new Object();
    private final Map<String, Block> blocks = new LinkedHashMap<>();
    private volatile boolean loaded;

    public IpBlockStore(File file) {
        this.file = file;
    }

    public static IpBlockStore get(Context context) {
        if (instance == null) {
            synchronized (IpBlockStore.class) {
                if (instance == null) instance = new IpBlockStore(SecurityPaths.blockedIps(context));
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public boolean isBlocked(String ip) {
        if (ip == null || ip.trim().isEmpty() || isLoopback(ip)) return false;
        String key = normalize(ip);
        synchronized (lock) {
            ensureLoaded();
            Block block = blocks.get(key);
            if (block == null) return false;
            if (block.expiresAt > 0 && block.expiresAt <= System.currentTimeMillis()) {
                blocks.remove(key);
                return false;
            }
            return true;
        }
    }

    public void block(String ip, String reason, long durationMs, String blockedBy) {
        if (ip == null || !isValidIp(ip) || isLoopback(ip)) return;
        String key = normalize(ip);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            ensureLoaded();
            Block block = new Block();
            block.ip = key;
            block.reason = reason == null ? "" : reason.trim();
            block.createdAt = now;
            block.expiresAt = durationMs > 0 ? now + durationMs : 0;
            block.blockedBy = blockedBy == null ? "app" : blockedBy;
            blocks.put(key, block);
            save();
        }
    }

    public void unblock(String ip) {
        if (ip == null) return;
        synchronized (lock) {
            ensureLoaded();
            if (blocks.remove(normalize(ip)) != null) save();
        }
    }

    public List<Block> list() {
        List<Block> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (lock) {
            ensureLoaded();
            for (Block block : blocks.values()) {
                if (block.expiresAt == 0 || block.expiresAt > now) result.add(block);
            }
        }
        Collections.sort(result, new Comparator<Block>() {
            @Override
            public int compare(Block a, Block b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        return result;
    }

    public int countActive() {
        return list().size();
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject json = JsonStorage.readJson(file);
            JSONArray array = json.optJSONArray("blocks");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Block block = Block.fromJson(item);
                if (block.ip == null || block.ip.isEmpty()) continue;
                blocks.put(block.ip, block);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read blocked IPs", e);
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (Block block : blocks.values()) array.put(block.toJson());
            JSONObject json = new JSONObject();
            json.put("blocks", array);
            JsonStorage.writeJsonAtomic(file, json, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e(TAG, "could not save blocked IPs", e);
        }
    }

    private static String normalize(String ip) {
        return ip.trim().toLowerCase(Locale.US);
    }

    public static boolean isValidIp(String ip) {
        if (ip == null) return false;
        String value = ip.trim();
        if (value.isEmpty() || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F') || c == '.' || c == ':' || c == '%';
            if (!allowed) return false;
        }
        return value.contains(".") || value.contains(":");
    }

    public static boolean isLoopback(String ip) {
        if (ip == null) return true;
        String value = ip.trim().toLowerCase(Locale.US);
        return value.equals("localhost") || value.equals("::1") || value.equals("0:0:0:0:0:0:0:1")
                || value.startsWith("127.") || value.startsWith("::ffff:127.");
    }

    public static class Block {
        public String ip = "";
        public String reason = "";
        public long createdAt;
        public long expiresAt;
        public String blockedBy = "app";

        public boolean isPermanent() {
            return expiresAt <= 0;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("ip", ip);
            json.put("reason", reason);
            json.put("createdAt", createdAt);
            json.put("expiresAt", expiresAt);
            json.put("blockedBy", blockedBy);
            return json;
        }

        static Block fromJson(JSONObject json) {
            Block block = new Block();
            block.ip = json.optString("ip", "");
            block.reason = json.optString("reason", "");
            block.createdAt = json.optLong("createdAt", 0);
            block.expiresAt = json.optLong("expiresAt", 0);
            block.blockedBy = json.optString("blockedBy", "app");
            return block;
        }
    }
}
