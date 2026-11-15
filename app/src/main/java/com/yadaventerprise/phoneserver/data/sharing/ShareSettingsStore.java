package com.yadaventerprise.phoneserver.data.sharing;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;

public final class ShareSettingsStore {

    private static volatile ShareSettingsStore instance;

    private final File file;
    private final Object lock = new Object();
    private ShareSettings cached;

    public ShareSettingsStore(File file) {
        this.file = file;
    }

    public static ShareSettingsStore get(Context context) {
        if (instance == null) {
            synchronized (ShareSettingsStore.class) {
                if (instance == null) instance = new ShareSettingsStore(SharePaths.settings(context));
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public ShareSettings get() {
        synchronized (lock) {
            if (cached != null) return cached.copy();
            try {
                cached = ShareSettings.fromJson(JsonStorage.readJson(file));
            } catch (Exception e) {
                Log.w("ShareSettingsStore", "could not read sharing settings", e);
                cached = new ShareSettings();
            }
            return cached.copy();
        }
    }

    public void save(ShareSettings settings) {
        if (settings == null) return;
        ShareSettings clean = settings.copy();
        clean.defaultExpiryMinutes = Math.max(0, Math.min(clean.defaultExpiryMinutes, 5_256_000));
        clean.defaultMaxDownloads = Math.max(0, Math.min(clean.defaultMaxDownloads, 1_000_000));
        clean.defaultPermission = ShareLink.PERMISSION_VIEW.equals(clean.defaultPermission)
                ? ShareLink.PERMISSION_VIEW : ShareLink.PERMISSION_DOWNLOAD;
        synchronized (lock) {
            cached = clean;
            try {
                JsonStorage.writeJsonAtomic(file, clean.toJson(), true);
                SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.e("ShareSettingsStore", "could not save sharing settings", e);
            }
        }
    }
}
