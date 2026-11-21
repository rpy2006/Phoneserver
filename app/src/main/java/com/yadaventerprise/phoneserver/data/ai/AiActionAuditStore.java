package com.yadaventerprise.phoneserver.data.ai;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AiActionAuditStore {
    private static final int MAX = 500;
    private static volatile AiActionAuditStore instance;
    private final File file;
    private final Object lock = new Object();
    private List<JSONObject> cached;

    private AiActionAuditStore(File file) { this.file = file; }

    public static AiActionAuditStore get(Context context) {
        if (instance == null) {
            synchronized (AiActionAuditStore.class) {
                if (instance == null) instance = new AiActionAuditStore(new File(SecurityPaths.securityDir(context), "ai-actions.json"));
            }
        }
        return instance;
    }

    public void append(String actor, String action, String target, boolean confirmed, boolean success, String detail) {
        synchronized (lock) {
            ensureLoaded();
            try {
                JSONObject o = new JSONObject();
                o.put("time", System.currentTimeMillis());
                o.put("actor", actor == null ? "admin" : actor);
                o.put("action", action);
                o.put("target", target == null ? "" : target);
                o.put("confirmed", confirmed);
                o.put("success", success);
                o.put("detail", detail == null ? "" : detail);
                cached.add(0, o);
                while (cached.size() > MAX) cached.remove(cached.size() - 1);
                persist();
            } catch (Exception ignored) {}
        }
    }

    public List<JSONObject> list(int limit) {
        synchronized (lock) {
            ensureLoaded();
            int n = Math.max(1, Math.min(limit <= 0 ? 100 : limit, cached.size()));
            List<JSONObject> out = new ArrayList<>();
            for (int i = 0; i < n; i++) out.add(cached.get(i));
            return out;
        }
    }

    private void ensureLoaded() {
        if (cached != null) return; cached = new ArrayList<>();
        try { JSONArray arr = JsonStorage.readJsonArray(file); for (int i=0;i<arr.length();i++){ JSONObject o=arr.optJSONObject(i); if(o!=null) cached.add(o);} } catch (Exception ignored) {}
    }
    private void persist() {
        try { JSONArray arr=new JSONArray(); for (JSONObject o:cached) arr.put(o); JsonStorage.writeJsonArrayAtomic(file,arr,true); SecurityPaths.restrict(file);} catch (Exception ignored) {}
    }
}
