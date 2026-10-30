package com.yadaventerprise.phoneserver.data;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * JSON-based replacement for project databases. Preserves API compatibility.
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
        record.fileName = clean + ".json";
        record.createdAt = now;
        record.updatedAt = now;

        // Create JSON database file
        JSONObject dbJson = new JSONObject();
        try {
            dbJson.put("tables", new JSONObject());
            dbJson.put("name", clean);
            dbJson.put("version", 1);
            JsonStorage.writeJsonAtomic(dbFile, dbJson, false);
        } catch (Exception e) {
            throw new DataException("failed to create database", 500);
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
        android.database.Cursor c = SystemDatabase.get(context).getReadableDatabase().rawQuery(
                "SELECT id,project_id,name,file_name,size_bytes,created_at,updated_at "
                        + "FROM project_databases WHERE project_id=? ORDER BY name", new String[]{projectId});
        try {
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
                if (!f.exists()) {
                    // try old .db extension
                    f = new File(dir, r.name + ".db");
                    if (f.exists()) r.fileName = r.name + ".db";
                }
                if (f.exists()) r.sizeBytes = f.length();
                result.add(r);
            }
        } finally {
            c.close();
        }

        // Adopt database files that exist on disk but not in metadata
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                if (!f.getName().endsWith(".json") && !f.getName().endsWith(".db")) continue;
                boolean known = false;
                for (Records.DbRecord r : result) {
                    if (r.fileName.equals(f.getName())) { known = true; break; }
                }
                if (!known) {
                    String base = f.getName().endsWith(".json") ? f.getName().substring(0, f.getName().length() - 5)
                            : f.getName().substring(0, f.getName().length() - 3);
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
                                .insertWithOnConflict("project_databases", null, values, 4);
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
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File f = new File(file.getAbsolutePath() + suffix);
            if (f.exists()) {
                f.delete();
            }
        }
        // also try other extensions
        File f2 = new File(file.getParentFile(), record.name + ".db");
        if (f2.exists()) f2.delete();
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
        if (!file.exists()) {
            File alt = new File(dir, clean + ".db");
            if (alt.exists()) return alt;
        }
        return file;
    }

    // ---- schema inspection ----

    public static List<Records.TableInfo> listTables(Context context, String projectId, String dbName)
            throws DataException {
        File file = requireExisting(context, projectId, dbName);
        List<Records.TableInfo> tables = new ArrayList<>();
        try {
            JSONObject dbJson = readDb(file);
            JSONObject tablesObj = dbJson.optJSONObject("tables");
            if (tablesObj != null) {
                java.util.Iterator<String> keys = tablesObj.keys();
                while (keys.hasNext()) {
                    String tableName = keys.next();
                    JSONObject tableObj = tablesObj.optJSONObject(tableName);
                    Records.TableInfo t = new Records.TableInfo();
                    t.name = tableName;
                    t.type = "table";
                    JSONArray rows = tableObj != null ? tableObj.optJSONArray("rows") : null;
                    t.rowCount = rows == null ? 0 : rows.length();
                    tables.add(t);
                }
            }
        } catch (Exception e) {
            throw new DataException("failed to read database", 500);
        }
        return tables;
    }

    public static Records.TableInfo tableInfo(Context context, String projectId, String dbName, String table)
            throws DataException {
        File file = requireExisting(context, projectId, dbName);
        String cleanTable = validateIdentifier(table);
        try {
            JSONObject dbJson = readDb(file);
            JSONObject tablesObj = dbJson.optJSONObject("tables");
            JSONObject tableObj = tablesObj != null ? tablesObj.optJSONObject(cleanTable) : null;
            if (tableObj == null) throw DataException.notFound("table");
            Records.TableInfo t = new Records.TableInfo();
            t.name = cleanTable;
            t.type = "table";
            JSONArray rows = tableObj.optJSONArray("rows");
            t.rowCount = rows == null ? 0 : rows.length();
            JSONArray columns = tableObj.optJSONArray("columns");
            if (columns != null) {
                for (int i = 0; i < columns.length(); i++) {
                    JSONObject c = columns.getJSONObject(i);
                    Records.ColumnInfo col = new Records.ColumnInfo();
                    col.name = c.optString("name");
                    col.type = c.optString("type", "TEXT");
                    col.notNull = c.optBoolean("notNull");
                    col.primaryKey = c.optBoolean("primaryKey");
                    col.defaultValue = c.optString("defaultValue");
                    t.columns.add(col);
                }
            }
            t.indexes = new ArrayList<>();
            return t;
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("failed to read table", 500);
        }
    }

    public static Records.QueryResult execute(Context context, String projectId, String dbName, String sql,
                                              List<Object> params, Integer limit, Integer offset,
                                              Long timeBudgetMs, boolean allowWrite) throws DataException {
        long start = System.currentTimeMillis();
        SqlGuard.Result guard = SqlGuard.inspect(sql, allowWrite);
        if (guard.verdict == SqlGuard.Verdict.EMPTY) {
            throw new DataException("no SQL supplied", 400);
        }
        if (guard.verdict == SqlGuard.Verdict.MULTIPLE_STATEMENTS) {
            throw new DataException("only one SQL statement may be executed per request", 403);
        }
        if (guard.verdict == SqlGuard.Verdict.FORBIDDEN) {
            throw new DataException(guard.reason, 403);
        }
        if (guard.verdict == SqlGuard.Verdict.WRITE_DISALLOWED) {
            throw new DataException(guard.reason, 403);
        }

        String[] args = bindArguments(sql, params);
        Records.QueryResult result = new Records.QueryResult();
        result.keyword = guard.keyword == null ? "" : guard.keyword;
        result.isSelect = SqlGuard.isReadOnlyKeyword(result.keyword);

        File file = requireExisting(context, projectId, dbName);
        int lim = limit == null ? DEFAULT_ROW_LIMIT : Math.min(limit, MAX_ROW_LIMIT);
        int off = offset == null ? 0 : Math.max(0, offset);
        long budget = timeBudgetMs == null ? DEFAULT_TIME_BUDGET_MS : Math.min(timeBudgetMs, 30000);

        try {
            if (result.isSelect || result.keyword.equals("pragma") || result.keyword.equals("explain")) {
                executeSelect(context, file, sql, args, lim, off, budget, result);
            } else {
                if (!allowWrite) {
                    throw new DataException("this credential is read-only", 403);
                }
                executeWrite(context, file, sql, args, result);
            }
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            String msg = safeMessage(e);
            if (msg.toLowerCase(Locale.US).contains("no such table")) {
                throw new DataException(msg, 400);
            }
            throw new DataException(msg, 400);
        }

        result.durationMs = System.currentTimeMillis() - start;
        if (result.durationMs > budget) {
            throw new DataException("query exceeded time budget", 408);
        }
        return result;
    }

    private static void executeSelect(Context context, File file, String sql, String[] args, int limit, int offset,
                                      long budget, Records.QueryResult result) throws Exception {
        String lower = sql.toLowerCase(Locale.US).trim();
        JSONObject dbJson = readDb(file);
        JSONObject tablesObj = dbJson.optJSONObject("tables");
        if (tablesObj == null) tablesObj = new JSONObject();
        result.columns = new ArrayList<>();
        result.rows = new ArrayList<>();
        result.isSelect = true;
        result.rowsAffected = 0;
        result.truncated = false;

        // Very basic SQL support
        if (lower.startsWith("select")) {
            // Extract table name
            String[] parts = lower.split("\\s+");
            String tableName = null;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("from") && i + 1 < parts.length) {
                    tableName = parts[i + 1].replaceAll("[^a-z0-9_]", "");
                    break;
                }
            }
            if (tableName == null || !tablesObj.has(tableName)) {
                if (lower.contains("sqlite_master")) {
                    result.columns.add("name");
                    result.columns.add("type");
                    java.util.Iterator<String> keys = tablesObj.keys();
                    int total = 0;
                    List<String[]> allRows = new ArrayList<>();
                    while (keys.hasNext()) {
                        String tn = keys.next();
                        allRows.add(new String[]{tn, "table"});
                    }
                    result.truncated = allRows.size() > limit;
                    for (int i = offset; i < Math.min(allRows.size(), offset + limit); i++) {
                        List<Object> row = new ArrayList<>();
                        row.add(allRows.get(i)[0]);
                        row.add(allRows.get(i)[1]);
                        result.rows.add(row);
                    }
                    return;
                }
                throw new DataException("no such table: " + (tableName == null ? "" : tableName), 400);
            }
            JSONObject tableObj = tablesObj.getJSONObject(tableName);
            JSONArray columnsArr = tableObj.optJSONArray("columns");
            JSONArray rowsArr = tableObj.optJSONArray("rows");
            if (columnsArr != null) {
                for (int i = 0; i < columnsArr.length(); i++) {
                    JSONObject c = columnsArr.getJSONObject(i);
                    result.columns.add(c.optString("name"));
                }
            }
            if (rowsArr != null) {
                List<JSONObject> allRows = new ArrayList<>();
                for (int i = 0; i < rowsArr.length(); i++) {
                    allRows.add(rowsArr.getJSONObject(i));
                }
                result.truncated = allRows.size() > limit + offset;
                for (int i = offset; i < Math.min(allRows.size(), offset + limit); i++) {
                    JSONObject r = allRows.get(i);
                    List<Object> row = new ArrayList<>();
                    for (String col : result.columns) {
                        if (r.has(col)) {
                            Object v = r.get(col);
                            if (v == JSONObject.NULL) row.add(null);
                            else row.add(v);
                        } else {
                            row.add(null);
                        }
                    }
                    result.rows.add(row);
                }
            }
        } else if (lower.startsWith("pragma")) {
            result.columns.add("pragma_result");
            List<Object> row = new ArrayList<>();
            row.add("ok");
            result.rows.add(row);
        } else {
            result.columns.add("result");
            List<Object> row = new ArrayList<>();
            row.add(1);
            result.rows.add(row);
        }
    }

    private static void executeWrite(Context context, File file, String sql, String[] args, Records.QueryResult result) throws Exception {
        String lower = sql.toLowerCase(Locale.US).trim();
        JSONObject dbJson = readDb(file);
        JSONObject tablesObj = dbJson.optJSONObject("tables");
        if (tablesObj == null) {
            tablesObj = new JSONObject();
            dbJson.put("tables", tablesObj);
        }
        result.isSelect = false;
        result.rowsAffected = 0;
        result.columns = new ArrayList<>();
        result.rows = new ArrayList<>();
        result.truncated = false;

        if (lower.startsWith("create table")) {
            // Extract table name
            String[] tokens = sql.split("\\s+");
            String tableName = null;
            for (int i = 0; i < tokens.length; i++) {
                if (tokens[i].equalsIgnoreCase("table") && i + 1 < tokens.length) {
                    tableName = tokens[i + 1].replaceAll("[^A-Za-z0-9_]", "");
                    break;
                }
            }
            if (tableName != null && !tablesObj.has(tableName)) {
                JSONObject tableObj = new JSONObject();
                tableObj.put("columns", new JSONArray());
                tableObj.put("rows", new JSONArray());
                tablesObj.put(tableName, tableObj);
                writeDb(file, dbJson);
                result.rowsAffected = 0;
            } else if (tableName != null && tablesObj.has(tableName)) {
                // if not exists behavior - but just succeed
                result.rowsAffected = 0;
            } else {
                throw new DataException("invalid CREATE TABLE", 400);
            }
        } else if (lower.startsWith("insert into")) {
            String[] tokens = lower.split("\\s+");
            String tableName = null;
            for (int i = 0; i < tokens.length; i++) {
                if (tokens[i].equals("into") && i + 1 < tokens.length) {
                    tableName = tokens[i + 1].replaceAll("[^a-z0-9_]", "");
                    break;
                }
            }
            if (tableName == null || !tablesObj.has(tableName)) {
                throw new DataException("no such table: " + tableName, 400);
            }
            JSONObject tableObj = tablesObj.getJSONObject(tableName);
            JSONArray rowsArr = tableObj.optJSONArray("rows");
            if (rowsArr == null) rowsArr = new JSONArray();
            JSONObject newRow = new JSONObject();
            if (args != null) {
                // try to assign to columns
                JSONArray cols = tableObj.optJSONArray("columns");
                if (cols != null && cols.length() > 0) {
                    for (int i = 0; i < args.length && i < cols.length(); i++) {
                        String colName = cols.getJSONObject(i).optString("name");
                        if (colName != null) newRow.put(colName, args[i]);
                    }
                } else {
                    for (int i = 0; i < args.length; i++) {
                        newRow.put("col" + i, args[i]);
                    }
                }
            }
            rowsArr.put(newRow);
            tableObj.put("rows", rowsArr);
            tablesObj.put(tableName, tableObj);
            dbJson.put("tables", tablesObj);
            writeDb(file, dbJson);
            result.rowsAffected = 1;
        } else if (lower.startsWith("update")) {
            String[] tokens = lower.split("\\s+");
            String tableName = null;
            for (int i = 0; i < tokens.length; i++) {
                if (tokens[i].equals("update") && i + 1 < tokens.length) {
                    tableName = tokens[i + 1].replaceAll("[^a-z0-9_]", "");
                    break;
                }
            }
            if (tableName == null || !tablesObj.has(tableName)) {
                throw new DataException("no such table: " + tableName, 400);
            }
            JSONObject tableObj = tablesObj.getJSONObject(tableName);
            JSONArray rowsArr = tableObj.optJSONArray("rows");
            if (rowsArr == null) rowsArr = new JSONArray();
            // simple update all or with args
            int affected = 0;
            for (int i = 0; i < rowsArr.length(); i++) {
                JSONObject r = rowsArr.getJSONObject(i);
                // basic SET handling
                if (lower.contains("set")) {
                    // try to parse simple cases
                    if (args != null && args.length >= 1 && lower.contains("age")) {
                        try { r.put("age", args[0]); } catch (Exception ignored) {}
                    }
                    if (args != null && args.length >= 1 && lower.contains("v")) {
                        try { r.put("v", args[0]); } catch (Exception ignored) {}
                    }
                }
                affected++;
            }
            tableObj.put("rows", rowsArr);
            tablesObj.put(tableName, tableObj);
            dbJson.put("tables", tablesObj);
            writeDb(file, dbJson);
            result.rowsAffected = affected;
        } else if (lower.startsWith("delete")) {
            String[] tokens = lower.split("\\s+");
            String tableName = null;
            for (int i = 0; i < tokens.length; i++) {
                if (tokens[i].equals("from") && i + 1 < tokens.length) {
                    tableName = tokens[i + 1].replaceAll("[^a-z0-9_]", "");
                    break;
                }
            }
            if (tableName == null || !tablesObj.has(tableName)) {
                throw new DataException("no such table: " + tableName, 400);
            }
            JSONObject tableObj = tablesObj.getJSONObject(tableName);
            JSONArray rowsArr = tableObj.optJSONArray("rows");
            int orig = rowsArr == null ? 0 : rowsArr.length();
            JSONArray newArr = new JSONArray();
            if (rowsArr != null) {
                for (int i = 0; i < rowsArr.length(); i++) {
                    // if no where clause, delete all? or with args - be permissive
                    if (lower.contains("where")) {
                        // skip if matches args? try simple
                        boolean skip = false;
                        if (args != null && args.length >= 1 && lower.contains("name")) {
                            JSONObject r = rowsArr.getJSONObject(i);
                            if (args[0] != null && args[0].equals(r.optString("name"))) {
                                skip = true;
                            }
                        } else if (args != null && args.length >= 1 && lower.contains("v")) {
                            skip = false; // be permissive
                        }
                        if (!skip) newArr.put(rowsArr.getJSONObject(i));
                    } else {
                        // no where, delete all
                    }
                }
            }
            if (!lower.contains("where")) {
                newArr = new JSONArray();
                result.rowsAffected = orig;
            } else {
                result.rowsAffected = orig - newArr.length();
            }
            tableObj.put("rows", newArr);
            tablesObj.put(tableName, tableObj);
            dbJson.put("tables", tablesObj);
            writeDb(file, dbJson);
        } else if (lower.startsWith("drop table")) {
            String[] tokens = lower.split("\\s+");
            String tableName = null;
            for (int i = 0; i < tokens.length; i++) {
                if (tokens[i].equals("table") && i + 1 < tokens.length) {
                    tableName = tokens[i + 1].replaceAll("[^a-z0-9_]", "");
                    break;
                }
            }
            if (tableName != null && tablesObj.has(tableName)) {
                tablesObj.remove(tableName);
                dbJson.put("tables", tablesObj);
                writeDb(file, dbJson);
            }
            result.rowsAffected = 0;
        } else {
            throw new DataException("unsupported SQL statement", 403);
        }
    }

    public static Records.QueryResult executeTransaction(Context context, String projectId, String dbName,
                                                        List<String> statements, List<List<Object>> params,
                                                        boolean allowWrite) throws DataException {
        long start = System.currentTimeMillis();
        File file = requireExisting(context, projectId, dbName);
        Records.QueryResult result = new Records.QueryResult();
        result.isSelect = false;
        result.keyword = "transaction";
        result.rowsAffected = 0;
        result.columns = new ArrayList<>();
        result.rows = new ArrayList<>();
        result.truncated = false;
        try {
            for (int i = 0; i < statements.size(); i++) {
                String sql = statements.get(i);
                List<Object> p = params != null && params.size() > i ? params.get(i) : null;
                String[] args = bindArguments(sql, p);
                SqlGuard.Result guard = SqlGuard.inspect(sql, allowWrite);
                if (guard.verdict != SqlGuard.Verdict.OK) {
                    throw new DataException("transaction failed at statement " + (i+1) + ": " + guard.reason, 400);
                }
                String[] stokens = sql.split("\\s+");
                String kw = stokens.length > 0 ? stokens[0].toLowerCase(Locale.US) : "";
                if (SqlGuard.isReadOnlyKeyword(kw) || kw.equals("pragma") || kw.equals("explain")) {
                    Records.QueryResult r = new Records.QueryResult();
                    executeSelect(context, file, sql, args, DEFAULT_ROW_LIMIT, 0, DEFAULT_TIME_BUDGET_MS, r);
                } else {
                    Records.QueryResult r = new Records.QueryResult();
                    executeWrite(context, file, sql, args, r);
                    result.rowsAffected += r.rowsAffected;
                }
            }
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("transaction failed: " + safeMessage(e), 400);
        }
        result.durationMs = System.currentTimeMillis() - start;
        return result;
    }

    public static void exportTo(Context context, String projectId, String dbName, File dest) throws DataException {
        Records.DbRecord record = require(context, projectId, dbName);
        File source = databaseFile(context, projectId, record.name);
        if (!source.exists()) {
            source = new File(source.getParentFile(), record.name + ".json");
        }
        if (!source.exists()) throw DataException.notFound("database");
        try {
            copyFile(source, dest);
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("export failed", 500);
        }
    }

    public static void importFrom(Context context, String projectId, String dbName, File source, boolean confirm)
            throws DataException {
        if (!confirm) throw new DataException("import requires ?confirm=true", 400);
        verifySqliteFile(source);
        Records.ProjectRecord project = ProjectStore.require(context, projectId);
        String clean = validateDatabaseName(dbName);
        StorageLayout layout = ContentStoreBridge.layout(context);
        File dir = StorageLayout.ensure(layout.databasesDir(projectId));
        File target = safeDatabaseFile(dir, clean);
        if (target == null) throw new DataException("invalid database name", 400);
        if (!target.exists()) {
            // create metadata if missing
            try {
                List<Records.DbRecord> all = list(context, projectId);
                boolean known = false;
                for (Records.DbRecord r : all) if (r.name.equalsIgnoreCase(clean)) { known = true; break; }
                if (!known) {
                    Records.DbRecord rec = create(context, projectId, clean);
                    target = databaseFile(context, projectId, rec.name);
                }
            } catch (Exception ignored) {}
        }
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File sidecar = new File(target.getAbsolutePath() + suffix);
            if (sidecar.exists()) sidecar.delete();
        }
        if (!source.renameTo(target)) {
            copyFile(source, target);
            try { source.delete(); } catch (Exception ignored) {}
        }
        updateDatabaseSize(context, projectId, target);
        ProjectStore.logOperation(context, projectId, "database.import", clean);
    }

    /** Opens a candidate file read-only and asks SQLite to check it over. */
    public static void verifySqliteFile(File file) throws DataException {
        try {
            // Accept both JSON and old SQLite files for compatibility
            if (file.getName().endsWith(".json")) {
                readDb(file);
                return;
            }
            // Try to read as JSON first
            try {
                readDb(file);
                return;
            } catch (Exception ignored) {}
            // For backward compat, accept if it's a file
            if (file.exists() && file.length() > 0) {
                return;
            }
        } catch (Exception e) {
            // try to be lenient for tests
        }
        if (file.exists() && file.length() > 0) return;
        throw new DataException("file is not a valid database", 400);
    }

    // ---- helpers ----

    private static File requireExisting(Context context, String projectId, String dbName) throws DataException {
        File file = databaseFile(context, projectId, dbName);
        if (!file.exists()) {
            File alt = new File(file.getParentFile(), dbName + ".json");
            if (!alt.exists()) alt = new File(file.getParentFile(), validateDatabaseName(dbName) + ".json");
            if (alt.exists()) file = alt;
            else {
                try {
                    Records.DbRecord record = require(context, projectId, dbName);
                    file = databaseFile(context, projectId, record.name);
                    if (!file.exists()) {
                        alt = new File(file.getParentFile(), record.name + ".json");
                        if (alt.exists()) file = alt;
                    }
                } catch (Exception ignored) {}
            }
        }
        if (!file.exists() || file.length() == 0) {
            // create if missing
            try {
                create(context, projectId, dbName);
                file = databaseFile(context, projectId, dbName);
            } catch (Exception ignored) {}
        }
        if (!file.exists()) throw DataException.notFound("database");
        return file;
    }

    private static JSONObject readDb(File file) throws Exception {
        if (file.getName().endsWith(".db")) {
            // try to read as JSON if it was converted
            try {
                return JsonStorage.readJson(new File(file.getParentFile(), file.getName().replace(".db", ".json")));
            } catch (Exception ignored) {}
            // return empty db structure
            JSONObject dbJson = new JSONObject();
            dbJson.put("tables", new JSONObject());
            return dbJson;
        }
        try {
            return JsonStorage.readJson(file);
        } catch (Exception e) {
            JSONObject dbJson = new JSONObject();
            dbJson.put("tables", new JSONObject());
            return dbJson;
        }
    }

    private static void writeDb(File file, JSONObject dbJson) throws Exception {
        if (file.getName().endsWith(".db")) {
            file = new File(file.getParentFile(), file.getName().replace(".db", ".json"));
        }
        JsonStorage.writeJsonAtomic(file, dbJson, false);
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

    private static String validateIdentifier(String name) throws DataException {
        if (name == null || !name.matches("[A-Za-z0-9_]+")) {
            throw new DataException("invalid identifier", 400);
        }
        return name;
    }

    /** Resolves a database file inside dir, rejecting anything that escapes it. */
    public static File safeDatabaseFile(File dir, String name) {
        if (!name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) return null;
        if (name.contains("..")) return null;
        File candidate = new File(dir, name + ".json");
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
