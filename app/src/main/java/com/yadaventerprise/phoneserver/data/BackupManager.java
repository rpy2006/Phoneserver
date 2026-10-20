package com.yadaventerprise.phoneserver.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Project backups.
 *
 * A backup is a single zip archive built by {@link BackupArchive}: a
 * {@code manifest.properties}, the project's databases, its stored files (named
 * by storage key) and a {@code metadata.json} that lets a restore rebuild the
 * folder structure and original file names. Restore validates the archive,
 * never extracts outside the project, and only replaces data once extraction
 * has completed successfully.
 */
public final class BackupManager {

    public static final int MAX_BACKUPS_PER_PROJECT = 25;
    private static final String METADATA_ENTRY = "metadata.json";
    private static final String CONFIG_ENTRY = "project-config.json";
    private static final String METADATA_FORMAT = "phoneserver-project-metadata";

    private BackupManager() {
    }

    // ---- create ----

    public static Records.BackupRecord create(Context context, String projectId, boolean includeFiles,
                                              String note) throws DataException {
        Records.ProjectRecord project = ProjectStore.require(context, projectId);
        StorageLayout layout = ContentStoreBridge.layout(context);
        String cleanNote = note == null ? "" : note.trim();
        if (cleanNote.length() > 200) throw new DataException("backup note is too long", 400);

        String id = StorageLayout.generateId();
        File backupsDir = StorageLayout.ensure(layout.backupsDir(projectId));
        File archive = new File(backupsDir, id + ".zip");

        List<Records.DbRecord> databases = DatabaseManager.list(context, projectId);
        List<Records.FileRecord> files = includeFiles ? FileStore.listAll(context, projectId)
                : new ArrayList<>();

        Map<String, String> manifest = new LinkedHashMap<>();
        manifest.put("format", BackupArchive.FORMAT);
        manifest.put("version", BackupArchive.VERSION);
        manifest.put("projectId", projectId);
        manifest.put("projectName", project.name);
        manifest.put("createdAt", String.valueOf(System.currentTimeMillis()));
        manifest.put("includesFiles", includeFiles ? "true" : "false");
        manifest.put("databaseCount", String.valueOf(databases.size()));
        manifest.put("fileCount", String.valueOf(files.size()));
        manifest.put("note", cleanNote);
        manifest.put("appVersion", "phoneserver");

        File metadataJson = layout.newTempFile("metadata");
        try {
            writeMetadataFile(context, projectId, databases, files, metadataJson);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            metadataJson.delete();
            throw new DataException("could not write backup metadata", 500);
        }

        List<BackupArchive.Entry> entries = new ArrayList<>();
        entries.add(new BackupArchive.Entry(METADATA_ENTRY, metadataJson));
        File config = layout.projectConfig(projectId);
        if (config.exists()) entries.add(new BackupArchive.Entry(CONFIG_ENTRY, config));

        for (Records.DbRecord db : databases) {
            File file = new File(layout.databasesDir(projectId), db.fileName);
            if (file.exists()) entries.add(new BackupArchive.Entry("databases/" + db.fileName, file));
        }
        for (Records.FileRecord file : files) {
            try {
                File content = FileStore.contentFile(context, file);
                entries.add(new BackupArchive.Entry("files/" + file.storageKey, content));
            } catch (DataException ignored) {
                // content already missing - it is reported by detectIssues()
            }
        }

        try {
            BackupArchive.create(archive, manifest, entries);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            throw new DataException("could not write backup archive: " + e.getMessage(), 500);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            metadataJson.delete();
        }

        long now = System.currentTimeMillis();
        Records.BackupRecord record = new Records.BackupRecord();
        record.id = id;
        record.projectId = projectId;
        record.fileName = backupDisplayName(project.name, now);
        record.sizeBytes = archive.length();
        record.includesFiles = includeFiles;
        record.valid = true;
        record.note = cleanNote;
        record.createdAt = now;

        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("project_id", projectId);
        values.put("file_name", record.fileName);
        values.put("size_bytes", record.sizeBytes);
        values.put("includes_files", includeFiles ? 1 : 0);
        values.put("valid", 1);
        values.put("note", record.note);
        values.put("created_at", now);
        SystemDatabase.get(context).getWritableDatabase().insertOrThrow("backups", null, values);

        enforceRetention(context, projectId);
        ProjectStore.touch(context, projectId);
        ProjectStore.logOperation(context, projectId, "backup.create", record.fileName);
        return record;
    }

    // ---- read ----

    public static List<Records.BackupRecord> list(Context context, String projectId) throws DataException {
        ProjectStore.require(context, projectId);
        List<Records.BackupRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,project_id,file_name,size_bytes,includes_files,valid,note,created_at "
                        + "FROM backups WHERE project_id=? ORDER BY created_at DESC",
                new String[]{projectId})) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public static Records.BackupRecord require(Context context, String projectId, String backupId)
            throws DataException {
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,project_id,file_name,size_bytes,includes_files,valid,note,created_at "
                        + "FROM backups WHERE project_id=? AND id=?", new String[]{projectId, backupId})) {
            if (c.moveToFirst()) return fromCursor(c);
        }
        throw DataException.notFound("backup");
    }

    public static File archiveFile(Context context, String projectId, String backupId) throws DataException {
        StorageLayout layout = ContentStoreBridge.layout(context);
        if (!backupId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new DataException("invalid backup id", 400);
        }
        File file = new File(layout.backupsDir(projectId), backupId + ".zip");
        if (!file.exists() || !file.isFile()) throw DataException.notFound("backup");
        return file;
    }

    /** Re-validates the archive on demand and refreshes the stored flag. */
    public static Map<String, String> validate(Context context, String projectId, String backupId)
            throws DataException {
        require(context, projectId, backupId);
        File file = archiveFile(context, projectId, backupId);
        Map<String, String> manifest;
        try {
            manifest = BackupArchive.validate(file);
        } catch (IOException e) {
            updateValid(context, backupId, false);
            throw new DataException("backup archive failed validation: " + e.getMessage(), 422);
        }
        updateValid(context, backupId, true);
        return manifest;
    }

    public static void delete(Context context, String projectId, String backupId) throws DataException {
        require(context, projectId, backupId);
        File file = new File(StorageLayout.ensure(
                ContentStoreBridge.layout(context).backupsDir(projectId)), backupId + ".zip");
        if (file.exists() && !file.delete()) {
            throw new DataException("could not delete backup file", 500);
        }
        SystemDatabase.get(context).getWritableDatabase()
                .delete("backups", "id=?", new String[]{backupId});
        ProjectStore.logOperation(context, projectId, "backup.delete", backupId);
    }

    // ---- restore ----

    /**
     * Extracts a validated archive into the project, replacing the project's
     * databases (and files, when the archive contains them) only after the
     * whole archive has been extracted safely.
     */
    public static void restore(Context context, String projectId, String backupId, boolean confirm)
            throws DataException {
        Records.BackupRecord record = require(context, projectId, backupId);
        File archive = archiveFile(context, projectId, backupId);
        restoreFromArchive(context, projectId, archive, record.includesFiles, confirm, "restore");
    }

    /** Restores an externally uploaded archive into a project (used by import). */
    public static void importArchive(Context context, String projectId, File archive, boolean confirm)
            throws DataException {
        ProjectStore.require(context, projectId);
        Map<String, String> manifest;
        try {
            manifest = BackupArchive.validate(archive);
        } catch (IOException e) {
            throw new DataException("not a valid PhoneServer backup: " + e.getMessage(), 422);
        }
        boolean includeFiles = !"false".equals(manifest.get("includesFiles"));
        restoreFromArchive(context, projectId, archive, includeFiles, confirm, "import");
    }

    private static void restoreFromArchive(Context context, String projectId, File archive,
                                           boolean includeFiles, boolean confirm, String action)
            throws DataException {
        if (!confirm) {
            throw new DataException(action + " requires confirm=true (it replaces existing data)", 400);
        }
        ProjectStore.require(context, projectId);
        StorageLayout layout = ContentStoreBridge.layout(context);
        File staging = new File(StorageLayout.ensure(layout.tmpDir()),
                "restore-" + StorageLayout.generateId());
        try {
            try {
                BackupArchive.extract(archive, staging, BackupArchive.DEFAULT_MAX_TOTAL_BYTES);
            } catch (IOException e) {
                throw new DataException("could not extract backup: " + e.getMessage(), 422);
            }
            Map<String, Records.FileRecord> knownFiles = readMetadata(new File(staging, METADATA_ENTRY));

            // Databases are always replaced; files only when the archive has them.
            replaceDir(new File(staging, "databases"), layout.databasesDir(projectId));
            if (includeFiles) {
                replaceDir(new File(staging, "files"), layout.filesDir(projectId));
            }

            rebuildMetadata(context, projectId, layout, knownFiles, includeFiles);
            ProjectStore.touch(context, projectId);
            ProjectStore.logOperation(context, projectId, "backup." + action, archive.getName());
        } finally {
            deleteRecursively(staging);
        }
    }

    // ---- internals ----

    private static void rebuildMetadata(Context context, String projectId, StorageLayout layout,
                                        Map<String, Records.FileRecord> knownFiles, boolean includeFiles)
            throws DataException {
        SQLiteDatabase db = SystemDatabase.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("files", "project_id=?", new String[]{projectId});
            db.delete("folders", "project_id=?", new String[]{projectId});
            db.delete("project_databases", "project_id=?", new String[]{projectId});

            if (includeFiles) {
                File filesDir = StorageLayout.ensure(layout.filesDir(projectId));
                File[] stored = filesDir.listFiles();
                if (stored != null) {
                    for (File f : stored) {
                        if (!f.isFile()) continue;
                        Records.FileRecord known = knownFiles.get(f.getName());
                        insertFileMetadata(db, projectId, f.getName(), f, known);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        // Re-adopt databases from disk and re-create the logical folders.
        DatabaseManager.list(context, projectId);
        for (Records.FileRecord file : FileStore.listAll(context, projectId)) {
            if (file.folderPath != null && !file.folderPath.isEmpty()) {
                ContentValues values = new ContentValues();
                values.put("id", StorageLayout.generateId());
                values.put("project_id", projectId);
                values.put("path", file.folderPath);
                values.put("created_at", System.currentTimeMillis());
                db.insertWithOnConflict("folders", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
        }
    }

    private static void insertFileMetadata(SQLiteDatabase db, String projectId, String storageKey,
                                           File content, Records.FileRecord known) {
        long modified = content.lastModified();
        ContentValues values = new ContentValues();
        values.put("id", StorageLayout.generateId());
        values.put("project_id", projectId);
        values.put("original_name", known != null && known.originalName != null
                ? known.originalName : storageKey);
        values.put("storage_key", storageKey);
        values.put("folder_path", known == null || known.folderPath == null ? "" : known.folderPath);
        values.put("mime_type", known == null || known.mimeType == null
                ? MimeTypes.guess(known == null ? storageKey : known.originalName) : known.mimeType);
        values.put("size_bytes", known != null && known.sizeBytes > 0 ? known.sizeBytes : content.length());
        values.put("checksum", known == null ? null : known.checksum);
        values.put("description", known == null || known.description == null ? "" : known.description);
        values.put("tags", known == null || known.tags == null ? "" : known.tags);
        values.put("created_at", known == null || known.createdAt == 0 ? modified : known.createdAt);
        values.put("updated_at", known == null || known.updatedAt == 0 ? modified : known.updatedAt);
        db.insertWithOnConflict("files", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Replaces {@code target} with {@code source}, keeping the old copy until the swap worked. */
    private static void replaceDir(File source, File target) throws DataException {
        StorageLayout.ensure(target.getParentFile());
        File previous = null;
        if (target.exists()) {
            previous = new File(target.getParentFile(),
                    target.getName() + ".replaced-" + System.currentTimeMillis());
            if (!target.renameTo(previous)) {
                throw new DataException("could not replace existing project data", 500);
            }
        }
        boolean swapped = true;
        if (source != null && source.exists()) {
            swapped = moveDirectory(source, target);
        }
        if (!swapped) {
            if (previous != null) {
                //noinspection ResultOfMethodCallIgnored
                previous.renameTo(target);
            }
            throw new DataException("could not install restored data", 500);
        }
        if (previous != null) deleteRecursively(previous);
    }

    private static boolean moveDirectory(File source, File target) {
        if (source.renameTo(target)) return true;
        StorageLayout.ensure(target);
        File[] children = source.listFiles();
        if (children == null) return false;
        boolean ok = true;
        for (File child : children) {
            File dest = new File(target, child.getName());
            if (child.isDirectory()) {
                ok &= moveDirectory(child, dest);
            } else {
                try (InputStream in = new java.io.FileInputStream(child);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                } catch (IOException e) {
                    ok = false;
                    break;
                }
            }
        }
        if (ok) deleteRecursively(source);
        return ok;
    }

    private static void writeMetadataFile(Context context, String projectId,
                                          List<Records.DbRecord> databases,
                                          List<Records.FileRecord> files, File out) throws IOException {
        try {
            JSONObject json = new JSONObject();
            json.put("format", METADATA_FORMAT);
            json.put("version", StorageLayout.FORMAT_VERSION);
            json.put("projectId", projectId);
            json.put("createdAt", System.currentTimeMillis());

            JSONArray dbArray = new JSONArray();
            for (Records.DbRecord db : databases) {
                JSONObject o = new JSONObject();
                o.put("name", db.name);
                o.put("fileName", db.fileName);
                o.put("createdAt", db.createdAt);
                dbArray.put(o);
            }
            json.put("databases", dbArray);

            JSONArray fileArray = new JSONArray();
            for (Records.FileRecord file : files) {
                JSONObject o = new JSONObject();
                o.put("storageKey", file.storageKey);
                o.put("originalName", file.originalName);
                o.put("folderPath", file.folderPath);
                o.put("mimeType", file.mimeType);
                o.put("sizeBytes", file.sizeBytes);
                o.put("checksum", file.checksum == null ? "" : file.checksum);
                o.put("description", file.description);
                o.put("tags", file.tags);
                o.put("createdAt", file.createdAt);
                o.put("updatedAt", file.updatedAt);
                fileArray.put(o);
            }
            json.put("files", fileArray);

            JSONArray folderArray = new JSONArray();
            for (Records.FolderRecord folder : FileStore.listFolders(context, projectId)) {
                folderArray.put(folder.path);
            }
            json.put("folders", folderArray);

            try (OutputStream fos = new FileOutputStream(out)) {
                fos.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new IOException("could not build backup metadata", e);
        } catch (DataException e) {
            throw new IOException("could not read project folders: " + e.getMessage(), e);
        }
    }

    private static Map<String, Records.FileRecord> readMetadata(File metadata) {
        Map<String, Records.FileRecord> known = new LinkedHashMap<>();
        if (metadata == null || !metadata.exists()) return known;
        try {
            byte[] bytes = readAll(metadata);
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray files = json.optJSONArray("files");
            if (files == null) return known;
            for (int i = 0; i < files.length(); i++) {
                JSONObject o = files.optJSONObject(i);
                if (o == null) continue;
                Records.FileRecord record = new Records.FileRecord();
                record.storageKey = o.optString("storageKey", "");
                record.originalName = o.optString("originalName", record.storageKey);
                record.folderPath = o.optString("folderPath", "");
                record.mimeType = o.optString("mimeType", "application/octet-stream");
                record.sizeBytes = o.optLong("sizeBytes", 0);
                record.checksum = o.optString("checksum", null);
                record.description = o.optString("description", "");
                record.tags = o.optString("tags", "");
                record.createdAt = o.optLong("createdAt", 0);
                record.updatedAt = o.optLong("updatedAt", 0);
                if (!record.storageKey.isEmpty()) known.put(record.storageKey, record);
            }
        } catch (Exception ignored) {
            // a metadata file we cannot read is not fatal: names fall back to storage keys
        }
        return known;
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    /** Keeps the newest {@link #MAX_BACKUPS_PER_PROJECT} archives per project. */
    private static void enforceRetention(Context context, String projectId) {
        List<Records.BackupRecord> all = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,project_id,file_name,size_bytes,includes_files,valid,note,created_at "
                        + "FROM backups WHERE project_id=? ORDER BY created_at DESC",
                new String[]{projectId})) {
            while (c.moveToNext()) all.add(fromCursor(c));
        }
        for (int i = MAX_BACKUPS_PER_PROJECT; i < all.size(); i++) {
            Records.BackupRecord stale = all.get(i);
            File file = new File(ContentStoreBridge.layout(context).backupsDir(projectId),
                    stale.id + ".zip");
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            SystemDatabase.get(context).getWritableDatabase()
                    .delete("backups", "id=?", new String[]{stale.id});
        }
    }

    private static void updateValid(Context context, String backupId, boolean valid) {
        ContentValues values = new ContentValues();
        values.put("valid", valid ? 1 : 0);
        SystemDatabase.get(context).getWritableDatabase()
                .update("backups", values, "id=?", new String[]{backupId});
    }

    private static String backupDisplayName(String projectName, long timestamp) {
        String safe = PathGuard.sanitizeFileName(projectName);
        if (safe.endsWith(".")) safe = safe.substring(0, safe.length() - 1);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(timestamp));
        return safe + "-backup-" + stamp + ".zip";
    }

    private static Records.BackupRecord fromCursor(Cursor c) {
        Records.BackupRecord r = new Records.BackupRecord();
        r.id = c.getString(0);
        r.projectId = c.getString(1);
        r.fileName = c.getString(2);
        r.sizeBytes = c.getLong(3);
        r.includesFiles = c.getInt(4) != 0;
        r.valid = c.getInt(5) != 0;
        r.note = c.isNull(6) ? "" : c.getString(6);
        r.createdAt = c.getLong(7);
        return r;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
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
}
