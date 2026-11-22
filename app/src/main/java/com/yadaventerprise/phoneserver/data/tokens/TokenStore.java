package com.yadaventerprise.phoneserver.data.tokens;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.database.SystemDatabase;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.data.storage.StorageLayout;
import com.yadaventerprise.phoneserver.network.http.DataException;
import com.yadaventerprise.phoneserver.security.TokenUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * API tokens for non-interactive clients (small apps, websites, scripts).
 *
 * Only the SHA-256 hash is stored; the plaintext token is returned once, at
 * creation. A token may be scoped to a single project and/or read-only.
 */
public final class TokenStore {

    private TokenStore() {
    }

    public static class Created {
        public Records.ApiTokenRecord record;
        public String token;
    }

    public static Created create(Context context, String name, String projectId, boolean canWrite)
            throws DataException {
        return create(context, name, projectId, canWrite, 0, 0, 0, 0);
    }

    public static Created create(Context context, String name, String projectId, boolean canWrite,
                                 long expiresAt) throws DataException {
        return create(context, name, projectId, canWrite, expiresAt, 0, 0, 0);
    }

    public static Created create(Context context, String name, String projectId, boolean canWrite,
                                 long expiresAt, long dailyQuota, long monthlyQuota, int concurrentLimit) throws DataException {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) throw new DataException("token name is required", 400);
        if (cleanName.length() > 60) throw new DataException("token name is too long", 400);
        if (projectId != null && !projectId.isEmpty()) {
            ProjectStore.require(context, projectId);
        } else {
            projectId = null;
        }
        if (expiresAt < 0) throw new DataException("expiresAt must be 0 or positive", 400);
        if (dailyQuota < 0 || monthlyQuota < 0 || concurrentLimit < 0) throw new DataException("quota values must be >= 0", 400);

        String token = TokenUtil.generateToken();
        long now = System.currentTimeMillis();

        Records.ApiTokenRecord record = new Records.ApiTokenRecord();
        record.id = StorageLayout.generateId();
        record.name = cleanName;
        record.projectId = projectId;
        record.canWrite = canWrite;
        record.createdAt = now;
        record.expiresAt = expiresAt;
        record.keyPrefix = TokenUtil.prefix(token);
        record.dailyQuota = dailyQuota;
        record.monthlyQuota = monthlyQuota;
        record.concurrentLimit = concurrentLimit;

        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("name", record.name);
        values.put("token_hash", TokenUtil.hash(token));
        values.put("project_id", record.projectId);
        values.put("can_write", canWrite ? 1 : 0);
        values.put("created_at", now);
        values.put("last_used_at", 0);
        values.put("revoked", 0);
        values.put("suspended", 0);
        values.put("expires_at", expiresAt);
        values.put("key_prefix", record.keyPrefix);
        values.put("last_used_ip", "");
        values.put("daily_quota", dailyQuota);
        values.put("monthly_quota", monthlyQuota);
        values.put("concurrent_limit", concurrentLimit);
        SystemDatabase.get(context).getWritableDatabase().insertOrThrow("api_tokens", null, values);

        ProjectStore.logOperation(context, projectId, "token.create", cleanName);
        Created created = new Created();
        created.record = record;
        created.token = token;
        return created;
    }

    /** @return the matching token record, or null when the token is unknown or revoked. */
    public static Records.ApiTokenRecord verify(Context context, String token) {
        return verify(context, token, null);
    }

    public static Records.ApiTokenRecord verify(Context context, String token, String remoteIp) {
        if (token == null || token.isEmpty()) return null;
        String hash = TokenUtil.hash(token);
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,project_id,can_write,created_at,last_used_at,revoked,suspended,expires_at,key_prefix,last_used_ip,daily_quota,monthly_quota,concurrent_limit "
                        + "FROM api_tokens WHERE token_hash=? AND revoked=0", new String[]{hash})) {
            if (c.moveToFirst()) {
                Records.ApiTokenRecord record = fromCursor(c);
                if (record.suspended) return null;
                if (record.expiresAt > 0 && record.expiresAt <= System.currentTimeMillis()) return null;
                ContentValues values = new ContentValues();
                values.put("last_used_at", System.currentTimeMillis());
                if (remoteIp != null) values.put("last_used_ip", remoteIp);
                SystemDatabase.get(context).getWritableDatabase()
                        .update("api_tokens", values, "id=?", new String[]{record.id});
                record.lastUsedAt = System.currentTimeMillis();
                if (remoteIp != null) record.lastUsedIp = remoteIp;
                return record;
            }
        }
        return null;
    }

    public static Records.ApiTokenRecord findById(Context context, String id) {
        if (id == null) return null;
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,project_id,can_write,created_at,last_used_at,revoked,suspended,expires_at,key_prefix,last_used_ip,daily_quota,monthly_quota,concurrent_limit "
                        + "FROM api_tokens WHERE id=?", new String[]{id})) {
            if (c.moveToFirst()) return fromCursor(c);
        }
        return null;
    }

    public static List<Records.ApiTokenRecord> list(Context context) {
        List<Records.ApiTokenRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,project_id,can_write,created_at,last_used_at,revoked,suspended,expires_at,key_prefix,last_used_ip,daily_quota,monthly_quota,concurrent_limit "
                        + "FROM api_tokens ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public static List<Records.ApiTokenRecord> listForProject(Context context, String projectId) {
        List<Records.ApiTokenRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,project_id,can_write,created_at,last_used_at,revoked,suspended,expires_at,key_prefix,last_used_ip,daily_quota,monthly_quota,concurrent_limit "
                        + "FROM api_tokens WHERE project_id=? OR project_id IS NULL "
                        + "ORDER BY created_at DESC", new String[]{projectId})) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public static void revoke(Context context, String id) {
        updateFlags(context, id, 1, null);
    }

    public static void setSuspended(Context context, String id, boolean suspended) {
        updateFlags(context, id, null, suspended ? 1 : 0);
    }

    public static void setExpiresAt(Context context, String id, long expiresAt) {
        ContentValues values = new ContentValues();
        values.put("expires_at", Math.max(0, expiresAt));
        SystemDatabase.get(context).getWritableDatabase()
                .update("api_tokens", values, "id=?", new String[]{id});
    }

    public static void rename(Context context, String id, String name) throws DataException {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) throw new DataException("token name is required", 400);
        if (cleanName.length() > 60) throw new DataException("token name is too long", 400);
        ContentValues values = new ContentValues();
        values.put("name", cleanName);
        SystemDatabase.get(context).getWritableDatabase().update("api_tokens", values, "id=?", new String[]{id});
    }

    public static Created regenerate(Context context, String id) throws DataException {
        Records.ApiTokenRecord existing = findById(context, id);
        if (existing == null) throw DataException.notFound("token");
        String token = TokenUtil.generateToken();
        ContentValues values = new ContentValues();
        values.put("token_hash", TokenUtil.hash(token));
        values.put("key_prefix", TokenUtil.prefix(token));
        values.put("revoked", 0);
        values.put("suspended", existing.suspended ? 1 : 0);
        values.put("last_used_at", 0);
        values.put("last_used_ip", "");
        SystemDatabase.get(context).getWritableDatabase().update("api_tokens", values, "id=?", new String[]{id});
        Records.ApiTokenRecord updated = findById(context, id);
        ProjectStore.logOperation(context, existing.projectId, "token.regenerate", existing.name);
        Created created = new Created();
        created.record = updated == null ? existing : updated;
        created.token = token;
        return created;
    }

    public static void updateQuotas(Context context, String id, long dailyQuota, long monthlyQuota, int concurrentLimit) throws DataException {
        if (dailyQuota < 0 || monthlyQuota < 0 || concurrentLimit < 0) throw new DataException("quota values must be >= 0", 400);
        ContentValues values = new ContentValues();
        values.put("daily_quota", dailyQuota);
        values.put("monthly_quota", monthlyQuota);
        values.put("concurrent_limit", concurrentLimit);
        SystemDatabase.get(context).getWritableDatabase().update("api_tokens", values, "id=?", new String[]{id});
    }

    private static void updateFlags(Context context, String id, Integer revoked, Integer suspended) {
        ContentValues values = new ContentValues();
        if (revoked != null) values.put("revoked", revoked);
        if (suspended != null) values.put("suspended", suspended);
        SystemDatabase.get(context).getWritableDatabase()
                .update("api_tokens", values, "id=?", new String[]{id});
    }

    public static void delete(Context context, String id) {
        SystemDatabase.get(context).getWritableDatabase()
                .delete("api_tokens", "id=?", new String[]{id});
    }

    private static Records.ApiTokenRecord fromCursor(Cursor c) {
        Records.ApiTokenRecord r = new Records.ApiTokenRecord();
        r.id = c.getString(0);
        r.name = c.getString(1);
        r.projectId = c.isNull(2) ? null : c.getString(2);
        r.canWrite = c.getInt(3) != 0;
        r.createdAt = c.getLong(4);
        r.lastUsedAt = c.getLong(5);
        r.revoked = c.getInt(6) != 0;
        r.suspended = c.getColumnCount() > 7 && c.getInt(7) != 0;
        r.expiresAt = c.getColumnCount() > 8 ? c.getLong(8) : 0;
        r.keyPrefix = c.getColumnCount() > 9 && !c.isNull(9) ? c.getString(9) : "";
        r.lastUsedIp = c.getColumnCount() > 10 && !c.isNull(10) ? c.getString(10) : "";
        r.dailyQuota = c.getColumnCount() > 11 ? c.getLong(11) : 0;
        r.monthlyQuota = c.getColumnCount() > 12 ? c.getLong(12) : 0;
        r.concurrentLimit = c.getColumnCount() > 13 ? c.getInt(13) : 0;
        return r;
    }
}
