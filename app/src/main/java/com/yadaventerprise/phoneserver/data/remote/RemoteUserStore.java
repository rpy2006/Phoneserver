package com.yadaventerprise.phoneserver.data.remote;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.sharing.SharePassword;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RemoteUserStore {
    private static volatile RemoteUserStore instance;

    private final File file;
    private final Object lock = new Object();
    private final Map<String, RemoteUser> users = new LinkedHashMap<>();
    private volatile boolean loaded;

    private RemoteUserStore(File file) {
        this.file = file;
    }

    public static RemoteUserStore get(Context context) {
        if (instance == null) {
            synchronized (RemoteUserStore.class) {
                if (instance == null) {
                    instance = new RemoteUserStore(RemotePaths.file(context, "users.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public List<RemoteUser> all() {
        synchronized (lock) {
            ensureLoaded();
            List<RemoteUser> result = new ArrayList<>();
            for (RemoteUser user : users.values()) result.add(user.copy());
            return result;
        }
    }

    public RemoteUser get(String id) {
        synchronized (lock) {
            ensureLoaded();
            RemoteUser user = users.get(id);
            return user == null ? null : user.copy();
        }
    }

    public RemoteUser findByUsername(String username) {
        String normalized = RemoteUser.normalizeUsername(username);
        synchronized (lock) {
            ensureLoaded();
            for (RemoteUser user : users.values()) {
                if (user.username.equals(normalized)) return user.copy();
            }
            return null;
        }
    }

    public RemoteUser create(RemoteUser draft, String password) {
        if (draft == null || !RemoteUser.isValidUsername(draft.username) || password == null
                || password.length() < 10) return null;
        synchronized (lock) {
            ensureLoaded();
            for (RemoteUser existing : users.values()) {
                if (existing.username.equals(draft.username)) return null;
            }
            long now = System.currentTimeMillis();
            RemoteUser user = draft.copy();
            user.id = UUID.randomUUID().toString();
            user.createdAt = now;
            user.updatedAt = now;
            user.failedAttempts = 0;
            user.lockedUntil = 0;
            user.passwordHash = SharePassword.hash(password);
            users.put(user.id, user);
            save();
            return user.copy();
        }
    }

    public RemoteUser save(RemoteUser user) {
        if (user == null || user.id.isEmpty()) return null;
        synchronized (lock) {
            ensureLoaded();
            RemoteUser copy = user.copy();
            copy.updatedAt = System.currentTimeMillis();
            users.put(copy.id, copy);
            save();
            return copy.copy();
        }
    }

    public void delete(String id) {
        synchronized (lock) {
            ensureLoaded();
            if (users.remove(id) != null) save();
        }
    }

    public RemoteUser ensureOwner(Context context) {
        synchronized (lock) {
            ensureLoaded();
            for (RemoteUser user : users.values()) {
                if (RemoteRole.OWNER.equals(user.role)) return user.copy();
            }
        }
        String password = ContentStore.getPassword(context);
        if (password == null || password.isEmpty()) return null;
        RemoteUser owner = new RemoteUser();
        owner.username = "owner";
        owner.displayName = "Owner";
        owner.role = RemoteRole.OWNER;
        return create(owner, password);
    }

    public void clear() {
        synchronized (lock) {
            users.clear();
            loaded = true;
            save();
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject root = JsonStorage.readJson(file);
            JSONArray array = root.optJSONArray("users");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                RemoteUser user = RemoteUser.fromJson(array.optJSONObject(i));
                if (!user.id.isEmpty()) users.put(user.id, user);
            }
        } catch (Exception e) {
            Log.w("RemoteUserStore", "could not read remote users", e);
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (RemoteUser user : users.values()) array.put(user.toJson());
            JSONObject root = new JSONObject();
            RemoteJson.put(root, "version", 1);
            RemoteJson.put(root, "users", array);
            JsonStorage.writeJsonAtomic(file, root, true);
            com.yadaventerprise.phoneserver.data.security.SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("RemoteUserStore", "could not save remote users", e);
        }
    }
}
