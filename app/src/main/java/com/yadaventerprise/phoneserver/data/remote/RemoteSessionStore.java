package com.yadaventerprise.phoneserver.data.remote;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.security.TokenUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RemoteSessionStore {
    private static volatile RemoteSessionStore instance;

    private final File file;
    private final Object lock = new Object();
    private final Map<String, RemoteSession> sessions = new LinkedHashMap<>();
    private volatile boolean loaded;

    private RemoteSessionStore(File file) {
        this.file = file;
    }

    public static RemoteSessionStore get(Context context) {
        if (instance == null) {
            synchronized (RemoteSessionStore.class) {
                if (instance == null) {
                    instance = new RemoteSessionStore(RemotePaths.file(context, "sessions.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public Created create(String subjectType, String subjectId, long ttlMillis, String ip,
                          Iterable<String> granted, Iterable<String> denied) {
        if (subjectId == null || subjectId.isEmpty()) return null;
        String token = TokenUtil.generateToken();
        long now = System.currentTimeMillis();
        RemoteSession session = new RemoteSession();
        session.id = UUID.randomUUID().toString();
        session.tokenHash = TokenUtil.hash(token);
        session.subjectType = subjectType;
        session.subjectId = subjectId;
        session.createdAt = now;
        session.expiresAt = now + Math.max(60_000L, ttlMillis);
        session.lastSeenAt = now;
        session.ip = ip == null ? "" : ip;
        if (granted != null) {
            for (String permission : granted) {
                if (RemotePermissions.isValid(permission)) session.grantedPermissions.add(permission);
            }
        }
        if (denied != null) {
            for (String permission : denied) {
                if (RemotePermissions.isValid(permission)) session.deniedPermissions.add(permission);
            }
        }
        synchronized (lock) {
            ensureLoaded();
            prune(now);
            sessions.put(session.id, session);
            save();
        }
        return new Created(token, session.copy());
    }

    public RemoteSession verify(String token) {
        if (token == null || token.isEmpty()) return null;
        String hash = TokenUtil.hash(token);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            ensureLoaded();
            prune(now);
            RemoteSession match = null;
            for (RemoteSession session : sessions.values()) {
                if (TokenUtil.constantTimeEquals(session.tokenHash, hash)) {
                    match = session;
                    break;
                }
            }
            if (match == null || !match.isActive()) {
                save();
                return null;
            }
            match.lastSeenAt = now;
            save();
            return match.copy();
        }
    }

    public void touch(String id) {
        synchronized (lock) {
            RemoteSession session = sessions.get(id);
            if (session == null) return;
            session.lastSeenAt = System.currentTimeMillis();
            save();
        }
    }

    public void revoke(String id) {
        synchronized (lock) {
            RemoteSession session = sessions.get(id);
            if (session == null || session.revoked) return;
            session.revoked = true;
            save();
        }
    }

    public void revokeSubject(String subjectType, String subjectId) {
        synchronized (lock) {
            boolean changed = false;
            for (RemoteSession session : sessions.values()) {
                if (subjectType.equals(session.subjectType) && subjectId.equals(session.subjectId)
                        && !session.revoked) {
                    session.revoked = true;
                    changed = true;
                }
            }
            if (changed) save();
        }
    }

    public List<RemoteSession> all() {
        synchronized (lock) {
            ensureLoaded();
            List<RemoteSession> result = new ArrayList<>();
            for (RemoteSession session : sessions.values()) result.add(session.copy());
            return result;
        }
    }

    public RemoteSession get(String id) {
        synchronized (lock) {
            ensureLoaded();
            RemoteSession session = sessions.get(id);
            return session == null ? null : session.copy();
        }
    }

    public void clear() {
        synchronized (lock) {
            sessions.clear();
            loaded = true;
            save();
        }
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, RemoteSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            RemoteSession session = iterator.next().getValue();
            if (session.expiresAt < now - 24L * 60L * 60L * 1000L) iterator.remove();
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject root = JsonStorage.readJson(file);
            JSONArray array = root.optJSONArray("sessions");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                RemoteSession session = RemoteSession.fromJson(array.optJSONObject(i));
                if (!session.id.isEmpty()) sessions.put(session.id, session);
            }
        } catch (Exception e) {
            Log.w("RemoteSessionStore", "could not read sessions", e);
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (RemoteSession session : sessions.values()) array.put(session.toJson());
            JSONObject root = new JSONObject();
            RemoteJson.put(root, "version", 1);
            RemoteJson.put(root, "sessions", array);
            JsonStorage.writeJsonAtomic(file, root, true);
            com.yadaventerprise.phoneserver.data.security.SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("RemoteSessionStore", "could not save sessions", e);
        }
    }

    public static final class Created {
        public final String token;
        public final RemoteSession session;

        Created(String token, RemoteSession session) {
            this.token = token;
            this.session = session;
        }
    }
}
