package com.yadaventerprise.phoneserver.data.webhooks;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WebhookReplayStore {

    private static final long REPLAY_SKEW_MS = 5L * 60L * 1000L;
    private static final long RETENTION_MS = 30L * 60L * 1000L;
    private static volatile WebhookReplayStore instance;
    private final File file;
    private final Object lock = new Object();
    private JSONArray cached;

    private WebhookReplayStore(File file) {
        this.file = file;
    }

    public static WebhookReplayStore get(Context context) {
        if (instance == null) {
            synchronized (WebhookReplayStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "webhooks");
                    if (!dir.exists()) dir.mkdirs();
                    SecurityPaths.restrict(dir);
                    instance = new WebhookReplayStore(new File(dir, "replay-cache.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public boolean checkAndRecord(String deliveryId, long timestampMs, long nowMs) {
        if (deliveryId == null || deliveryId.trim().isEmpty()) return false;
        if (timestampMs <= 0) return false;
        long skew = Math.abs(nowMs - timestampMs);
        if (skew > REPLAY_SKEW_MS) return false;
        String id = deliveryId.trim();
        synchronized (lock) {
            ensureLoaded();
            cleanup(nowMs);
            for (int i = 0; i < cached.length(); i++) {
                JSONObject obj = cached.optJSONObject(i);
                if (obj != null && id.equals(obj.optString("id", ""))) {
                    return false;
                }
            }
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("ts", timestampMs);
                obj.put("seenAt", nowMs);
                cached.put(obj);
                persist();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    public void cleanup(long nowMs) {
        synchronized (lock) {
            ensureLoaded();
            long cutoff = nowMs - RETENTION_MS;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < cached.length(); i++) {
                JSONObject obj = cached.optJSONObject(i);
                if (obj == null) continue;
                long ts = obj.optLong("seenAt", obj.optLong("ts", 0));
                if (ts >= cutoff) kept.put(obj);
            }
            if (kept.length() != cached.length()) {
                cached = kept;
                persist();
            }
        }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        try {
            cached = JsonStorage.readJsonArray(file);
        } catch (Exception e) {
            cached = new JSONArray();
        }
        if (cached == null) cached = new JSONArray();
    }

    private void persist() {
        try {
            JsonStorage.writeJsonArrayAtomic(file, cached, false);
            SecurityPaths.restrict(file);
        } catch (Exception ignored) {
        }
    }
}
