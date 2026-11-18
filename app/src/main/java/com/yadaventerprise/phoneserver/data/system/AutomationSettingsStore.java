package com.yadaventerprise.phoneserver.data.system;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;

public final class AutomationSettingsStore {

    private static volatile AutomationSettingsStore instance;
    private final File file;
    private final Object lock = new Object();
    private AutomationSettings cached;

    private AutomationSettingsStore(File file) {
        this.file = file;
    }

    public static AutomationSettingsStore get(Context context) {
        if (instance == null) {
            synchronized (AutomationSettingsStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "system");
                    if (!dir.exists()) dir.mkdirs();
                    SecurityPaths.restrict(dir);
                    instance = new AutomationSettingsStore(new File(dir, "automation-settings.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public AutomationSettings get() {
        synchronized (lock) {
            ensureLoaded();
            return AutomationSettings.copy(cached);
        }
    }

    public void save(AutomationSettings settings) {
        if (settings == null) return;
        synchronized (lock) {
            cached = AutomationSettings.copy(settings);
            persist();
        }
    }

    public void update(AutomationSettings settings) {
        save(settings);
    }

    private void ensureLoaded() {
        if (cached != null) return;
        try {
            cached = AutomationSettings.fromJson(JsonStorage.readJson(file));
        } catch (Exception e) {
            Log.w("AutomationSettingsStore", "could not read automation settings", e);
            cached = new AutomationSettings();
        }
    }

    private void persist() {
        try {
            JsonStorage.writeJsonAtomic(file, cached.toJson());
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("AutomationSettingsStore", "could not save automation settings", e);
        }
    }
}
