package com.yadaventerprise.phoneserver.data.database;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * JSON-based replacement for SystemDatabase operations.
 */
public final class SystemStore {
    private static final Object LOCK = new Object();
    
    private SystemStore() {}
    
    public static JSONObject getData(Context context) {
        synchronized (LOCK) {
            try {
                JsonMetaStore store = JsonMetaStore.get(context);
                JSONObject data = store.getData();
                if (data == null || data.length() == 0) {
                    data = new JSONObject();
                    data.put("projects", new JSONArray());
                    data.put("project_databases", new JSONArray());
                    data.put("files", new JSONArray());
                    data.put("folders", new JSONArray());
                    data.put("api_tokens", new JSONArray());
                    data.put("backups", new JSONArray());
                    data.put("operations", new JSONArray());
                    store.updateData(data);
                } else {
                    ensureDefaults(data);
                }
                return data;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    private static void ensureDefaults(JSONObject data) throws JSONException {
        if (!data.has("projects")) data.put("projects", new JSONArray());
        if (!data.has("project_databases")) data.put("project_databases", new JSONArray());
        if (!data.has("files")) data.put("files", new JSONArray());
        if (!data.has("folders")) data.put("folders", new JSONArray());
        if (!data.has("api_tokens")) data.put("api_tokens", new JSONArray());
        if (!data.has("backups")) data.put("backups", new JSONArray());
        if (!data.has("operations")) data.put("operations", new JSONArray());
    }
    
    public static void saveData(Context context, JSONObject data) {
        synchronized (LOCK) {
            try {
                ensureDefaults(data);
                JsonMetaStore.get(context).updateData(data);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    public static JSONArray getArray(JSONObject data, String key) {
        try {
            if (data.has(key)) {
                return data.getJSONArray(key);
            }
            JSONArray arr = new JSONArray();
            data.put(key, arr);
            return arr;
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
