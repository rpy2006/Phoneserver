package com.yadaventerprise.phoneserver.data.remote;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import org.json.JSONObject;

public final class RemoteSettingsStore {
    private static volatile RemoteSettingsStore instance;

    private final File file;
    private final Object lock = new Object();
    private RemoteSettings settings;

    private RemoteSettingsStore(File file) {
        this.file = file;
    }

    public static RemoteSettingsStore get(Context context) {
        if (instance == null) {
            synchronized (RemoteSettingsStore.class) {
                if (instance == null) {
                    instance = new RemoteSettingsStore(RemotePaths.file(context, "settings.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public RemoteSettings get() {
        synchronized (lock) {
            ensureLoaded();
            return settings.copy();
        }
    }

    public RemoteSettings save(RemoteSettings newSettings) {
        if (newSettings == null) return get();
        synchronized (lock) {
            settings = newSettings.copy();
            persist();
            return settings.copy();
        }
    }

    public void clear() {
        synchronized (lock) {
            settings = new RemoteSettings();
            persist();
        }
    }

    private void ensureLoaded() {
        if (settings != null) return;
        settings = new RemoteSettings();
        try {
            JSONObject json = JsonStorage.readJson(file);
            settings = RemoteSettings.fromJson(json);
        } catch (Exception e) {
            Log.w("RemoteSettingsStore", "could not read remote settings", e);
        }
    }

    private void persist() {
        try {
            JSONObject root = settings.toJson();
            RemoteJson.put(root, "version", 1);
            JsonStorage.writeJsonAtomic(file, root, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("RemoteSettingsStore", "could not save remote settings", e);
        }
    }
}
