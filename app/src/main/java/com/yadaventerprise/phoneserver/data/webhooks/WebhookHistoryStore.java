package com.yadaventerprise.phoneserver.data.webhooks;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public final class WebhookHistoryStore {

    private static final int MAX_ENTRIES = 200;
    private static volatile WebhookHistoryStore instance;
    private final File file;
    private final Object lock = new Object();
    private List<WebhookHistoryEntry> cached;

    private WebhookHistoryStore(File file) {
        this.file = file;
    }

    public static WebhookHistoryStore get(Context context) {
        if (instance == null) {
            synchronized (WebhookHistoryStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "webhooks");
                    if (!dir.exists()) dir.mkdirs();
                    SecurityPaths.restrict(dir);
                    instance = new WebhookHistoryStore(new File(dir, "webhook-history.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void append(WebhookHistoryEntry entry) {
        if (entry == null) return;
        synchronized (lock) {
            ensureLoaded();
            cached.add(0, entry);
            while (cached.size() > MAX_ENTRIES) cached.remove(cached.size() - 1);
            persist();
        }
    }

    public List<WebhookHistoryEntry> list() {
        synchronized (lock) {
            ensureLoaded();
            return new ArrayList<>(cached);
        }
    }

    public void clear() {
        synchronized (lock) {
            ensureLoaded();
            cached.clear();
            persist();
        }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        cached = new ArrayList<>();
        try {
            JSONArray array = JsonStorage.readJsonArray(file);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject json = array.optJSONObject(i);
                if (json != null) cached.add(WebhookHistoryEntry.fromJson(json));
            }
        } catch (Exception e) {
            Log.w("WebhookHistoryStore", "could not read webhook history", e);
        }
    }

    private void persist() {
        try {
            JSONArray array = new JSONArray();
            for (WebhookHistoryEntry entry : cached) array.put(entry.toJson());
            JsonStorage.writeJsonArrayAtomic(file, array, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("WebhookHistoryStore", "could not save webhook history", e);
        }
    }
}
