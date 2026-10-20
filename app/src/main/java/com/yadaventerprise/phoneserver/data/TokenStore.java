package com.yadaventerprise.phoneserver.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

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
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) throw new DataException("token name is required", 400);
        if (cleanName.length() > 60) throw new DataException("token name is too long", 400);
        if (projectId != null && !projectId.isEmpty()) {
            ProjectStore.require(context, projectId);
        } else {
            projectId = null;
        }

        String token = TokenUtil.generateToken();
        long now = System.currentTimeMillis();

        Records.ApiTokenRecord record = new Records.ApiTokenRecord();
        record.id = StorageLayout.generateId();
        record.name = cleanName;
        record.projectId = projectId;
        record.canWrite = canWrite;
        record.createdAt = now;

        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("name", record.name);
        values.put("token_hash", TokenUtil.hash(token));
        values.put("project_id", record.projectId);
        values.put("can_write", canWrite ? 1 : 0);
        values.put("created_at", now);
        values.put("last_used_at", 0);
        values.put("revoked", 0);
        SystemDatabase.get(context).getWritableDatabase().insertOrThrow("api_tokens", null, values);

        ProjectStore.logOperation(context, projectId, "token.create", cleanName);
        Created created = new Created();
        created.record = record;
        created.token = token;
        return created;
    }

    /** @return the matching token record, or null when the token is unknown or revoked. */
    public static Records.ApiTokenRecord verify(Context context, String token) {
        if (token == null || token.isEmpty()) return null;
        String hash = TokenUtil.hash(token);
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,project_id,can_write,created_at,last_used_at,revoked "
                        + "FROM api_tokens WHERE token_hash=? AND revoked=0", new String[]{hash})) {
            if (c.moveToFirst()) {
                Records.ApiTokenRecord record = fromCursor(c);
                ContentValues values = new ContentValues();
                values.put("last_used_at", System.currentTimeMillis());
                SystemDatabase.get(context).getWritableDatabase()
                        .update("api_tokens", values, "id=?", new String[]{record.id});
                return record;
            }
        }
        return null;
    }

    public static List<Records.ApiTokenRecord> list(Context context) {
        List<Records.ApiTokenRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,project_id,can_write,created_at,last_used_at,revoked "
                        + "FROM api_tokens ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public static List<Records.ApiTokenRecord> listForProject(Context context, String projectId) {
        List<Records.ApiTokenRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,project_id,can_write,created_at,last_used_at,revoked "
                        + "FROM api_tokens WHERE project_id=? OR project_id IS NULL "
                        + "ORDER BY created_at DESC", new String[]{projectId})) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public static void revoke(Context context, String id) {
        ContentValues values = new ContentValues();
        values.put("revoked", 1);
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
        return r;
    }
}
