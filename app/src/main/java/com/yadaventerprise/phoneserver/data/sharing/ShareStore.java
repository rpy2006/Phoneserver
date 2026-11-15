package com.yadaventerprise.phoneserver.data.sharing;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ShareStore {

    public static final String ERROR_INVALID = "invalid";
    public static final String ERROR_EXPIRED = "expired";
    public static final String ERROR_REVOKED = "revoked";
    public static final String ERROR_LIMIT = "limit";

    private static volatile ShareStore instance;

    private final File file;
    private final Object lock = new Object();
    private List<ShareLink> cached;

    public ShareStore(File file) {
        this.file = file;
    }

    public static ShareStore get(Context context) {
        if (instance == null) {
            synchronized (ShareStore.class) {
                if (instance == null) instance = new ShareStore(SharePaths.links(context));
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public List<ShareLink> all() {
        synchronized (lock) {
            ensureLoaded();
            List<ShareLink> result = new ArrayList<>();
            for (ShareLink link : cached) result.add(link.copy());
            Collections.sort(result, new Comparator<ShareLink>() {
                @Override
                public int compare(ShareLink a, ShareLink b) {
                    return Long.compare(b.updatedAt, a.updatedAt);
                }
            });
            return result;
        }
    }

    public ShareLink get(String id) {
        synchronized (lock) {
            ensureLoaded();
            for (ShareLink link : cached) {
                if (link.id.equals(id)) return link.copy();
            }
            return null;
        }
    }

    public ShareLink findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isEmpty()) return null;
        synchronized (lock) {
            ensureLoaded();
            for (ShareLink link : cached) {
                if (link.tokenHash.equals(tokenHash)) return link.copy();
            }
            return null;
        }
    }

    public Created create(ShareLink draft, String password) {
        if (draft == null) return null;
        synchronized (lock) {
            ensureLoaded();
            ShareLink link = draft.copy();
            link.id = UUID.randomUUID().toString();
            link.createdAt = System.currentTimeMillis();
            link.updatedAt = link.createdAt;
            link.downloadCount = 0;
            link.lastAccessAt = 0;
            link.lastAccessIp = "";
            String token = ShareToken.generate();
            link.tokenHash = ShareToken.hash(token);
            link.passwordHash = password == null || password.isEmpty() ? "" : SharePassword.hash(password);
            cached.add(link.copy());
            persist();
            return new Created(link.copy(), token);
        }
    }

    public ShareLink update(String id, ShareLink changes, String newPassword, boolean clearPassword) {
        if (id == null || changes == null) return null;
        synchronized (lock) {
            ensureLoaded();
            for (int i = 0; i < cached.size(); i++) {
                ShareLink current = cached.get(i);
                if (!current.id.equals(id)) continue;
                current.name = changes.name == null ? "" : changes.name;
                current.rootPath = changes.rootPath == null ? "" : changes.rootPath;
                current.type = ShareLink.TYPE_FOLDER.equals(changes.type)
                        ? ShareLink.TYPE_FOLDER : ShareLink.TYPE_FILE;
                current.permission = ShareLink.PERMISSION_VIEW.equals(changes.permission)
                        ? ShareLink.PERMISSION_VIEW : ShareLink.PERMISSION_DOWNLOAD;
                current.expiresAt = Math.max(0, changes.expiresAt);
                current.maxDownloads = Math.max(0, changes.maxDownloads);
                current.revoked = changes.revoked;
                current.updatedAt = System.currentTimeMillis();
                if (clearPassword) {
                    current.passwordHash = "";
                } else if (newPassword != null) {
                    current.passwordHash = newPassword.isEmpty() ? "" : SharePassword.hash(newPassword);
                }
                if (clearPassword || newPassword != null) {
                    ShareSessionStore.revokeLink(current.id);
                }
                persist();
                return current.copy();
            }
            return null;
        }
    }

    public boolean delete(String id) {
        if (id == null) return false;
        synchronized (lock) {
            ensureLoaded();
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(id)) {
                    cached.remove(i);
                    ShareSessionStore.revokeLink(id);
                    persist();
                    return true;
                }
            }
            return false;
        }
    }

    public Created rotateToken(String id) {
        if (id == null) return null;
        synchronized (lock) {
            ensureLoaded();
            for (ShareLink link : cached) {
                if (!link.id.equals(id)) continue;
                String token = ShareToken.generate();
                link.tokenHash = ShareToken.hash(token);
                link.downloadCount = 0;
                link.updatedAt = System.currentTimeMillis();
                ShareSessionStore.revokeLink(id);
                persist();
                return new Created(link.copy(), token);
            }
            return null;
        }
    }

    public ShareLink setRevoked(String id, boolean revoked) {
        synchronized (lock) {
            ensureLoaded();
            for (ShareLink link : cached) {
                if (!link.id.equals(id)) continue;
                link.revoked = revoked;
                link.updatedAt = System.currentTimeMillis();
                ShareSessionStore.revokeLink(id);
                persist();
                return link.copy();
            }
            return null;
        }
    }

    public void recordAccess(String id, String ip, long now) {
        if (id == null) return;
        synchronized (lock) {
            ensureLoaded();
            for (ShareLink link : cached) {
                if (!link.id.equals(id)) continue;
                link.lastAccessAt = now;
                link.lastAccessIp = ip == null ? "" : ip;
                persist();
                return;
            }
        }
    }

    public ConsumeResult consumeDownload(String tokenHash, long now) {
        if (tokenHash == null || tokenHash.isEmpty()) return ConsumeResult.error(ERROR_INVALID);
        synchronized (lock) {
            ensureLoaded();
            for (ShareLink link : cached) {
                if (!link.tokenHash.equals(tokenHash)) continue;
                if (link.revoked) return ConsumeResult.error(ERROR_REVOKED);
                if (link.isExpired(now)) return ConsumeResult.error(ERROR_EXPIRED);
                if (link.isDownloadLimitReached()) return ConsumeResult.error(ERROR_LIMIT);
                link.downloadCount++;
                link.updatedAt = now;
                persist();
                return ConsumeResult.success(link.copy());
            }
            return ConsumeResult.error(ERROR_INVALID);
        }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        cached = new ArrayList<>();
        try {
            JSONObject root = JsonStorage.readJson(file);
            JSONArray array = root.optJSONArray("links");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) cached.add(ShareLink.fromJson(json));
            }
        } catch (Exception e) {
            Log.w("ShareStore", "could not read share links", e);
        }
    }

    private void persist() {
        try {
            JSONObject root = new JSONObject();
            JSONArray array = new JSONArray();
            for (ShareLink link : cached) array.put(link.toJson());
            root.put("version", 1);
            root.put("links", array);
            JsonStorage.writeJsonAtomic(file, root, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("ShareStore", "could not save share links", e);
        }
    }

    public static final class Created {
        public final ShareLink link;
        public final String token;

        Created(ShareLink link, String token) {
            this.link = link;
            this.token = token;
        }
    }

    public static final class ConsumeResult {
        public final ShareLink link;
        public final String error;

        private ConsumeResult(ShareLink link, String error) {
            this.link = link;
            this.error = error;
        }

        static ConsumeResult success(ShareLink link) {
            return new ConsumeResult(link, "");
        }

        static ConsumeResult error(String error) {
            return new ConsumeResult(null, error);
        }

        public boolean ok() {
            return error == null || error.isEmpty();
        }
    }
}
