package com.yadaventerprise.phoneserver.data.webhooks;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public final class WebhookStore {

    private static volatile WebhookStore instance;
    private final File file;
    private final Object lock = new Object();
    private List<WebhookConfig> cached;

    private WebhookStore(File file) {
        this.file = file;
    }

    public static WebhookStore get(Context context) {
        if (instance == null) {
            synchronized (WebhookStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "webhooks");
                    if (!dir.exists()) dir.mkdirs();
                    SecurityPaths.restrict(dir);
                    instance = new WebhookStore(new File(dir, "webhooks.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public List<WebhookConfig> all() {
        synchronized (lock) {
            ensureLoaded();
            List<WebhookConfig> result = new ArrayList<>();
            for (WebhookConfig config : cached) result.add(WebhookConfig.copy(config));
            return result;
        }
    }

    public WebhookConfig get(String id) {
        synchronized (lock) {
            ensureLoaded();
            for (WebhookConfig config : cached) {
                if (config.id.equals(id)) return WebhookConfig.copy(config);
            }
            return null;
        }
    }

    public void save(WebhookConfig config) {
        if (config == null) return;
        synchronized (lock) {
            ensureLoaded();
            boolean found = false;
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(config.id)) {
                    cached.set(i, WebhookConfig.copy(config));
                    found = true;
                    break;
                }
            }
            if (!found) cached.add(WebhookConfig.copy(config));
            persist();
        }
    }

    public void delete(String id) {
        synchronized (lock) {
            ensureLoaded();
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(id)) {
                    cached.remove(i);
                    persist();
                    return;
                }
            }
        }
    }

    public void recordDelivery(String id, boolean success, int statusCode, String error) {
        synchronized (lock) {
            ensureLoaded();
            for (WebhookConfig config : cached) {
                if (!config.id.equals(id)) continue;
                config.lastDeliveryAt = System.currentTimeMillis();
                config.lastStatus = success ? "success" : "failed";
                config.lastError = error == null ? "" : error;
                config.failureCount = success ? 0 : config.failureCount + 1;
                persist();
                return;
            }
        }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        cached = new ArrayList<>();
        try {
            JSONArray array = JsonStorage.readJsonArray(file);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject json = array.optJSONObject(i);
                if (json != null) cached.add(WebhookConfig.fromJson(json));
            }
        } catch (Exception e) {
            Log.w("WebhookStore", "could not read webhooks", e);
        }
    }

    private void persist() {
        try {
            JSONArray array = new JSONArray();
            for (WebhookConfig config : cached) array.put(config.toJson());
            JsonStorage.writeJsonArrayAtomic(file, array, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("WebhookStore", "could not save webhooks", e);
        }
    }
}
