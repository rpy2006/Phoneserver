package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores the user's custom /api/* endpoint definitions as a JSON array in
 * SharedPreferences - one entry per (method, path) combination.
 */
public class ApiEndpointStore {

    private static final String PREFS_NAME = "api_endpoint_configs";
    private static final String KEY_LIST = "endpoints_json";

    public static List<ApiEndpointConfig> getAll(Context context) {
        List<ApiEndpointConfig> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_LIST, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                result.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** Finds the config that should handle this request, matching path and method exactly. */
    public static ApiEndpointConfig find(Context context, String path, String method) {
        for (ApiEndpointConfig config : getAll(context)) {
            if (config.path.equals(path) && config.method.equalsIgnoreCase(method)) {
                return config;
            }
        }
        return null;
    }

    /** True if some endpoint is defined at this path (for any method) - used to give a clearer 405 vs 404. */
    public static boolean pathExists(Context context, String path) {
        for (ApiEndpointConfig config : getAll(context)) {
            if (config.path.equals(path)) return true;
        }
        return false;
    }

    public static void save(Context context, ApiEndpointConfig config, String originalPath, String originalMethod) {
        List<ApiEndpointConfig> all = getAll(context);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            ApiEndpointConfig existing = all.get(i);
            if (originalPath != null && existing.path.equals(originalPath) && existing.method.equalsIgnoreCase(originalMethod)) {
                all.set(i, config);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(config);
        }
        writeAll(context, all);
    }

    public static void delete(Context context, String path, String method) {
        List<ApiEndpointConfig> all = getAll(context);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).path.equals(path) && all.get(i).method.equalsIgnoreCase(method)) {
                all.remove(i);
                break;
            }
        }
        writeAll(context, all);
    }

    /** Removes every custom endpoint - used by Advanced Settings > Reset Custom API Endpoints. */
    public static void clearAll(Context context) {
        prefs(context).edit().remove(KEY_LIST).apply();
    }

    private static void writeAll(Context context, List<ApiEndpointConfig> configs) {
        try {
            JSONArray arr = new JSONArray();
            for (ApiEndpointConfig config : configs) {
                arr.put(toJson(config));
            }
            prefs(context).edit().putString(KEY_LIST, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject toJson(ApiEndpointConfig config) throws Exception {
        JSONObject o = new JSONObject();
        o.put("path", config.path);
        o.put("method", config.method);
        o.put("body", config.body);
        o.put("headers", keyValueListToJson(config.headers));
        o.put("params", keyValueListToJson(config.params));
        return o;
    }

    private static ApiEndpointConfig fromJson(JSONObject o) throws Exception {
        ApiEndpointConfig config = new ApiEndpointConfig();
        config.path = o.optString("path");
        config.method = o.optString("method", "GET");
        config.body = o.optString("body", "{}");
        config.headers = keyValueListFromJson(o.optJSONArray("headers"));
        config.params = keyValueListFromJson(o.optJSONArray("params"));
        return config;
    }

    private static JSONArray keyValueListToJson(List<KeyValue> list) throws Exception {
        JSONArray arr = new JSONArray();
        for (KeyValue kv : list) {
            JSONObject o = new JSONObject();
            o.put("key", kv.key == null ? "" : kv.key);
            o.put("value", kv.value == null ? "" : kv.value);
            arr.put(o);
        }
        return arr;
    }

    private static List<KeyValue> keyValueListFromJson(JSONArray arr) throws Exception {
        List<KeyValue> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            list.add(new KeyValue(o.optString("key"), o.optString("value")));
        }
        return list;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
