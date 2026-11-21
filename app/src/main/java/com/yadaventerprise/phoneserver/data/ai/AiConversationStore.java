package com.yadaventerprise.phoneserver.data.ai;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AiConversationStore {
    private static final int MAX = 200;
    private static volatile AiConversationStore instance;
    private final File file;
    private final Object lock = new Object();
    private List<JSONObject> cached;

    private AiConversationStore(File file) { this.file = file; }

    public static AiConversationStore get(Context context) {
        if (instance == null) {
            synchronized (AiConversationStore.class) {
                if (instance == null) instance = new AiConversationStore(new File(SecurityPaths.securityDir(context), "ai-conversation.json"));
            }
        }
        return instance;
    }

    public static synchronized void reset() { instance = null; }

    public void append(String role, String content) {
        synchronized (lock) {
            ensureLoaded();
            try {
                JSONObject o = new JSONObject();
                o.put("time", System.currentTimeMillis());
                o.put("role", role);
                o.put("content", content);
                cached.add(0, o);
                while (cached.size() > MAX) cached.remove(cached.size() - 1);
                persist();
            } catch (Exception ignored) {}
        }
    }

    public List<JSONObject> list(int limit) {
        synchronized (lock) {
            ensureLoaded();
            int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, cached.size()));
            List<JSONObject> out = new ArrayList<>();
            for (int i = 0; i < n; i++) out.add(cached.get(i));
            return out;
        }
    }

    public void clear() {
        synchronized (lock) { ensureLoaded(); cached.clear(); persist(); }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        cached = new ArrayList<>();
        try {
            JSONArray arr = JsonStorage.readJsonArray(file);
            for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null) cached.add(o); }
        } catch (Exception ignored) {}
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : cached) arr.put(o);
            JsonStorage.writeJsonArrayAtomic(file, arr, true);
            SecurityPaths.restrict(file);
        } catch (Exception ignored) {}
    }
}
