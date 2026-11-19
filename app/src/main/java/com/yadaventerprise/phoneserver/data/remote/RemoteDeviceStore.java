package com.yadaventerprise.phoneserver.data.remote;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RemoteDeviceStore {
    private static volatile RemoteDeviceStore instance;

    private final File file;
    private final Object lock = new Object();
    private final Map<String, RemoteDevice> devices = new LinkedHashMap<>();
    private volatile boolean loaded;

    private RemoteDeviceStore(File file) {
        this.file = file;
    }

    public static RemoteDeviceStore get(Context context) {
        if (instance == null) {
            synchronized (RemoteDeviceStore.class) {
                if (instance == null) {
                    instance = new RemoteDeviceStore(RemotePaths.file(context, "devices.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public List<RemoteDevice> all() {
        synchronized (lock) {
            ensureLoaded();
            List<RemoteDevice> result = new ArrayList<>();
            for (RemoteDevice device : devices.values()) result.add(device.copy());
            return result;
        }
    }

    public RemoteDevice get(String id) {
        synchronized (lock) {
            ensureLoaded();
            RemoteDevice device = devices.get(id);
            return device == null ? null : device.copy();
        }
    }

    public RemoteDevice findByUid(String deviceUid) {
        synchronized (lock) {
            ensureLoaded();
            for (RemoteDevice device : devices.values()) {
                if (device.deviceUid.equals(deviceUid)) return device.copy();
            }
            return null;
        }
    }

    public RemoteDevice create(RemoteDevice draft) {
        if (draft == null) return null;
        synchronized (lock) {
            ensureLoaded();
            RemoteDevice device = draft.copy();
            device.id = UUID.randomUUID().toString();
            device.deviceUid = UUID.randomUUID().toString();
            device.registeredAt = System.currentTimeMillis();
            device.lastSeenAt = device.registeredAt;
            device.revoked = false;
            device.disconnected = false;
            device.offlineNotified = false;
            device.onlineNotified = true;
            devices.put(device.id, device);
            save();
            return device.copy();
        }
    }

    public RemoteDevice register(String id, RemoteDevice draft) {
        if (draft == null || id == null || id.isEmpty()) return null;
        synchronized (lock) {
            ensureLoaded();
            if (devices.containsKey(id)) return devices.get(id).copy();
            RemoteDevice device = draft.copy();
            device.id = id;
            device.deviceUid = UUID.randomUUID().toString();
            device.registeredAt = System.currentTimeMillis();
            device.lastSeenAt = 0;
            device.lastReportAt = 0;
            device.revoked = false;
            device.disconnected = true;
            device.offlineNotified = false;
            device.onlineNotified = false;
            devices.put(device.id, device);
            save();
            return device.copy();
        }
    }

    public RemoteDevice save(RemoteDevice device) {
        if (device == null || device.id.isEmpty()) return null;
        synchronized (lock) {
            ensureLoaded();
            RemoteDevice copy = device.copy();
            devices.put(copy.id, copy);
            save();
            return copy.copy();
        }
    }

    public RemoteDevice rename(String id, String name) {
        synchronized (lock) {
            RemoteDevice device = devices.get(id);
            if (device == null) return null;
            device.name = RemoteDevice.friendlyName(name);
            save();
            return device.copy();
        }
    }

    public RemoteDevice heartbeat(String id, JSONObject report) {
        synchronized (lock) {
            RemoteDevice device = devices.get(id);
            if (device == null || device.revoked || device.disconnected) return null;
            device.applyHeartbeat(report);
            save();
            return device.copy();
        }
    }

    public RemoteDevice touch(String id) {
        synchronized (lock) {
            RemoteDevice device = devices.get(id);
            if (device == null) return null;
            device.lastSeenAt = System.currentTimeMillis();
            device.disconnected = false;
            save();
            return device.copy();
        }
    }

    public RemoteDevice disconnect(String id) {
        synchronized (lock) {
            RemoteDevice device = devices.get(id);
            if (device == null) return null;
            device.disconnected = true;
            device.offlineNotified = true;
            save();
            return device.copy();
        }
    }

    public RemoteDevice revoke(String id) {
        synchronized (lock) {
            RemoteDevice device = devices.get(id);
            if (device == null) return null;
            device.revoked = true;
            device.disconnected = true;
            device.sessionId = "";
            save();
            return device.copy();
        }
    }

    public void clear() {
        synchronized (lock) {
            devices.clear();
            loaded = true;
            save();
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject root = JsonStorage.readJson(file);
            JSONArray array = root.optJSONArray("devices");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                RemoteDevice device = RemoteDevice.fromJson(array.optJSONObject(i));
                if (!device.id.isEmpty()) devices.put(device.id, device);
            }
        } catch (Exception e) {
            Log.w("RemoteDeviceStore", "could not read devices", e);
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (RemoteDevice device : devices.values()) array.put(device.toJson());
            JSONObject root = new JSONObject();
            RemoteJson.put(root, "version", 1);
            RemoteJson.put(root, "devices", array);
            JsonStorage.writeJsonAtomic(file, root, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("RemoteDeviceStore", "could not save devices", e);
        }
    }
}
