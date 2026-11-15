package com.yadaventerprise.phoneserver.data.security;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import java.io.File;
import org.json.JSONObject;

/** Persists security settings as encrypted app-private JSON. */
public final class SecuritySettingsStore {

    private static final String TAG = "SecuritySettingsStore";
    private static volatile SecuritySettingsStore instance;

    private final File file;
    private final Object lock = new Object();
    private volatile SecuritySettings cached;

    public SecuritySettingsStore(File file) {
        this.file = file;
    }

    public static SecuritySettingsStore get(Context context) {
        if (instance == null) {
            synchronized (SecuritySettingsStore.class) {
                if (instance == null) {
                    instance = new SecuritySettingsStore(SecurityPaths.securitySettings(context));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public SecuritySettings get() {
        SecuritySettings current = cached;
        if (current != null) return current;
        synchronized (lock) {
            if (cached == null) cached = load();
            return cached;
        }
    }

    private SecuritySettings load() {
        try {
            JSONObject json = JsonStorage.readJson(file);
            return SecuritySettings.fromJson(json);
        } catch (Exception e) {
            Log.w(TAG, "could not read security settings; using defaults", e);
            return new SecuritySettings();
        }
    }

    public void save(SecuritySettings settings) {
        if (settings == null) return;
        synchronized (lock) {
            try {
                JsonStorage.writeJsonAtomic(file, settings.toJson(), true);
                SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.e(TAG, "could not save security settings", e);
            }
            cached = settings.copy();
        }
    }
}
