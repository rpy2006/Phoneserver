package com.yadaventerprise.phoneserver.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Owns the SQLite engines used for user data: one database file per logical
 * database inside a project. Each connection enables foreign keys, WAL and a
 * busy timeout, all statements go through {@link SqlGuard}, results are
 * paginated, and long scans are bounded by a time budget.
 */
public final class DatabaseManager {

    public static final int DEFAULT_ROW_LIMIT = 100;
    public static final int MAX_ROW_LIMIT = 1000;
    public static final long DEFAULT_TIME_BUDGET_MS = 5000;
    public static final int MAX_DATABASES_PER_PROJECT = 50;

    private DatabaseManager() {
    }

    // ---- lifecycle ----

    public static Records.DbRecord create(Context context, String projectId, String name) throws DataException {
        ProjectStore.require(context, projectId);
        String clean = validateDatabaseName(name);
        StorageLayout layout = ContentStoreBridge.layout(context);
        File dir = StorageLayout.ensure(layout.databasesDir(projectId));
        File dbFile = safeDatabaseFile(dir, clean);
        if (dbFile == null) throw new DataException("invalid database name", 400);

        if (list(context, projectId).size() >= MAX_DATABASES_PER_PROJECT) {
            throw new DataException("project already has the maximum number of databases", 409);
        }
        if (dbFile.exists()) {
            throw DataException.conflict("database '" + clean + "' already exists");
        }

        long now = System.currentTimeMillis();
        Records.DbRecord record = new Records.DbRecord();
        record.id = StorageLayout.generateId();
        record.projectId = projectId;
        record.name = clean;
        record.fileName = clean + ".db";
        record.createdAt = now;
        record.updatedAt = now;

        // Create the file and configure it before recording metadata, so we
        // never advertise a database that failed to open.
        SQLiteDatabase db = openFile(dbFile, true);
        try {
            db.execSQL("PRAGMA auto_vacuum = INCREMENTAL");
        } finally {
            db.close();
        }
        record.sizeBytes = dbFile.length();

        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("project_id", projectId);
        values.put("name", record.name);
        values.put("file_name", record.fileName);
        values.put("size_bytes", record.sizeBytes);
        values.put("created_at", now);
        values.put("updated_at", now);
        SystemDatabase.get(context).getWritableDatabase().insertOrThrow("project_databases", null, values);

        ProjectStore.touch(context, projectId);
        ProjectStore.logOperation(context, projectId, "database.create", clean);
        return record;
    }

    /** Scans the project directory and reconciles the metadata table with what is on disk. */
    public static List<Records.DbRecord> list(Context context, String projectId) throws DataException {
        ProjectStore.require(context, projectId);
        StorageLayout layout = ContentStoreBridge.layout(context);
        File dir = layout.databasesDir(projectId);
        StorageLayout.ensure(dir);

        List<Records.DbRecord> result = new ArrayList<>();
        SQLiteDatabase db = SystemDatabase.get(context).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id,project_id,name,file_name,size_bytes,created_at,updated_at "
                        + "FROM project_databases WHERE project_id=? ORDER BY name", new String[]{projectId})) {
            while (c.moveToNext()) {
                Records.DbRecord r = new Records.DbRecord();
                r.id = c.getString(0);
                r.projectId = c.getString(1);
                r.name = c.getString(2);
                r.fileName = c.getString(3);
                r.sizeBytes = c.getLong(4);
                r.createdAt = c.getLong(5);
                r.updatedAt = c.getLong(6);
                File f = new File(dir, r.fileName);
                if (f.exists()) r.sizeBytes = f.length();
                result.add(r);
            }
        }

        // Adopt database files that exist on disk but not in metadata (e.g. after
        // a manual restore) so nothing becomes invisible.
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".db")) continue;
                boolean known = false;
                for (Records.DbRecord r : result) {
                    if (r.fileName.equals(f.getName())) { known = true; break; }
                }
                if (!known) {
                    String base = f.getName().substring(0, f.getName().length() - 3);
                    Records.DbRecord r = new Records.DbRecord();
                    r.id = StorageLayout.generateId();
                    r.projectId = projectId;
                    r.name = base;
                    r.fileName = f.getName();
                    r.sizeBytes = f.length();
                    r.createdAt = f.lastModified();
                    r.updatedAt = f.lastModified();
                    ContentValues values = new ContentValues();
                    values.put("id", r.id);
                    values.put("project_id", projectId);
                    values.put("name", r.name);
                    values.put("file_name", r.fileName);
                    values.put("size_bytes", r.sizeBytes);
                    values.put("created_at", r.createdAt);
                    values.put("updated_at", r.updatedAt);
                    try {
                        SystemDatabase.get(context).getWritableDatabase()
                                .insertWithOnConflict("project_databases", null, values,
                                        SQLiteDatabase.CONFLICT_IGNORE);
                    } catch (Exception ignored) {
                    }
                    result.add(r);
                }
            }
        }
        return result;
    }

    public static Records.DbRecord require(Context context, String projectId, String idOrName) throws DataException {
        for (Records.DbRecord r : list(context, projectId)) {
            if (r.id.equals(idOrName) || r.name.equalsIgnoreCase(idOrName)) return r;
        }
        throw DataException.notFound("database");
    }

    public static void delete(Context context, String projectId, String idOrName) throws DataException {
        Records.DbRecord record = require(context, projectId, idOrName);
        File file = databaseFile(context, projectId, record.name);
        // Remove sidecar files too (WAL/SHM), otherwise a re-created database
        // with the same name could inherit an old WAL.
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File f = new File(file.getAbsolutePath() + suffix);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        SystemDatabase.get(context).getWritableDatabase()
                .delete("project_databases", "id=?", new String[]{record.id});
        ProjectStore.logOperation(context, projectId, "database.delete", record.name);
    }

    public static File databaseFile(Context context, String projectId, String name) throws DataException {
        String clean = validateDatabaseName(name);
        File dir = StorageLayout.ensure(ContentStoreBridge.layout(context).databasesDir(projectId));
        File file = safeDatabaseFile(dir, clean);
        if (file == null || !PathGuard.isInside(dir, file)) {
            throw new DataException("invalid database name", 400);
        }
        return file;
    }

    // ---- schema inspection ----

    public static List<Records.TableInfo> listTables(Context context, String projectId, String dbName)
            throws DataException {
        File file = requireExisting(context, projectId, dbName);
        List<Records.TableInfo> tables = new ArrayList<>();
        SQLiteDatabase db = openFile(file, false);
        try (Cursor c = db.rawQuery(
                "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') "
                        + "AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
            while (c.moveToNext()) {
                Records.TableInfo t = new Records.TableInfo();
                t.name = c.getString(0);
                t.type = c.getString(1);
                if ("table".equals(t.type)) {
                    t.rowCount = countRows(db, t.name);
                    t.columns = columns(db, t.name);
                }
                tables.add(t);
            }
        } finally {
            db.close();
        }
        return tables;
    }

    public static Records.TableInfo tableInfo(Context context, String projectId, String dbName, String table)
            throws DataException {
        File file = requireExisting(context, projectId, dbName);
        SQLiteDatabase db = openFile(file, false);
        try {
            Records.TableInfo t = new Records.TableInfo();
            t.name = table;
            t.columns = columns(db, table);
            if (t.columns.isEmpty()) throw DataException.notFound("table");
            t.rowCount = countRows(db, table);
            t.indexes = indexes(db, table);
            return t;
        } finally {
            db.close();
        }
    }

    // ---- query execution ----

    public static Records.QueryResult execute(Context context, String projectId, String dbName, String sql,
                                              List<Object> params, Integer limit, Integer offset,
                                              Long timeBudgetMs, boolean allowWrite) throws DataException {
        File file = requireExisting(context, projectId, dbName);
        SqlGuard.Result guard = SqlGuard.inspect(sql, allowWrite);
        switch (guard.verdict) {
            case EMPTY:
                throw new DataException("no SQL supplied");
            case MULTIPLE_STATEMENTS:
            case FORBIDDEN:
            case WRITE_DISALLOWED:
                throw DataException.forbidden(guard.reason);
            default:
                break;
        }

        int rowLimit = limit == null ? DEFAULT_ROW_LIMIT : Math.max(1, Math.min(limit, MAX_ROW_LIMIT));
        int rowOffset = offset == null ? 0 : Math.max(0, offset);
        long budget = timeBudgetMs == null ? DEFAULT_TIME_BUDGET_MS : Math.max(50, Math.min(timeBudgetMs, 30000));

        SQLiteDatabase db = openFile(file, true);
        long start = System.currentTimeMillis();
        Records.QueryResult result = new Records.QueryResult();
        try {
            String[] args = bindArguments(sql, params);
            if (SqlGuard.isReadOnlyKeyword(guard.keyword)) {
                result.isSelect = true;
                result.keyword = guard.keyword;
                runQuery(db, sql, args, result, rowLimit, rowOffset, budget, start);
            } else {
                result.keyword = guard.keyword;
                result.rowsAffected = runWrite(db, sql, args, guard.keyword);
            }
        } catch (android.database.sqlite.SQLiteException e) {
            throw new DataException("SQL error: " + safeMessage(e), 400);
        } finally {
            result.durationMs = System.currentTimeMillis() - start;
            db.close();
        }

        updateDatabaseSize(context, projectId, file);
        return result;
    }

    private static void runQuery(SQLiteDatabase db, String sql, String[] args, Records.QueryResult result,
                                 int rowLimit, int rowOffset, long budget, long start) throws DataException {
        try (Cursor c = db.rawQuery(sql, args)) {
            String[] names = c.getColumnNames();
            for (String name : names) result.columns.add(name);
            if (rowOffset > 0) {
                if (!c.moveToPosition(rowOffset)) return;
            } else {
                if (!c.moveToFirst()) return;
            }
            int collected = 0;
            do {
                if (System.currentTimeMillis() - start > budget) {
                    throw new DataException("query exceeded the time budget", 408);
                }
                List<Object> row = new ArrayList<>(names.length);
                for (int i = 0; i < names.length; i++) {
                    row.add(valueAt(c, i));
                }
                result.rows.add(row);
                collected++;
                if (collected > rowLimit) {
                    result.rows.remove(result.rows.size() - 1);
                    result.truncated = true;
                    break;
                }
            } while (c.moveToNext());
        }
    }

    private static int runWrite(SQLiteDatabase db, String sql, String[] args, String keyword) {
        if (Arrays.asList("insert", "update", "delete", "replace").contains(keyword)) {
            SQLiteStatement statement = db.compileStatement(sql);
            try {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] == null) statement.bindNull(i + 1);
                        else statement.bindString(i + 1, args[i]);
                    }
                }
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        } else {
            if (args == null || args.length == 0) db.execSQL(sql);
            else db.execSQL(sql, args);
            return 0;
        }
    }

    private static Object valueAt(Cursor c, int index) {
        switch (c.getType(index)) {
            case Cursor.FIELD_TYPE_NULL:
                return null;
            case Cursor.FIELD_TYPE_INTEGER:
                return c.getLong(index);
            case Cursor.FIELD_TYPE_FLOAT:
                return c.getDouble(index);
            case Cursor.FIELD_TYPE_BLOB:
                byte[] blob = c.getBlob(index);
                return blob == null ? null : "base64:" + Base64.encodeToString(blob, Base64.NO_WRAP);
            default:
                return c.getString(index);
        }
    }

    // ---- transactions over multiple statements ----

    public static Records.QueryResult executeTransaction(Context context, String projectId, String dbName,
                                                         List<String> statements, List<List<Object>> params,
                                                         boolean allowWrite) throws DataException {
        File file = requireExisting(context, projectId, dbName);
        for (String sql : statements) {
            SqlGuard.Result guard = SqlGuard.inspect(sql, allowWrite);
            if (!guard.isOk()) throw DataException.forbidden(guard.reason);
        }
        SQLiteDatabase db = openFile(file, true);
        long start = System.currentTimeMillis();
        Records.QueryResult result = new Records.QueryResult();
        db.beginTransaction();
        try {
            for (int i = 0; i < statements.size(); i++) {
                String[] args = bindArguments(statements.get(i),
                        params != null && i < params.size() ? params.get(i) : null);
                SqlGuard.Result guard = SqlGuard.inspect(statements.get(i), allowWrite);
                result.rowsAffected += runWrite(db, statements.get(i), args, guard.keyword);
            }
            db.setTransactionSuccessful();
        } catch (android.database.sqlite.SQLiteException e) {
            throw new DataException("SQL error: " + safeMessage(e), 400);
        } finally {
            db.endTransaction();
            result.durationMs = System.currentTimeMillis() - start;
            db.close();
        }
        updateDatabaseSize(context, projectId, file);
        return result;
    }

    // ---- export / import ----

    public static void exportTo(Context context, String projectId, String dbName, File dest) throws DataException {
        File file = requireExisting(context, projectId, dbName);
        StorageLayout.ensure(dest.getParentFile());
        if (dest.exists() && !dest.delete()) {
            throw new DataException("could not prepare export file", 500);
        }
        SQLiteDatabase db = openFile(file, false);
        try {
            try {
                db.execSQL("VACUUM INTO " + quote(dest.getAbsolutePath()));
            } catch (android.database.sqlite.SQLiteException vacuumUnsupported) {
                // Older SQLite: checkpoint WAL then copy the file bytes.
                db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
                db.close();
                copyFile(file, dest);
                return;
            }
        } finally {
            if (db.isOpen()) db.close();
        }
    }

    public static void importFrom(Context context, String projectId, String dbName, File source, boolean confirm)
            throws DataException {
        File target = databaseFile(context, projectId, dbName);
        if (!confirm) {
            throw new DataException("import requires confirm=true (it replaces the existing database)", 400);
        }
        if (!source.exists() || source.length() == 0) {
            throw new DataException("uploaded database file is empty or missing", 400);
        }
        verifySqliteFile(source);

        File recoveryDir = StorageLayout.ensure(ContentStoreBridge.layout(context).backupsDir(projectId));
        if (target.exists()) {
            File backup = new File(recoveryDir, target.getName() + ".pre-import-" + System.currentTimeMillis());
            if (!target.renameTo(backup)) {
                throw new DataException("could not preserve the existing database before import", 500);
            }
        }
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            File sidecar = new File(target.getAbsolutePath() + suffix);
            if (sidecar.exists()) {
                //noinspection ResultOfMethodCallIgnored
                sidecar.delete();
            }
        }
        if (!source.renameTo(target)) {
            copyFile(source, target);
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
        updateDatabaseSize(context, projectId, target);
        ProjectStore.logOperation(context, projectId, "database.import", dbName);
    }

    /** Opens a candidate file read-only and asks SQLite to check it over. */
    public static void verifySqliteFile(File file) throws DataException {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor c = db.rawQuery("PRAGMA integrity_check", null)) {
                if (c.moveToFirst()) {
                    String status = c.getString(0);
                    if (!"ok".equalsIgnoreCase(status)) {
                        throw new DataException("file is not a healthy SQLite database", 400);
                    }
                }
            }
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sqlite_master", null)) {
                c.moveToFirst();
            }
        } catch (android.database.sqlite.SQLiteException e) {
            throw new DataException("file is not a valid SQLite database", 400);
        } finally {
            if (db != null) db.close();
        }
    }

    // ---- helpers ----

    private static File requireExisting(Context context, String projectId, String dbName) throws DataException {
        File file = databaseFile(context, projectId, dbName);
        if (!file.exists() || file.length() == 0) {
            // Allow metadata-only entry to be materialised on first use.
            if (!file.exists()) {
                Records.DbRecord record = require(context, projectId, dbName);
                file = databaseFile(context, projectId, record.name);
            }
            if (!file.exists()) throw DataException.notFound("database");
        }
        return file;
    }

    private static SQLiteDatabase openFile(File file, boolean writable) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                writable ? (SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY)
                        : SQLiteDatabase.OPEN_READONLY);
        if (writable) {
            db.setForeignKeyConstraintsEnabled(true);
            try {
                db.enableWriteAheadLogging();
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("PRAGMA busy_timeout = 5000");
            } catch (Exception ignored) {
            }
        }
        return db;
    }

    private static List<Records.ColumnInfo> columns(SQLiteDatabase db, String table) {
        List<Records.ColumnInfo> result = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + quote(table) + ")", null)) {
            while (c.moveToNext()) {
                Records.ColumnInfo col = new Records.ColumnInfo();
                col.name = c.getString(c.getColumnIndexOrThrow("name"));
                col.type = c.getString(c.getColumnIndexOrThrow("type"));
                col.notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) != 0;
                col.primaryKey = c.getInt(c.getColumnIndexOrThrow("pk")) != 0;
                col.defaultValue = c.getString(c.getColumnIndexOrThrow("dflt_value"));
                result.add(col);
            }
        }
        return result;
    }

    private static List<Records.IndexInfo> indexes(SQLiteDatabase db, String table) {
        List<Records.IndexInfo> result = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA index_list(" + quote(table) + ")", null)) {
            while (c.moveToNext()) {
                Records.IndexInfo info = new Records.IndexInfo();
                info.name = c.getString(c.getColumnIndexOrThrow("name"));
                info.unique = c.getInt(c.getColumnIndexOrThrow("unique")) != 0;
                result.add(info);
            }
        }
        for (Records.IndexInfo info : result) {
            try (Cursor c = db.rawQuery("PRAGMA index_info(" + quote(info.name) + ")", null)) {
                while (c.moveToNext()) {
                    info.columns.add(c.getString(c.getColumnIndexOrThrow("name")));
                }
            }
        }
        return result;
    }

    private static long countRows(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + quote(table), null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void updateDatabaseSize(Context context, String projectId, File file) {
        try {
            SystemDatabase.get(context).getWritableDatabase()
                    .execSQL("UPDATE project_databases SET size_bytes=?, updated_at=? "
                                    + "WHERE project_id=? AND file_name=?",
                            new Object[]{file.length(), System.currentTimeMillis(), projectId, file.getName()});
        } catch (Exception ignored) {
        }
    }

    public static String validateDatabaseName(String name) throws DataException {
        if (name == null) throw new DataException("database name is required", 400);
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 64) {
            throw new DataException("database name must be 1-64 characters", 400);
        }
        if (!clean.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) {
            throw new DataException("database name may only contain letters, digits, '_', '.' and '-'", 400);
        }
        if (clean.equalsIgnoreCase("system") || clean.equalsIgnoreCase("sqlite")) {
            throw new DataException("that database name is reserved", 400);
        }
        return clean;
    }

    /** Resolves a database file inside dir, rejecting anything that escapes it. */
    public static File safeDatabaseFile(File dir, String name) {
        if (!name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) return null;
        if (name.contains("..")) return null;
        File candidate = new File(dir, name + ".db");
        return PathGuard.isInside(dir, candidate) ? candidate : null;
    }

    static String[] bindArguments(String sql, List<Object> params) throws DataException {
        if (params == null || params.isEmpty()) {
            return null;
        }
        String[] out = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value == null) out[i] = null;
            else if (value instanceof Boolean) out[i] = ((Boolean) value) ? "1" : "0";
            else out[i] = String.valueOf(value);
        }
        return out;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) return e.getClass().getSimpleName();
        // Never leak absolute paths to API clients.
        return message.replaceAll("(/[\\w./-]+)+", "<path>");
    }

    private static void copyFile(File source, File dest) throws DataException {
        StorageLayout.ensure(dest.getParentFile());
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new DataException("copy failed: " + e.getMessage(), 500);
        }
    }

    public static long directorySize(File dir) {
        long total = 0;
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            for (File f : files) total += f.isDirectory() ? directorySize(f) : f.length();
        }
        return total;
    }

    public static String describe(Records.DbRecord record) {
        return String.format(Locale.US, "%s (%d bytes)", record.name, record.sizeBytes);
    }
}
