package com.yadaventerprise.phoneserver.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Project lifecycle: create, list, read, update and delete. Every project gets
 * a generated, ASCII-safe id that is used as its directory name - user supplied
 * names are never used as filesystem paths, which keeps projects isolated.
 */
public final class ProjectStore {

    public static final int MAX_NAME_LENGTH = 60;

    private ProjectStore() {
    }

    public static class Stats {
        public int databaseCount;
        public int fileCount;
        public int folderCount;
        public long fileBytes;
        public long databaseBytes;
        public long backupBytes;
    }

    public static class GlobalStats {
        public int projectCount;
        public int databaseCount;
        public int fileCount;
        public long fileBytes;
        public long databaseBytes;
    }

    public static Records.ProjectRecord create(Context context, String name, String description,
                                               long fileQuotaBytes, long maxUploadBytes) throws DataException {
        String cleanName = validateName(name);
        String cleanDescription = description == null ? "" : description.trim();
        long now = System.currentTimeMillis();

        Records.ProjectRecord record = new Records.ProjectRecord();
        record.id = StorageLayout.generateId();
        record.name = cleanName;
        record.description = cleanDescription;
        record.fileQuotaBytes = Math.max(0, fileQuotaBytes);
        record.maxUploadBytes = Math.max(0, maxUploadBytes);
        record.createdAt = now;
        record.updatedAt = now;

        StorageLayout layout = ContentStoreBridge.layout(context);
        StorageLayout.ensure(layout.databasesDir(record.id));
        StorageLayout.ensure(layout.filesDir(record.id));
        StorageLayout.ensure(layout.backupsDir(record.id));

        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("name", record.name);
        values.put("description", record.description);
        values.put("file_quota_bytes", record.fileQuotaBytes);
        values.put("max_upload_bytes", record.maxUploadBytes);
        values.put("created_at", record.createdAt);
        values.put("updated_at", record.updatedAt);
        try {
            SystemDatabase.get(context).getWritableDatabase().insertOrThrow("projects", null, values);
        } catch (SQLiteConstraintException e) {
            // roll back the empty directory we just made
            deleteQuietly(layout.projectDir(record.id));
            throw DataException.conflict("a project named '" + cleanName + "' already exists");
        }

        writeConfig(context, record);
        logOperation(context, record.id, "project.create", cleanName);
        return record;
    }

    public static List<Records.ProjectRecord> list(Context context) {
        List<Records.ProjectRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,description,file_quota_bytes,max_upload_bytes,created_at,updated_at "
                        + "FROM projects ORDER BY updated_at DESC", null)) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public static Records.ProjectRecord get(Context context, String id) {
        if (id == null) return null;
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,description,file_quota_bytes,max_upload_bytes,created_at,updated_at "
                        + "FROM projects WHERE id=?", new String[]{id})) {
            if (c.moveToFirst()) return fromCursor(c);
        }
        return null;
    }

    public static Records.ProjectRecord require(Context context, String id) throws DataException {
        Records.ProjectRecord record = get(context, id);
        if (record == null) throw DataException.notFound("project");
        return record;
    }

    public static Records.ProjectRecord update(Context context, String id, String name, String description,
                                               Long fileQuotaBytes, Long maxUploadBytes) throws DataException {
        Records.ProjectRecord existing = require(context, id);
        ContentValues values = new ContentValues();
        if (name != null) values.put("name", validateName(name));
        if (description != null) values.put("description", description.trim());
        if (fileQuotaBytes != null) values.put("file_quota_bytes", Math.max(0, fileQuotaBytes));
        if (maxUploadBytes != null) values.put("max_upload_bytes", Math.max(0, maxUploadBytes));
        values.put("updated_at", System.currentTimeMillis());

        try {
            int rows = SystemDatabase.get(context).getWritableDatabase()
                    .update("projects", values, "id=?", new String[]{id});
            if (rows == 0) throw DataException.notFound("project");
        } catch (SQLiteConstraintException e) {
            throw DataException.conflict("another project already uses that name");
        }

        Records.ProjectRecord updated = require(context, id);
        writeConfig(context, updated);
        logOperation(context, id, "project.update", updated.name);
        return updated;
    }

    public static void touch(Context context, String id) {
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = SystemDatabase.get(context).getWritableDatabase();
        db.update("projects", values, "id=?", new String[]{id});
    }

    /**
     * Deletes a project and all of its on-disk data. The caller must pass the
     * exact project name as a confirmation token, which prevents a stray DELETE
     * request from destroying data.
     */
    public static void delete(Context context, String id, String confirmName) throws DataException {
        Records.ProjectRecord record = require(context, id);
        if (confirmName == null || !confirmName.equals(record.name)) {
            throw new DataException("confirmation name does not match the project name", 400);
        }

        File projectDir = ContentStoreBridge.layout(context).projectDir(id);
        // Remove the directory first; if that fails we keep the metadata so the
        // project is not silently lost.
        if (projectDir.exists() && !deleteRecursively(projectDir)) {
            throw new DataException("could not delete project data (files may be in use)", 500);
        }

        SQLiteDatabase db = SystemDatabase.get(context).getWritableDatabase();
        db.delete("projects", "id=?", new String[]{id});
        logOperation(context, null, "project.delete", record.name);
    }

    public static Stats stats(Context context, String projectId) {
        Stats stats = new Stats();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        stats.databaseCount = count(db, "SELECT COUNT(*) FROM project_databases WHERE project_id=?", projectId);
        stats.fileCount = count(db, "SELECT COUNT(*) FROM files WHERE project_id=?", projectId);
        stats.folderCount = count(db, "SELECT COUNT(*) FROM folders WHERE project_id=?", projectId);
        stats.fileBytes = sum(db, "SELECT IFNULL(SUM(size_bytes),0) FROM files WHERE project_id=?", projectId);

        try (Cursor c = db.rawQuery(
                "SELECT IFNULL(SUM(size_bytes),0) FROM backups WHERE project_id=?", new String[]{projectId})) {
            if (c.moveToFirst()) stats.backupBytes = c.getLong(0);
        }

        // Prefer the real on-disk sizes so the numbers stay honest if files were
        // imported/restored outside the app.
        File dbDir = ContentStoreBridge.layout(context).databasesDir(projectId);
        File[] dbFiles = dbDir.listFiles();
        if (dbFiles != null) {
            for (File f : dbFiles) stats.databaseBytes += f.length();
        }
        return stats;
    }

    public static GlobalStats globalStats(Context context) {
        GlobalStats stats = new GlobalStats();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        stats.projectCount = count(db, "SELECT COUNT(*) FROM projects", null);
        stats.databaseCount = count(db, "SELECT COUNT(*) FROM project_databases", null);
        stats.fileCount = count(db, "SELECT COUNT(*) FROM files", null);
        stats.fileBytes = sum(db, "SELECT IFNULL(SUM(size_bytes),0) FROM files", null);
        stats.databaseBytes = sum(db, "SELECT IFNULL(SUM(size_bytes),0) FROM project_databases", null);
        return stats;
    }

    public static void logOperation(Context context, String projectId, String action, String detail) {
        ContentValues values = new ContentValues();
        values.put("project_id", projectId);
        values.put("action", action);
        values.put("detail", detail == null ? "" : detail);
        values.put("created_at", System.currentTimeMillis());
        SQLiteDatabase db = SystemDatabase.get(context).getWritableDatabase();
        try {
            db.insert("operations", null, values);
            db.execSQL("DELETE FROM operations WHERE id NOT IN "
                    + "(SELECT id FROM operations ORDER BY id DESC LIMIT 500)");
        } catch (Exception ignored) {
            // logging must never break the caller
        }
    }

    // ---- helpers ----

    static String validateName(String name) throws DataException {
        if (name == null) throw new DataException("project name is required", 400);
        String clean = name.trim();
        if (clean.isEmpty()) throw new DataException("project name is required", 400);
        if (clean.length() > MAX_NAME_LENGTH) {
            throw new DataException("project name must be at most " + MAX_NAME_LENGTH + " characters", 400);
        }
        for (int i = 0; i < clean.length(); i++) {
            if (clean.charAt(i) < 0x20) throw new DataException("project name contains control characters", 400);
        }
        return clean;
    }

    static Records.ProjectRecord fromCursor(Cursor c) {
        Records.ProjectRecord r = new Records.ProjectRecord();
        r.id = c.getString(0);
        r.name = c.getString(1);
        r.description = c.getString(2);
        r.fileQuotaBytes = c.getLong(3);
        r.maxUploadBytes = c.getLong(4);
        r.createdAt = c.getLong(5);
        r.updatedAt = c.getLong(6);
        return r;
    }

    static int count(SQLiteDatabase db, String sql, String arg) {
        try (Cursor c = db.rawQuery(sql, arg == null ? null : new String[]{arg})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    static long sum(SQLiteDatabase db, String sql, String arg) {
        try (Cursor c = db.rawQuery(sql, arg == null ? null : new String[]{arg})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** Writes the safe-to-export project configuration (never contains secrets). */
    static void writeConfig(Context context, Records.ProjectRecord record) {
        try {
            JSONObject json = new JSONObject();
            json.put("format", "phoneserver-project");
            json.put("version", StorageLayout.FORMAT_VERSION);
            json.put("id", record.id);
            json.put("name", record.name);
            json.put("description", record.description);
            json.put("fileQuotaBytes", record.fileQuotaBytes);
            json.put("maxUploadBytes", record.maxUploadBytes);
            json.put("createdAt", record.createdAt);
            json.put("updatedAt", record.updatedAt);
            File config = ContentStoreBridge.layout(context).projectConfig(record.id);
            try (FileOutputStream fos = new FileOutputStream(config)) {
                fos.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private static void deleteQuietly(File file) {
        try {
            deleteRecursively(file);
        } catch (Exception ignored) {
        }
    }
}
