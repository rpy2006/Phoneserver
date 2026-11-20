package com.yadaventerprise.phoneserver.data.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.yadaventerprise.phoneserver.data.database.DatabaseManager;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.database.SystemDatabase;
import com.yadaventerprise.phoneserver.network.http.DataException;
import com.yadaventerprise.phoneserver.security.PathGuard;
import com.yadaventerprise.phoneserver.util.MimeTypes;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
        return create(context, projectId, BackupOptions.full(includeFiles, note));
    }

    public static Records.BackupRecord create(Context context, String projectId, BackupOptions options)
            throws DataException {
        BackupOptions opts = options == null ? BackupOptions.full(true, "") : options;
        Records.ProjectRecord project = ProjectStore.require(context, projectId);
        StorageLayout layout = ContentStoreBridge.layout(context);
        String cleanNote = opts.note == null ? "" : opts.note.trim();
        if (cleanNote.length() > 200) throw new DataException("backup note is too long", 400);

        String type = BackupOptions.TYPE_INCREMENTAL.equals(opts.type)
                ? BackupOptions.TYPE_INCREMENTAL : BackupOptions.TYPE_FULL;
        if (BackupOptions.TYPE_RECOVERY.equals(opts.type)) type = BackupOptions.TYPE_RECOVERY;
        boolean includeFiles = opts.includeFiles;
        boolean includeDatabases = opts.includeDatabases;

        String id = StorageLayout.generateId();
        File backupsDir = StorageLayout.ensure(layout.backupsDir(projectId));
        File archive = new File(backupsDir, id + (opts.isEncrypted() ? ".zip.enc" : ".zip"));

        List<Records.DbRecord> databases = includeDatabases ? DatabaseManager.list(context, projectId)
                : new ArrayList<>();
        List<Records.FileRecord> files = includeFiles ? FileStore.listAll(context, projectId)
                : new ArrayList<>();

        Map<String, String> manifest = new LinkedHashMap<>();
        manifest.put("format", BackupArchive.FORMAT);
        manifest.put("version", BackupArchive.VERSION);
        manifest.put("backupType", type);
        manifest.put("projectId", projectId);
        manifest.put("projectName", project.name);
        manifest.put("createdAt", String.valueOf(System.currentTimeMillis()));
        manifest.put("includesFiles", includeFiles ? "true" : "false");
        manifest.put("includesDatabases", includeDatabases ? "true" : "false");
        manifest.put("databaseCount", String.valueOf(databases.size()));
        manifest.put("fileCount", String.valueOf(files.size()));
        manifest.put("encrypted", opts.isEncrypted() ? "true" : "false");
        manifest.put("parentBackupId", opts.parentBackupId == null ? "" : opts.parentBackupId);
        manifest.put("note", cleanNote);
        manifest.put("appVersion", "phoneserver");

        File metadataJson = layout.newTempFile("metadata");
        try {
            writeMetadataFile(context, projectId, databases, files, metadataJson);
        } catch (IOException e) {
            metadataJson.delete();
            throw new DataException("could not write backup metadata", 500);
        }

        List<BackupArchive.Entry> entries = new ArrayList<>();
        entries.add(new BackupArchive.Entry(METADATA_ENTRY, metadataJson));
        File config = layout.projectConfig(projectId);
        if (opts.includeProjectConfig && config.exists()) entries.add(new BackupArchive.Entry(CONFIG_ENTRY, config));

        if (includeDatabases) {
            for (Records.DbRecord db : databases) {
                File file = new File(layout.databasesDir(projectId), db.fileName);
                if (file.exists()) entries.add(new BackupArchive.Entry("databases/" + db.fileName, file));
            }
        }
        if (includeFiles) {
            for (Records.FileRecord file : files) {
                try {
                    File content = FileStore.contentFile(context, file);
                    entries.add(new BackupArchive.Entry("files/" + file.storageKey, content));
                } catch (DataException ignored) {
                }
            }
        }

        File plainArchive = opts.isEncrypted() ? layout.newTempFile("backup-plain") : archive;
        try {
            BackupArchive.create(plainArchive, manifest, entries);
            if (opts.isEncrypted()) {
                BackupCrypto.encrypt(plainArchive, archive, opts.password);
            }
        } catch (IOException e) {
            archive.delete();
            plainArchive.delete();
            throw new DataException("could not write backup archive: " + e.getMessage(), 500);
        } finally {
            metadataJson.delete();
            if (opts.isEncrypted() && plainArchive.exists()) plainArchive.delete();
            opts.clearPassword();
        }

        String checksum = "";
        try {
            checksum = sha256(archive);
        } catch (IOException ignored) {
        }

        long now = System.currentTimeMillis();
        Records.BackupRecord record = new Records.BackupRecord();
        record.id = id;
        record.projectId = projectId;
        record.fileName = backupDisplayName(project.name, now) + (opts.isEncrypted() ? ".enc" : "");
        record.sizeBytes = archive.length();
        record.includesFiles = includeFiles;
        record.valid = true;
        record.encrypted = opts.isEncrypted();
        record.backupType = type;
        record.checksum = checksum;
        record.parentBackupId = opts.parentBackupId == null ? "" : opts.parentBackupId;
        record.note = cleanNote;
        record.createdAt = now;

        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("project_id", projectId);
        values.put("file_name", record.fileName);
        values.put("size_bytes", record.sizeBytes);
        values.put("includes_files", includeFiles ? 1 : 0);
        values.put("valid", 1);
        values.put("encrypted", record.encrypted ? 1 : 0);
        values.put("backup_type", record.backupType);
        values.put("checksum", record.checksum);
        values.put("parent_id", record.parentBackupId);
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
                "SELECT id,project_id,file_name,size_bytes,includes_files,valid,encrypted,backup_type,"
                        + "checksum,parent_id,note,created_at "
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
                "SELECT id,project_id,file_name,size_bytes,includes_files,valid,encrypted,backup_type,"
                        + "checksum,parent_id,note,created_at "
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
        File dir = layout.backupsDir(projectId);
        File file = new File(dir, backupId + ".zip");
        if (!file.exists() || !file.isFile()) file = new File(dir, backupId + ".zip.enc");
        if (!file.exists() || !file.isFile()) throw DataException.notFound("backup");
        return file;
    }

    /** Re-validates the archive on demand and refreshes the stored flag. */
    public static Map<String, String> validate(Context context, String projectId, String backupId)
            throws DataException {
        return validate(context, projectId, backupId, null);
    }

    public static Map<String, String> validate(Context context, String projectId, String backupId, char[] password)
            throws DataException {
        Records.BackupRecord record = require(context, projectId, backupId);
        File file = archiveFile(context, projectId, backupId);
        StorageLayout layout = ContentStoreBridge.layout(context);
        File tempPlain = null;
        try {
            File plain = file;
            if (BackupCrypto.isEncrypted(file) || record.encrypted) {
                if (password == null || password.length == 0) {
                    throw new DataException("backup password is required", 400);
                }
                tempPlain = layout.newTempFile("backup-validate");
                BackupCrypto.decrypt(file, tempPlain, password);
                plain = tempPlain;
            }
            if (record.checksum != null && !record.checksum.isEmpty()) {
                String actual = sha256(plain);
                if (!actual.equalsIgnoreCase(record.checksum)) {
                    updateValid(context, backupId, false);
                    throw new DataException("backup checksum verification failed", 422);
                }
            }
            Map<String, String> manifest = BackupArchive.validate(plain);
            updateValid(context, backupId, true);
            return manifest;
        } catch (IOException e) {
            updateValid(context, backupId, false);
            String msg = e.getMessage() == null ? "backup archive failed validation" : e.getMessage();
            throw new DataException("backup archive failed validation: " + msg, 422);
        } finally {
            if (tempPlain != null) tempPlain.delete();
        }
    }

    public static BackupPreview preview(Context context, String projectId, String backupId, char[] password)
            throws DataException {
        BackupPreview preview = new BackupPreview();
        Records.BackupRecord record = require(context, projectId, backupId);
        File file = archiveFile(context, projectId, backupId);
        preview.encrypted = record.encrypted || BackupCrypto.isEncrypted(file);
        preview.backupType = record.backupType == null ? BackupOptions.TYPE_FULL : record.backupType;
        preview.projectId = record.projectId;
        preview.createdAt = record.createdAt;
        preview.includesFiles = record.includesFiles;
        preview.note = record.note == null ? "" : record.note;
        preview.checksum = record.checksum == null ? "" : record.checksum;
        preview.parentBackupId = record.parentBackupId == null ? "" : record.parentBackupId;
        StorageLayout layout = ContentStoreBridge.layout(context);
        File tempPlain = null;
        try {
            File plain = file;
            if (preview.encrypted) {
                if (password == null || password.length == 0) {
                    preview.valid = false;
                    preview.error = "backup password is required";
                    return preview;
                }
                tempPlain = layout.newTempFile("backup-preview");
                BackupCrypto.decrypt(file, tempPlain, password);
                plain = tempPlain;
            }
            Map<String, String> manifest = BackupArchive.validate(plain);
            preview.valid = true;
            preview.manifest.putAll(manifest);
            preview.format = manifest.get("format") == null ? "" : manifest.get("format");
            preview.version = manifest.get("version") == null ? "" : manifest.get("version");
            preview.projectName = manifest.get("projectName") == null ? "" : manifest.get("projectName");
            preview.includesDatabases = !"false".equals(manifest.get("includesDatabases"));
            Map<String, Records.FileRecord> known = readMetadataFromArchive(plain, layout);
            for (Records.FileRecord f : known.values()) {
                preview.files.add(f.folderPath == null || f.folderPath.isEmpty()
                        ? f.originalName : f.folderPath + "/" + f.originalName);
                if (f.folderPath != null && !f.folderPath.isEmpty() && !preview.folders.contains(f.folderPath)) {
                    preview.folders.add(f.folderPath);
                }
            }
            for (String name : BackupArchive.list(plain)) {
                if (name.startsWith("databases/") && name.endsWith(".db")) {
                    String db = name.substring("databases/".length());
                    if (!preview.databases.contains(db)) preview.databases.add(db);
                }
            }
            java.util.Collections.sort(preview.files);
            java.util.Collections.sort(preview.folders);
            java.util.Collections.sort(preview.databases);
            updateValid(context, backupId, true);
            return preview;
        } catch (Exception e) {
            updateValid(context, backupId, false);
            preview.valid = false;
            preview.error = e.getMessage() == null ? "backup preview failed" : e.getMessage();
            return preview;
        } finally {
            if (tempPlain != null) tempPlain.delete();
        }
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

    public static void restore(Context context, String projectId, String backupId, boolean confirm)
            throws DataException {
        RestoreOptions options = new RestoreOptions();
        options.confirm = confirm;
        restore(context, projectId, backupId, options);
    }

    public static void restore(Context context, String projectId, String backupId, RestoreOptions options)
            throws DataException {
        RestoreOptions opts = options == null ? new RestoreOptions() : options;
        try {
            if (!opts.confirm) {
                throw new DataException("restore requires confirm=true (it replaces existing data)", 400);
            }
            Records.BackupRecord record = require(context, projectId, backupId);
            File archive = archiveFile(context, projectId, backupId);
            restoreFromArchive(context, projectId, archive, record.includesFiles, opts, "restore", record.id);
        } finally {
            opts.clearPassword();
        }
    }

    /** Restores an externally uploaded archive into a project (used by import). */
    public static void importArchive(Context context, String projectId, File archive, boolean confirm)
            throws DataException {
        RestoreOptions options = new RestoreOptions();
        options.confirm = confirm;
        importArchive(context, projectId, archive, options);
    }

    public static void importArchive(Context context, String projectId, File archive, RestoreOptions options)
            throws DataException {
        ProjectStore.require(context, projectId);
        RestoreOptions opts = options == null ? new RestoreOptions() : options;
        try {
            if (!opts.confirm) {
                throw new DataException("import requires confirm=true (it replaces existing data)", 400);
            }
            if (BackupCrypto.isEncrypted(archive) && !opts.isEncryptedPasswordProvided()) {
                throw new DataException("backup password is required", 400);
            }
            restoreFromArchive(context, projectId, archive, true, opts, "import", null);
        } finally {
            opts.clearPassword();
        }
    }

    private static void restoreFromArchive(Context context, String projectId, File archive,
                                           boolean includeFiles, RestoreOptions options, String action,
                                           String recoverySourceId)
            throws DataException {
        ProjectStore.require(context, projectId);
        StorageLayout layout = ContentStoreBridge.layout(context);
        File staging = new File(StorageLayout.ensure(layout.tmpDir()),
                "restore-" + StorageLayout.generateId());
        File plainArchive = null;
        String recoveryId = null;
        try {
            File sourceArchive = archive;
            if (BackupCrypto.isEncrypted(archive)) {
                if (!options.isEncryptedPasswordProvided()) {
                    throw new DataException("backup password is required", 400);
                }
                plainArchive = layout.newTempFile("restore-plain");
                BackupCrypto.decrypt(archive, plainArchive, options.password);
                sourceArchive = plainArchive;
            }
            Map<String, String> manifest;
            try {
                manifest = BackupArchive.validate(sourceArchive);
            } catch (IOException e) {
                throw new DataException("not a valid PhoneServer backup: " + e.getMessage(), 422);
            }
            boolean includesFiles = includeFiles && !"false".equals(manifest.get("includesFiles")) && options.restoreFiles;
            boolean includesDatabases = !"false".equals(manifest.get("includesDatabases")) && options.restoreDatabases;
            boolean includesConfig = options.restoreProjectConfig;

            try {
                BackupArchive.extract(sourceArchive, staging, BackupArchive.DEFAULT_MAX_TOTAL_BYTES);
            } catch (IOException e) {
                throw new DataException("could not extract backup: " + e.getMessage(), 422);
            }

            if (options.createRecoveryPoint) {
                BackupOptions recovery = new BackupOptions();
                recovery.type = BackupOptions.TYPE_RECOVERY;
                recovery.includeFiles = true;
                recovery.includeDatabases = true;
                recovery.includeProjectConfig = true;
                recovery.note = "Recovery point before " + action;
                recoveryId = create(context, projectId, recovery).id;
            }

            Map<String, Records.FileRecord> knownFiles = readMetadata(new File(staging, METADATA_ENTRY));
            if (includesDatabases) {
                replaceDir(new File(staging, "databases"), layout.databasesDir(projectId));
            }
            if (includesFiles) {
                replaceDir(new File(staging, "files"), layout.filesDir(projectId));
            }
            if (includesConfig) {
                File cfg = new File(staging, CONFIG_ENTRY);
                if (cfg.exists()) {
                    StorageLayout.ensure(layout.projectDir(projectId));
                    copyFile(cfg, layout.projectConfig(projectId));
                }
            }
            rebuildMetadata(context, projectId, layout, knownFiles, includesFiles, includesDatabases);
            ProjectStore.touch(context, projectId);
            ProjectStore.logOperation(context, projectId, "backup." + action, archive.getName());
            recoveryId = null;
        } catch (Exception e) {
            if (recoveryId != null) {
                try {
                    RestoreOptions rollback = new RestoreOptions();
                    rollback.confirm = true;
                    rollback.createRecoveryPoint = false;
                    restore(context, projectId, recoveryId, rollback);
                } catch (Exception ignored) {
                }
            }
            if (e instanceof DataException) throw (DataException) e;
            throw new DataException("restore failed: " + safeMessage(e), 500);
        } finally {
            if (plainArchive != null) plainArchive.delete();
            deleteRecursively(staging);
        }
    }

    // ---- internals ----

    private static void rebuildMetadata(Context context, String projectId, StorageLayout layout,
                                        Map<String, Records.FileRecord> knownFiles, boolean includeFiles,
                                        boolean includeDatabases)
            throws DataException {
        SQLiteDatabase db = SystemDatabase.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            if (includeFiles) {
                db.delete("files", "project_id=?", new String[]{projectId});
                db.delete("folders", "project_id=?", new String[]{projectId});
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
            if (includeDatabases) {
                db.delete("project_databases", "project_id=?", new String[]{projectId});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (includeDatabases) {
            DatabaseManager.list(context, projectId);
        }
        if (includeFiles) {
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
                "SELECT id,project_id,file_name,size_bytes,includes_files,valid,encrypted,backup_type,"
                        + "checksum,parent_id,note,created_at "
                        + "FROM backups WHERE project_id=? ORDER BY created_at DESC",
                new String[]{projectId})) {
            while (c.moveToNext()) all.add(fromCursor(c));
        }
        for (int i = MAX_BACKUPS_PER_PROJECT; i < all.size(); i++) {
            Records.BackupRecord stale = all.get(i);
            try {
                File file = archiveFile(context, projectId, stale.id);
                if (file.exists()) file.delete();
            } catch (Exception ignored) {
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
        r.encrypted = c.getInt(6) != 0;
        r.backupType = c.isNull(7) ? BackupOptions.TYPE_FULL : c.getString(7);
        r.checksum = c.isNull(8) ? "" : c.getString(8);
        r.parentBackupId = c.isNull(9) ? "" : c.getString(9);
        r.note = c.isNull(10) ? "" : c.getString(10);
        r.createdAt = c.getLong(11);
        return r;
    }


    private static Map<String, Records.FileRecord> readMetadataFromArchive(File plainZip, StorageLayout layout) {
        File tmp = new File(layout.tmpDir(), "meta-" + StorageLayout.generateId());
        try {
            BackupArchive.extract(plainZip, tmp, 64L * 1024 * 1024);
            return readMetadata(new File(tmp, METADATA_ENTRY));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) StorageLayout.ensure(parent);
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private static String sha256(File file) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("could not compute checksum", e);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e == null ? null : e.getMessage();
        return message == null || message.isEmpty() ? (e == null ? "unknown" : e.getClass().getSimpleName()) : message;
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
