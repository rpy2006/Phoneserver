package com.yadaventerprise.phoneserver.data.remote;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.network.pairing.PairingStore;
import com.yadaventerprise.phoneserver.security.TokenUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RemotePairingStore {
    private static final Map<String, Integer> FAILED_CODES = new ConcurrentHashMap<>();
    private static final Set<String> LOCKED_CODES = ConcurrentHashMap.newKeySet();
    private static volatile RemotePairingStore instance;

    private final File file;
    private final Object lock = new Object();
    private final Map<String, RemotePairingRequest> requests = new LinkedHashMap<>();
    private volatile boolean loaded;

    private RemotePairingStore(File file) {
        this.file = file;
    }

    public static RemotePairingStore get(Context context) {
        if (instance == null) {
            synchronized (RemotePairingStore.class) {
                if (instance == null) {
                    instance = new RemotePairingStore(RemotePaths.file(context, "pairing.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public Created create(String requestedName, long ttlMillis) {
        String plain = PairingStore.newCode();
        long now = System.currentTimeMillis();
        RemotePairingRequest request = new RemotePairingRequest();
        request.id = UUID.randomUUID().toString();
        request.codeHash = TokenUtil.hash(PairingStore.normalize(plain));
        request.requestedName = RemotePairingRequest.sanitize(requestedName);
        request.createdAt = now;
        request.expiresAt = now + Math.max(60_000L, ttlMillis);
        synchronized (lock) {
            ensureLoaded();
            prune(now);
            requests.put(request.id, request);
            save();
        }
        return new Created(plain, request.copy());
    }

    public RemotePairingRequest findByCode(String supplied) {
        String normalized = PairingStore.normalize(supplied);
        if (normalized.length() != 8) return null;
        if (LOCKED_CODES.contains(normalized)) return null;
        String hash = TokenUtil.hash(normalized);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            ensureLoaded();
            prune(now);
            RemotePairingRequest match = null;
            for (RemotePairingRequest request : requests.values()) {
                if (TokenUtil.constantTimeEquals(request.codeHash, hash)) {
                    match = request;
                    break;
                }
            }
            if (match == null) {
                if (FAILED_CODES.size() > 1000) FAILED_CODES.clear();
                int failed = FAILED_CODES.merge(normalized, 1, Integer::sum);
                if (failed >= 16) LOCKED_CODES.add(normalized);
                return null;
            }
            if (!match.isActive(now)) return null;
            return match.copy();
        }
    }

    public RemotePairingRequest submitDeviceInfo(String id, String deviceName, JSONObject info) {
        synchronized (lock) {
            RemotePairingRequest request = requests.get(id);
            if (request == null || !request.isActive(System.currentTimeMillis())) return null;
            request.deviceName = RemotePairingRequest.sanitize(deviceName);
            request.deviceInfo = info == null ? "" : RemotePairingRequest.sanitize(info.toString());
            save();
            return request.copy();
        }
    }

    public RemotePairingRequest approve(String id, String approver, String deviceId) {
        synchronized (lock) {
            RemotePairingRequest request = requests.get(id);
            if (request == null || !request.isActive(System.currentTimeMillis())) return null;
            request.status = RemotePairingRequest.STATUS_APPROVED;
            request.approvedBy = RemotePairingRequest.sanitize(approver);
            request.approvedAt = System.currentTimeMillis();
            request.deviceId = deviceId;
            save();
            return request.copy();
        }
    }

    public RemotePairingRequest reject(String id, String approver) {
        synchronized (lock) {
            RemotePairingRequest request = requests.get(id);
            if (request == null) return null;
            request.status = RemotePairingRequest.STATUS_REJECTED;
            request.approvedBy = RemotePairingRequest.sanitize(approver);
            request.approvedAt = System.currentTimeMillis();
            save();
            return request.copy();
        }
    }

    public boolean consume(String id, String tokenHash) {
        synchronized (lock) {
            RemotePairingRequest request = requests.get(id);
            if (request == null || !RemotePairingRequest.STATUS_APPROVED.equals(request.status)) return false;
            request.sessionTokenHash = tokenHash == null ? "" : tokenHash;
            request.status = RemotePairingRequest.STATUS_CONSUMED;
            save();
            return true;
        }
    }

    public List<RemotePairingRequest> all() {
        synchronized (lock) {
            ensureLoaded();
            prune(System.currentTimeMillis());
            List<RemotePairingRequest> result = new ArrayList<>();
            for (RemotePairingRequest request : requests.values()) result.add(request.copy());
            return result;
        }
    }

    public RemotePairingRequest get(String id) {
        synchronized (lock) {
            ensureLoaded();
            RemotePairingRequest request = requests.get(id);
            return request == null ? null : request.copy();
        }
    }

    public void clear() {
        synchronized (lock) {
            requests.clear();
            loaded = true;
            save();
        }
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, RemotePairingRequest>> iterator = requests.entrySet().iterator();
        while (iterator.hasNext()) {
            RemotePairingRequest request = iterator.next().getValue();
            if (request.expiresAt < now - 24L * 60L * 60L * 1000L) iterator.remove();
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject root = JsonStorage.readJson(file);
            JSONArray array = root.optJSONArray("requests");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                RemotePairingRequest request = RemotePairingRequest.fromJson(array.optJSONObject(i));
                if (!request.id.isEmpty()) requests.put(request.id, request);
            }
        } catch (Exception e) {
            Log.w("RemotePairingStore", "could not read pairing requests", e);
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (RemotePairingRequest request : requests.values()) array.put(request.toJson());
            JSONObject root = new JSONObject();
            RemoteJson.put(root, "version", 1);
            RemoteJson.put(root, "requests", array);
            JsonStorage.writeJsonAtomic(file, root, true);
            com.yadaventerprise.phoneserver.data.security.SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("RemotePairingStore", "could not save pairing requests", e);
        }
    }

    public static final class Created {
        public final String code;
        public final RemotePairingRequest request;

        Created(String code, RemotePairingRequest request) {
            this.code = code;
            this.request = request;
        }
    }
}
