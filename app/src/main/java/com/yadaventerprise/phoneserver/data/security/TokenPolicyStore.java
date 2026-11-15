package com.yadaventerprise.phoneserver.data.security;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.database.Records;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** JSON sidecar for token roles/permissions, keyed by the SQLite token id. */
public final class TokenPolicyStore {

    private static final String TAG = "TokenPolicyStore";
    private static volatile TokenPolicyStore instance;

    private final File file;
    private final Object lock = new Object();
    private final Map<String, TokenPolicy> policies = new LinkedHashMap<>();
    private volatile boolean loaded;

    public TokenPolicyStore(File file) {
        this.file = file;
    }

    public static TokenPolicyStore get(Context context) {
        if (instance == null) {
            synchronized (TokenPolicyStore.class) {
                if (instance == null) {
                    instance = new TokenPolicyStore(SecurityPaths.tokenPolicies(context));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public TokenPolicy get(String tokenId, Records.ApiTokenRecord record) {
        synchronized (lock) {
            ensureLoaded();
            TokenPolicy policy = policies.get(tokenId);
            if (policy == null) return TokenPolicy.defaultFor(record);
            return copy(policy);
        }
    }

    public void save(TokenPolicy policy) {
        if (policy == null || policy.tokenId == null || policy.tokenId.isEmpty()) return;
        if (!TokenPolicy.isValidRole(policy.role)) policy.role = TokenPolicy.ROLE_VIEWER;
        policy.updatedAt = System.currentTimeMillis();
        synchronized (lock) {
            ensureLoaded();
            policies.put(policy.tokenId, copy(policy));
            save();
        }
    }

    public void delete(String tokenId) {
        if (tokenId == null) return;
        synchronized (lock) {
            ensureLoaded();
            if (policies.remove(tokenId) != null) save();
        }
    }

    public Map<String, TokenPolicy> all() {
        synchronized (lock) {
            ensureLoaded();
            Map<String, TokenPolicy> result = new LinkedHashMap<>();
            for (Map.Entry<String, TokenPolicy> entry : policies.entrySet()) {
                result.put(entry.getKey(), copy(entry.getValue()));
            }
            return result;
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject json = JsonStorage.readJson(file);
            JSONObject map = json.optJSONObject("policies");
            if (map == null) return;
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = map.optJSONObject(key);
                if (item == null) continue;
                TokenPolicy policy = TokenPolicy.fromJson(item);
                policy.tokenId = key;
                policies.put(key, policy);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read token policies", e);
        }
    }

    private void save() {
        try {
            JSONObject map = new JSONObject();
            for (Map.Entry<String, TokenPolicy> entry : policies.entrySet()) {
                map.put(entry.getKey(), entry.getValue().toJson());
            }
            JSONObject json = new JSONObject();
            json.put("policies", map);
            JsonStorage.writeJsonAtomic(file, json, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e(TAG, "could not save token policies", e);
        }
    }

    private static TokenPolicy copy(TokenPolicy source) {
        TokenPolicy copy = new TokenPolicy();
        copy.tokenId = source.tokenId;
        copy.role = source.role;
        copy.permissions.addAll(source.permissions);
        copy.rateLimitPerMinute = source.rateLimitPerMinute;
        copy.expiresAt = source.expiresAt;
        copy.updatedAt = source.updatedAt;
        return copy;
    }
}
