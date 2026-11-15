package com.yadaventerprise.phoneserver.data.servers;

import android.content.Context;
import android.content.SharedPreferences;
import com.yadaventerprise.phoneserver.model.ServerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stores the user's list of saved remote servers as a small JSON array in SharedPreferences. */
public class ServerProfileStore {

    private static final String PREFS_NAME = "server_profiles";
    private static final String KEY_LIST = "profiles_json";

    public static List<ServerProfile> getAll(Context context) {
        List<ServerProfile> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_LIST, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ServerProfile p = new ServerProfile();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.ip = o.optString("ip");
                p.port = o.optInt("port", 8080);
                p.username = o.optString("username", "");
                p.password = o.optString("password", "");
                p.description = o.optString("description", "");
                p.scheme = o.optString("scheme", "http");
                p.token = o.optString("token", "");
                p.fingerprint = o.optString("fingerprint", "");
                result.add(p);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static ServerProfile getById(Context context, String id) {
        if (id == null) return null;
        for (ServerProfile p : getAll(context)) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    public static void save(Context context, ServerProfile profile) {
        List<ServerProfile> all = getAll(context);
        if (profile.id == null || profile.id.isEmpty()) {
            profile.id = UUID.randomUUID().toString();
            all.add(profile);
        } else {
            boolean found = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id.equals(profile.id)) {
                    all.set(i, profile);
                    found = true;
                    break;
                }
            }
            if (!found) all.add(profile);
        }
        writeAll(context, all);
    }

    public static void delete(Context context, String id) {
        List<ServerProfile> all = getAll(context);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        writeAll(context, all);
    }

    private static void writeAll(Context context, List<ServerProfile> profiles) {
        try {
            JSONArray arr = new JSONArray();
            for (ServerProfile p : profiles) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("ip", p.ip);
                o.put("port", p.port);
                o.put("username", p.username == null ? "" : p.username);
                o.put("password", p.password == null ? "" : p.password);
                o.put("description", p.description == null ? "" : p.description);
                o.put("scheme", p.scheme == null ? "http" : p.scheme);
                o.put("token", p.token == null ? "" : p.token);
                o.put("fingerprint", p.fingerprint == null ? "" : p.fingerprint);
                arr.put(o);
            }
            prefs(context).edit().putString(KEY_LIST, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
