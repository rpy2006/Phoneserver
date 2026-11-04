package com.yadaventerprise.phoneserver.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent local file/object storage.
 *
 * File bytes are streamed into the project's {@code files/} directory under a
 * generated storage key; the original name, logical folder, MIME type, size,
 * checksum and timestamps are tracked in the system database. User supplied
 * names are never used as paths, so path traversal is structurally impossible.
 */
public final class FileStore {

    public static final int MAX_FILES_PER_PROJECT = 20000;
    private static final int BUFFER_SIZE = 65536;

    private FileStore() {
    }

    public static class Usage {
        public int fileCount;
        public long bytes;
        public long quotaBytes;
        public long maxUploadBytes;
    }

    public static class Issues {
        public final List<Records.FileRecord> missingFiles = new ArrayList<>();
        public final List<File> orphanFiles = new ArrayList<>();
    }

    /** Streams an upload to disk. The stream is fully consumed or the temp file is removed. */
    public static Records.FileRecord upload(Context context, String projectId, String folderPath,
                                            String originalName, String mimeType, InputStream input,
                                            long declaredLength, String description, String tags)
            throws DataException {
        Records.ProjectRecord project = ProjectStore.require(context, projectId);
        StorageLayout layout = ContentStoreBridge.layout(context);

        String folder = PathGuard.normalizeRelative(folderPath);
        if (folder == null) throw new DataException("invalid folder path", 400);
        String safeName = PathGuard.sanitizeFileName(originalName);
        String mime = (mimeType == null || mimeType.trim().isEmpty())
                ? MimeTypes.guess(safeName) : mimeType.trim();

        long maxUpload = project.maxUploadBytes > 0
                ? project.maxUploadBytes : ContentStoreBridge.defaultMaxUploadBytes(context);
        if (declaredLength > maxUpload) {
            throw DataException.tooLarge("file exceeds the " + maxUpload + " byte upload limit");
        }

        long quota = project.fileQuotaBytes;
        Usage current = usage(context, projectId);
        if (quota > 0 && declaredLength > 0 && current.bytes + declaredLength > quota) {
            throw DataException.tooLarge("project file quota exceeded");
        }
        if (current.fileCount >= MAX_FILES_PER_PROJECT) {
            throw DataException.conflict("project has reached the maximum number of files");
        }

        ensureFolder(context, projectId, folder);

        String storageKey = StorageLayout.generateId();
        File temp = layout.newTempFile("upload");
        long written = 0;
        String checksum;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > maxUpload) {
                        throw DataException.tooLarge("file exceeds the upload limit");
                    }
                    if (quota > 0 && current.bytes + written > quota) {
                        throw DataException.tooLarge("project file quota exceeded");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            checksum = toHex(digest.digest());
        } catch (DataException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new DataException("upload failed while writing data", 500);
        }

        File dest = layout.contentFile(projectId, storageKey);
        StorageLayout.ensure(dest.getParentFile());
        if (!temp.renameTo(dest)) {
            try (InputStream in = new FileInputStream(temp); OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                throw new DataException("upload failed while finalising file", 500);
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }

        long now = System.currentTimeMillis();
        Records.FileRecord record = new Records.FileRecord();
        record.id = StorageLayout.generateId();
        record.projectId = projectId;
        record.originalName = safeName;
        record.storageKey = storageKey;
        record.folderPath = folder;
        record.mimeType = mime;
        record.sizeBytes = written;
        record.checksum = checksum;
        record.description = description == null ? "" : description.trim();
        record.tags = tags == null ? "" : tags.trim();
        record.createdAt = now;
        record.updatedAt = now;

        insert(context, record);
        ProjectStore.touch(context, projectId);
        ProjectStore.logOperation(context, projectId, "file.upload",
                safeName + " (" + written + " bytes)");
        return record;
    }

    public static List<Records.FileRecord> list(Context context, String projectId, String folderPath)
            throws DataException {
        ProjectStore.require(context, projectId);
        String folder = PathGuard.normalizeRelative(folderPath);
        if (folder == null) throw new DataException("invalid folder path", 400);

        List<Records.FileRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,project_id,original_name,storage_key,folder_path,mime_type,size_bytes,"
                        + "checksum,description,tags,created_at,updated_at FROM files "
                        + "WHERE project_id=? AND folder_path=? ORDER BY original_name COLLATE NOCASE",
                new String[]{projectId, folder})) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public static List<Records.FileRecord> listAll(Context context, String projectId) throws DataException {
        ProjectStore.require(context, projectId);
        List<Records.FileRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,project_id,original_name,storage_key,folder_path,mime_type,size_bytes,"
                        + "checksum,description,tags,created_at,updated_at FROM files "
                        + "WHERE project_id=? ORDER BY folder_path, original_name COLLATE NOCASE",
                new String[]{projectId})) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public static Records.FileRecord require(Context context, String projectId, String fileId) throws DataException {
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,project_id,original_name,storage_key,folder_path,mime_type,size_bytes,"
                        + "checksum,description,tags,created_at,updated_at FROM files "
                        + "WHERE project_id=? AND id=?", new String[]{projectId, fileId})) {
            if (c.moveToFirst()) return fromCursor(c);
        }
        throw DataException.notFound("file");
    }

    public static File contentFile(Context context, Records.FileRecord record) throws DataException {
        File file = ContentStoreBridge.layout(context).contentFile(record.projectId, record.storageKey);
        if (!file.exists() || !file.isFile()) {
            throw new DataException("stored file content is missing on disk", 410);
        }
        return file;
    }

    public static void delete(Context context, String projectId, String fileId) throws DataException {
        Records.FileRecord record = require(context, projectId, fileId);
        File file = ContentStoreBridge.layout(context).contentFile(projectId, record.storageKey);
        if (file.exists() && !file.delete()) {
            throw new DataException("could not delete stored file", 500);
        }
        SystemDatabase.get(context).getWritableDatabase()
                .delete("files", "id=?", new String[]{record.id});
        ProjectStore.logOperation(context, projectId, "file.delete", record.originalName);
    }

    public static Records.FileRecord rename(Context context, String projectId, String fileId, String newName)
            throws DataException {
        Records.FileRecord record = require(context, projectId, fileId);
        if (!PathGuard.isValidSegment(newName)) throw new DataException("invalid file name", 400);
        String safe = PathGuard.sanitizeFileName(newName);
        updateMeta(context, record, null, safe, null, null, null);
        ProjectStore.logOperation(context, projectId, "file.rename", record.originalName + " -> " + safe);
        return require(context, projectId, fileId);
    }

    public static Records.FileRecord move(Context context, String projectId, String fileId, String destFolder)
            throws DataException {
        Records.FileRecord record = require(context, projectId, fileId);
        String folder = PathGuard.normalizeRelative(destFolder);
        if (folder == null) throw new DataException("invalid destination folder", 400);
        ensureFolder(context, projectId, folder);
        updateMeta(context, record, folder, null, null, null, null);
        ProjectStore.logOperation(context, projectId, "file.move",
                record.originalName + " -> " + (folder.isEmpty() ? "/" : folder));
        return require(context, projectId, fileId);
    }

    public static void createFolder(Context context, String projectId, String path) throws DataException {
        ProjectStore.require(context, projectId);
        String folder = PathGuard.normalizeRelative(path);
        if (folder == null || folder.isEmpty()) throw new DataException("invalid folder path", 400);
        ensureFolder(context, projectId, folder);
        ProjectStore.logOperation(context, projectId, "folder.create", folder);
    }

    public static List<Records.FolderRecord> listFolders(Context context, String projectId) throws DataException {
        ProjectStore.require(context, projectId);
        List<Records.FolderRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,project_id,path,created_at FROM folders WHERE project_id=? ORDER BY path",
                new String[]{projectId})) {
            while (c.moveToNext()) {
                Records.FolderRecord f = new Records.FolderRecord();
                f.id = c.getString(0);
                f.projectId = c.getString(1);
                f.path = c.getString(2);
                f.createdAt = c.getLong(3);
                result.add(f);
            }
        }
        return result;
    }

    public static void deleteFolder(Context context, String projectId, String path) throws DataException {
        String folder = PathGuard.normalizeRelative(path);
        if (folder == null || folder.isEmpty()) throw new DataException("invalid folder path", 400);
        SQLiteDatabase db = SystemDatabase.get(context).getWritableDatabase();
        int files = 0;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM files WHERE project_id=? AND folder_path=?",
                new String[]{projectId, folder})) {
            if (c.moveToFirst()) files = c.getInt(0);
        }
        if (files > 0) throw new DataException("folder is not empty", 409);
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM folders WHERE project_id=? AND path LIKE ?",
                new String[]{projectId, folder + "/%"})) {
            if (c.moveToFirst() && c.getInt(0) > 0) {
                throw new DataException("folder contains subfolders", 409);
            }
        }
        db.delete("folders", "project_id=? AND path=?", new String[]{projectId, folder});
        ProjectStore.logOperation(context, projectId, "folder.delete", folder);
    }

    public static Usage usage(Context context, String projectId) {
        Usage usage = new Usage();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*), IFNULL(SUM(size_bytes),0) FROM files WHERE project_id=?",
                new String[]{projectId})) {
            if (c.moveToFirst()) {
                usage.fileCount = c.getInt(0);
                usage.bytes = c.getLong(1);
            }
        }
        Records.ProjectRecord project = ProjectStore.get(context, projectId);
        if (project != null) {
            usage.quotaBytes = project.fileQuotaBytes;
            usage.maxUploadBytes = project.maxUploadBytes > 0
                    ? project.maxUploadBytes : ContentStoreBridge.defaultMaxUploadBytes(context);
        }
        return usage;
    }

    /** Reports metadata rows without content and content without metadata (or with a mismatched size). */
    public static Issues detectIssues(Context context, String projectId) throws DataException {
        Issues issues = new Issues();
        StorageLayout layout = ContentStoreBridge.layout(context);
        File dir = StorageLayout.ensure(layout.filesDir(projectId));

        java.util.Set<String> knownKeys = new java.util.HashSet<>();
        for (Records.FileRecord record : listAll(context, projectId)) {
            knownKeys.add(record.storageKey);
            File content = layout.contentFile(projectId, record.storageKey);
            if (!content.exists() || content.length() != record.sizeBytes) {
                issues.missingFiles.add(record);
            }
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !knownKeys.contains(f.getName())) {
                    issues.orphanFiles.add(f);
                }
            }
        }
        return issues;
    }

    // ---- helpers ----

    private static void ensureFolder(Context context, String projectId, String folder) {
        if (folder == null || folder.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("id", StorageLayout.generateId());
        values.put("project_id", projectId);
        values.put("path", folder);
        values.put("created_at", System.currentTimeMillis());
        try {
            SystemDatabase.get(context).getWritableDatabase()
                    .insertWithOnConflict("folders", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception ignored) {
        }
    }

    private static void insert(Context context, Records.FileRecord record) {
        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("project_id", record.projectId);
        values.put("original_name", record.originalName);
        values.put("storage_key", record.storageKey);
        values.put("folder_path", record.folderPath);
        values.put("mime_type", record.mimeType);
        values.put("size_bytes", record.sizeBytes);
        values.put("checksum", record.checksum);
        values.put("description", record.description);
        values.put("tags", record.tags);
        values.put("created_at", record.createdAt);
        values.put("updated_at", record.updatedAt);
        SystemDatabase.get(context).getWritableDatabase().insertOrThrow("files", null, values);
    }

    private static void updateMeta(Context context, Records.FileRecord record, String folder,
                                   String name, String description, String tags, String mime) {
        ContentValues values = new ContentValues();
        if (folder != null) values.put("folder_path", folder);
        if (name != null) values.put("original_name", name);
        if (description != null) values.put("description", description);
        if (tags != null) values.put("tags", tags);
        if (mime != null) values.put("mime_type", mime);
        values.put("updated_at", System.currentTimeMillis());
        SystemDatabase.get(context).getWritableDatabase()
                .update("files", values, "id=?", new String[]{record.id});
    }

    private static Records.FileRecord fromCursor(Cursor c) {
        Records.FileRecord r = new Records.FileRecord();
        r.id = c.getString(0);
        r.projectId = c.getString(1);
        r.originalName = c.getString(2);
        r.storageKey = c.getString(3);
        r.folderPath = c.getString(4);
        r.mimeType = c.getString(5);
        r.sizeBytes = c.getLong(6);
        r.checksum = c.getString(7);
        r.description = c.getString(8);
        r.tags = c.getString(9);
        r.createdAt = c.getLong(10);
        r.updatedAt = c.getLong(11);
        return r;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
