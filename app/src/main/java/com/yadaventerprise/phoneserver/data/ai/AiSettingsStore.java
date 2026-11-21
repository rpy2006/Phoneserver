package com.yadaventerprise.phoneserver.data.ai;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;

public final class AiSettingsStore {
    private static volatile AiSettingsStore instance;
    private final File file;
    private final Object lock = new Object();
    private AiSettings cached;

    private AiSettingsStore(File file) { this.file = file; }

    public static AiSettingsStore get(Context context) {
        if (instance == null) {
            synchronized (AiSettingsStore.class) {
                if (instance == null) instance = new AiSettingsStore(new File(SecurityPaths.securityDir(context), "ai-settings.json"));
            }
        }
        return instance;
    }

    public static synchronized void reset() { instance = null; }

    public AiSettings get() {
        synchronized (lock) {
            if (cached != null) return cached.copy();
            try { cached = AiSettings.fromJson(JsonStorage.readJson(file)); }
            catch (Exception e) { cached = new AiSettings(); }
            return cached.copy();
        }
    }

    public void save(AiSettings settings) {
        synchronized (lock) {
            try {
                settings.updatedAt = System.currentTimeMillis();
                JsonStorage.writeJsonAtomic(file, settings.toJson(), true);
                SecurityPaths.restrict(file);
                cached = settings.copy();
            } catch (Exception ignored) {}
        }
    }
}
