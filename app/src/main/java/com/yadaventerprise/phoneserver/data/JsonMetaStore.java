package com.yadaventerprise.phoneserver.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * JSON-based replacement for SystemDatabase metadata.
 */
public final class JsonMetaStore {

    private static final String TAG = "JsonMetaStore";
    private static volatile JsonMetaStore instance;
    private final Context context;
    private final File metaFile;
    private final Object lock = new Object();

    private JsonMetaStore(Context ctx) {
        this.context = ctx.getApplicationContext();
        StorageLayout layout = ContentStoreBridge.layout(context);
        StorageLayout.ensure(layout.systemDir());
        this.metaFile = new File(layout.systemDir(), "metadata.json");
    }

    public static JsonMetaStore get(Context context) {
        if (instance == null) {
            synchronized (JsonMetaStore.class) {
                if (instance == null) {
                    instance = new JsonMetaStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    private JSONObject load() {
        try {
            return JsonStorage.readJson(metaFile);
        } catch (Exception e) {
            Log.w(TAG, "failed to load metadata", e);
            return new JSONObject();
        }
    }

    private void save(JSONObject obj) {
        try {
            JsonStorage.writeJsonAtomic(metaFile, obj, true);
        } catch (Exception e) {
            Log.e(TAG, "failed to save metadata", e);
        }
    }

    public JSONObject getData() {
        synchronized (lock) {
            return load();
        }
    }

    public void updateData(JSONObject data) {
        synchronized (lock) {
            save(data);
        }
    }
}
