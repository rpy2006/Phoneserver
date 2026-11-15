package com.yadaventerprise.phoneserver.network.tls;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import org.json.JSONObject;

public final class HttpsConfigStore {

    private static final String TAG = "HttpsConfigStore";
    private static volatile HttpsConfigStore instance;

    private final File file;
    private final Object lock = new Object();
    private HttpsConfig cached;

    private HttpsConfigStore(File file) {
        this.file = file;
    }

    public static HttpsConfigStore get(Context context) {
        if (instance == null) {
            synchronized (HttpsConfigStore.class) {
                if (instance == null) instance = new HttpsConfigStore(NetworkPaths.httpsConfig(context));
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public HttpsConfig get() {
        synchronized (lock) {
            if (cached != null) return copy(cached);
            try {
                cached = HttpsConfig.fromJson(JsonStorage.readJson(file));
            } catch (Exception e) {
                Log.w(TAG, "could not read HTTPS config", e);
                cached = new HttpsConfig();
            }
            return copy(cached);
        }
    }

    public void save(HttpsConfig config) {
        if (config == null) return;
        HttpsConfig clean = new HttpsConfig();
        clean.enabled = config.enabled;
        clean.port = HttpsConfig.clampPort(config.port);
        clean.hostname = HttpsConfig.cleanHostname(config.hostname);
        clean.publicUrl = HttpsConfig.cleanUrl(config.publicUrl);
        clean.includeLocalIps = config.includeLocalIps;
        clean.updatedAt = System.currentTimeMillis();
        synchronized (lock) {
            cached = clean;
            try {
                JsonStorage.writeJsonAtomic(file, clean.toJson(), true);
                SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.e(TAG, "could not save HTTPS config", e);
            }
        }
    }

    private static HttpsConfig copy(HttpsConfig source) {
        HttpsConfig copy = new HttpsConfig();
        copy.enabled = source.enabled;
        copy.port = source.port;
        copy.hostname = source.hostname;
        copy.publicUrl = source.publicUrl;
        copy.includeLocalIps = source.includeLocalIps;
        copy.updatedAt = source.updatedAt;
        return copy;
    }
}
