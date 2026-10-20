package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.yadaventerprise.phoneserver.data.BackupManager;
import com.yadaventerprise.phoneserver.data.DataException;
import com.yadaventerprise.phoneserver.data.DatabaseManager;
import com.yadaventerprise.phoneserver.data.FileStore;
import com.yadaventerprise.phoneserver.data.MimeTypes;
import com.yadaventerprise.phoneserver.data.ProjectStore;
import com.yadaventerprise.phoneserver.data.RateLimiter;
import com.yadaventerprise.phoneserver.data.Records;
import com.yadaventerprise.phoneserver.data.TokenStore;
import com.yadaventerprise.phoneserver.data.TokenUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The versioned data & storage API served under {@link #BASE}.
 *
 * It turns the project/database/file/backup storage layer into an HTTP service
 * that small applications and websites can consume. Everything is:
 *
 * <ul>
 *   <li><b>Authenticated</b> - the interactive access password, or a scoped
 *       API token (see {@link TokenStore}). There is no unauthenticated admin
 *       SQL surface.</li>
 *   <li><b>Authorised per project</b> - a project-scoped token can only touch
 *       its own project, so projects stay isolated.</li>
 *   <li><b>Rate limited</b> per client IP.</li>
 *   <li><b>Safe</b> - parameterised SQL, a static SQL guard, path-traversal
 *       proof storage, and errors that never leak host paths or secrets.</li>
 * </ul>
 *
 * Responses are JSON with meaningful HTTP status codes. Errors are
 * {@code {"error":"message"}}. Binary resources (file downloads, database and
 * backup exports) are streamed and support HTTP range requests.
 */
public final class DataApi {

    private static final String TAG = "DataApi";

    /** Base path of this API. Everything below requires authentication. */
    public static final String BASE = "/api/v1";
    public static final String API_VERSION = "1";

    private static final long API_TOKEN_WINDOW_MS = 60_000L;

    private static volatile RateLimiter limiter;
    private static volatile int limiterMax;

    private DataApi() {
    }

    public static boolean isApiPath(String path) {
        return path != null && (path.equals(BASE) || path.startsWith(BASE + "/"));
    }

    /**
     * True when the request body should be left on the socket for the data API
     * to stream (large raw binary uploads). Multipart bodies are buffered and
     * size-capped by the server instead.
     */
    public static boolean wantsStreamedBody(String method, String path, Map<String, String> headers) {
        if (method == null || path == null) return false;
        if (!isApiPath(path)) return false;
        boolean write = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        if (!write) return false;
        String contentType = headers == null ? null : headers.get("content-type");
        boolean multipart = contentType != null && contentType.toLowerCase(Locale.US).contains("multipart/form-data");
        if (multipart) return false;
        String p = path;
        return p.endsWith("/files")
                || (p.contains("/databases/") && p.endsWith("/import"))
                || p.endsWith("/backups/import");
    }

    // ---- entry point ----

    public static void handle(Context context, SimpleHttpServer.Request req, OutputStream out) {
        try {
            if (req.bodyTooLarge) {
                sendError(context, req, out, 413, "request body is too large for a buffered request; "
                        + "upload large files as a raw body (non-multipart)");
                return;
            }
            dispatch(context, req, out);
        } catch (DataException e) {
            try {
                sendError(context, req, out, e.httpStatus(), e.getMessage());
            } catch (IOException ignored) {
            }
        } catch (JSONException e) {
            try {
                sendError(context, req, out, 400, "invalid JSON in request body");
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            Log.w(TAG, "client disconnected", e);
        } catch (Exception e) {
            Log.e(TAG, "unexpected API error", e);
            try {
                sendError(context, req, out, 500, "internal server error");
            } catch (IOException ignored) {
            }
        }
    }

    private static void dispatch(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws Exception {
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);

        // CORS preflight is answered before auth so browsers can negotiate.
        if (method.equals("OPTIONS")) {
            sendPreflight(context, req, out);
            return;
        }

        List<String> parts = segments(req.path);
        String head = parts.isEmpty() ? "" : parts.get(0);

        // Public, non-sensitive liveness probe.
        if (head.equals("health")) {
            requireMethod(method, "GET");
            sendHealth(context, req, out);
            return;
        }

        // Everything past here is authenticated.
        if (!enforceRateLimit(context, req, out)) return;
        Auth auth = authenticate(context, req);
        if (auth == null) {
            sendUnauthorized(req, out, context);
            return;
        }

        if (head.isEmpty()) {
            requireMethod(method, "GET");
            sendIndex(req, out);
            return;
        }
        if (head.equals("system")) {
            requireMethod(method, "GET");
            sendSystem(context, req, out, auth);
            return;
        }
        if (head.equals("tokens")) {
            handleTokens(context, req, out, auth, parts);
            return;
        }
        if (head.equals("projects")) {
            handleProjects(context, req, out, auth, parts);
            return;
        }
        throw DataException.notFound("endpoint");
    }

    // ---- authentication & limits ----

    /** A resolved caller: either the interactive password (admin) or an API token. */
    private static final class Auth {
        boolean admin;
        boolean canWrite = true;
        /** null => every project (admin or an unscoped token). */
        String projectId;
    }

    private static Auth authenticate(Context context, SimpleHttpServer.Request req) {
        // Bearer token first, so non-interactive clients have a scoped option.
        String bearer = bearerToken(req);
        if (bearer != null) {
            Records.ApiTokenRecord token = TokenStore.verify(context, bearer);
            if (token == null) return null;
            Auth auth = new Auth();
            auth.admin = false;
            auth.canWrite = token.canWrite;
            auth.projectId = token.projectId;
            return auth;
        }

        String stored = ContentStore.getPassword(context);
        if (stored == null || stored.isEmpty()) {
            // No password configured: refuse anonymous admin access to the data
            // API. A token is required (created from the app UI).
            return null;
        }
        String supplied = req.headers.get("x-auth-password");
        if (supplied == null) supplied = req.query.get("pw");
        if (stored.equals(supplied) || TokenUtil.constantTimeEquals(stored, supplied) || stored.equals(basicPassword(req))) {
            Auth auth = new Auth();
            auth.admin = true;
            auth.canWrite = true;
            return auth;
        }
        return null;
    }

    private static String bearerToken(SimpleHttpServer.Request req) {
        String auth = req.headers.get("authorization");
        if (auth == null) return null;
        String lower = auth.toLowerCase(Locale.US);
        if (lower.startsWith("bearer ")) return auth.substring("bearer ".length()).trim();
        return null;
    }

    private static String basicPassword(SimpleHttpServer.Request req) {
        String auth = req.headers.get("authorization");
        if (auth == null || !auth.startsWith("Basic ")) return null;
        try {
            byte[] decoded = Base64.decode(auth.substring("Basic ".length()).trim(), Base64.DEFAULT);
            String userPass = new String(decoded, StandardCharsets.UTF_8);
            int colon = userPass.indexOf(':');
            return colon < 0 ? userPass : userPass.substring(colon + 1);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean enforceRateLimit(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws IOException {
        int max = ContentStore.getApiRateLimitPerMinute(context);
        RateLimiter current = limiter;
        if (current == null || limiterMax != max) {
            current = new RateLimiter(max, API_TOKEN_WINDOW_MS);
            limiter = current;
            limiterMax = max;
        }
        if (current.tryAcquire(req.remoteIp)) return true;
        String body = "{\"error\":\"rate limit exceeded, slow down\"}";
        writeRaw(out, 429, "Too Many Requests", "application/json", body.getBytes(StandardCharsets.UTF_8),
                context, req, "Retry-After: 60");
        return false;
    }

    private static void requireProjectAccess(Auth auth, String projectId) throws DataException {
        if (auth.admin) return;
        if (auth.projectId == null) return; // unscoped token
        if (!auth.projectId.equals(projectId)) {
            throw DataException.forbidden("this token is not authorised for that project");
        }
    }

    private static void requireWrite(Auth auth) throws DataException {
        if (!auth.canWrite) throw DataException.forbidden("this credential is read-only");
    }

    private static void requireAdmin(Auth auth) throws DataException {
        if (!auth.admin) throw DataException.forbidden("administrator (password) access required");
    }

    // ---- system ----

    private static void sendHealth(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws IOException, JSONException {
        JSONObject json = new JSONObject();
        json.put("status", "online");
        json.put("apiVersion", "v" + API_VERSION);
        json.put("server", "phoneserver");
        json.put("time", isoNow());
        sendJson(context, req, out, 200, json);
    }

    private static void sendSystem(Context context, SimpleHttpServer.Request req, OutputStream out, Auth auth)
            throws Exception {
        requireAdmin(auth);
        ProjectStore.GlobalStats stats = ProjectStore.globalStats(context);
        JSONObject json = new JSONObject();
        JSONObject projects = new JSONObject();
        projects.put("count", stats.projectCount);
        json.put("projects", projects);
        JSONObject databases = new JSONObject();
        databases.put("count", stats.databaseCount);
        databases.put("bytes", stats.databaseBytes);
        json.put("databases", databases);
        JSONObject files = new JSONObject();
        files.put("count", stats.fileCount);
        files.put("bytes", stats.fileBytes);
        json.put("files", files);

        File root = ContentStore.getDataRoot(context);
        JSONObject storage = new JSONObject();
        storage.put("usedBytes", stats.databaseBytes + stats.fileBytes);
        storage.put("freeBytes", root.getUsableSpace());
        storage.put("totalBytes", root.getTotalSpace());
        json.put("storage", storage);

        long uptime = ServerService.getUptimeMillis();
        json.put("uptimeMillis", uptime < 0 ? 0 : uptime);
        json.put("apiVersion", "v" + API_VERSION);
        sendJson(context, req, out, 200, json);
    }

    private static void sendIndex(SimpleHttpServer.Request req, OutputStream out) throws IOException, JSONException {
        JSONObject json = new JSONObject();
        json.put("name", "PhoneServer data API");
        json.put("version", API_VERSION);
        JSONArray endpoints = new JSONArray();
        endpoints.put("GET  " + BASE + "/health");
        endpoints.put("GET  " + BASE + "/system");
        endpoints.put("GET/POST " + BASE + "/projects");
        endpoints.put("GET/PATCH/DELETE " + BASE + "/projects/{id}");
        endpoints.put("GET/POST " + BASE + "/projects/{id}/databases");
        endpoints.put("GET/DELETE " + BASE + "/projects/{id}/databases/{db}");
        endpoints.put("GET  " + BASE + "/projects/{id}/databases/{db}/tables");
        endpoints.put("GET  " + BASE + "/projects/{id}/databases/{db}/tables/{table}");
        endpoints.put("POST " + BASE + "/projects/{id}/databases/{db}/query");
        endpoints.put("POST " + BASE + "/projects/{id}/databases/{db}/transaction");
        endpoints.put("GET  " + BASE + "/projects/{id}/databases/{db}/export");
        endpoints.put("POST " + BASE + "/projects/{id}/databases/{db}/import");
        endpoints.put("GET/POST " + BASE + "/projects/{id}/files");
        endpoints.put("GET/PATCH/DELETE " + BASE + "/projects/{id}/files/{fileId}");
        endpoints.put("GET  " + BASE + "/projects/{id}/files/{fileId}/download");
        endpoints.put("GET/POST/DELETE " + BASE + "/projects/{id}/folders");
        endpoints.put("GET/POST " + BASE + "/projects/{id}/backups");
        endpoints.put("POST " + BASE + "/projects/{id}/backups/{backupId}/restore");
        endpoints.put("GET  " + BASE + "/projects/{id}/backups/{backupId}/download");
        endpoints.put("GET/POST/DELETE " + BASE + "/tokens");
        json.put("endpoints", endpoints);
        sendJson(null, req, out, 200, json);
    }

    // ---- projects ----

    private static void handleProjects(Context context, SimpleHttpServer.Request req, OutputStream out,
                                       Auth auth, List<String> parts) throws Exception {
        String method = req.method.toUpperCase(Locale.US);
        if (parts.size() == 1) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (Records.ProjectRecord p : ProjectStore.list(context)) {
                    requireProjectAccess(auth, p.id);
                    arr.put(projectJson(context, p));
                }
                sendJson(context, req, out, 200, wrap("projects", arr));
                return;
            }
            if (method.equals("POST")) {
                requireWrite(auth);
                JSONObject body = readJson(req);
                requireBody(body);
                if (!auth.admin && auth.projectId != null) {
                    throw DataException.forbidden("a project-scoped token cannot create projects");
                }
                Records.ProjectRecord project = ProjectStore.create(context,
                        body.optString("name", null), body.optString("description", ""),
                        optLong(body, "fileQuotaBytes", 0), optLong(body, "maxUploadBytes", 0));
                sendJson(context, req, out, 201, projectJson(context, project));
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String projectId = parts.get(1);
        projectId = decode(projectId);
        requireProjectAccess(auth, projectId);

        if (parts.size() == 2) {
            if (method.equals("GET")) {
                sendJson(context, req, out, 200, projectJson(context, ProjectStore.require(context, projectId)));
                return;
            }
            if (method.equals("PATCH") || method.equals("PUT")) {
                requireWrite(auth);
                JSONObject body = readJson(req);
                Records.ProjectRecord updated = ProjectStore.update(context, projectId,
                        body.has("name") ? body.optString("name", null) : null,
                        body.has("description") ? body.optString("description", "") : null,
                        body.has("fileQuotaBytes") ? Long.valueOf(optLong(body, "fileQuotaBytes", 0)) : null,
                        body.has("maxUploadBytes") ? Long.valueOf(optLong(body, "maxUploadBytes", 0)) : null);
                sendJson(context, req, out, 200, projectJson(context, updated));
                return;
            }
            if (method.equals("DELETE")) {
                requireWrite(auth);
                ProjectStore.delete(context, projectId, req.query.get("confirm"));
                sendJson(context, req, out, 200, ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String section = parts.get(2);
        if (section.equals("stats") && parts.size() == 3 && method.equals("GET")) {
            sendJson(context, req, out, 200, projectStatsJson(context, projectId));
            return;
        }
        if (section.equals("usage") && parts.size() == 3 && method.equals("GET")) {
            sendJson(context, req, out, 200, usageJson(context, projectId));
            return;
        }
        if (section.equals("databases")) {
            handleDatabases(context, req, out, auth, projectId, parts);
            return;
        }
        if (section.equals("files")) {
            handleFiles(context, req, out, auth, projectId, parts);
            return;
        }
        if (section.equals("folders")) {
            handleFolders(context, req, out, auth, projectId, parts);
            return;
        }
        if (section.equals("backups")) {
            handleBackups(context, req, out, auth, projectId, parts);
            return;
        }
        throw DataException.notFound("endpoint");
    }

    private static JSONObject projectJson(Context context, Records.ProjectRecord p) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", p.id);
        json.put("name", p.name);
        json.put("description", p.description);
        json.put("fileQuotaBytes", p.fileQuotaBytes);
        json.put("maxUploadBytes", p.maxUploadBytes);
        json.put("createdAt", p.createdAt);
        json.put("updatedAt", p.updatedAt);
        ProjectStore.Stats stats = ProjectStore.stats(context, p.id);
        JSONObject usage = new JSONObject();
        usage.put("databaseCount", stats.databaseCount);
        usage.put("fileCount", stats.fileCount);
        usage.put("folderCount", stats.folderCount);
        usage.put("fileBytes", stats.fileBytes);
        usage.put("databaseBytes", stats.databaseBytes);
        usage.put("backupBytes", stats.backupBytes);
        json.put("usage", usage);
        return json;
    }

    private static JSONObject projectStatsJson(Context context, String projectId) throws Exception {
        ProjectStore.require(context, projectId);
        ProjectStore.Stats stats = ProjectStore.stats(context, projectId);
        JSONObject json = new JSONObject();
        json.put("projectId", projectId);
        json.put("databaseCount", stats.databaseCount);
        json.put("fileCount", stats.fileCount);
        json.put("folderCount", stats.folderCount);
        json.put("fileBytes", stats.fileBytes);
        json.put("databaseBytes", stats.databaseBytes);
        json.put("backupBytes", stats.backupBytes);
        return json;
    }

    private static JSONObject usageJson(Context context, String projectId) throws Exception {
        ProjectStore.require(context, projectId);
        FileStore.Usage usage = FileStore.usage(context, projectId);
        JSONObject json = new JSONObject();
        json.put("projectId", projectId);
        json.put("fileCount", usage.fileCount);
        json.put("fileBytes", usage.bytes);
        json.put("quotaBytes", usage.quotaBytes);
        json.put("maxUploadBytes", usage.maxUploadBytes);
        File root = ContentStore.getDataRoot(context);
        json.put("freeBytes", root.getUsableSpace());
        json.put("totalBytes", root.getTotalSpace());
        return json;
    }

    // ---- databases ----

    private static void handleDatabases(Context context, SimpleHttpServer.Request req, OutputStream out,
                                        Auth auth, String projectId, List<String> parts) throws Exception {
        String method = req.method.toUpperCase(Locale.US);
        if (parts.size() == 3) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (Records.DbRecord db : DatabaseManager.list(context, projectId)) arr.put(dbJson(db));
                sendJson(context, req, out, 200, wrap("databases", arr));
                return;
            }
            if (method.equals("POST")) {
                requireWrite(auth);
                JSONObject body = readJson(req);
                Records.DbRecord db = DatabaseManager.create(context, projectId, body.optString("name", null));
                sendJson(context, req, out, 201, dbJson(db));
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String dbName = decode(parts.get(3));
        if (parts.size() == 4) {
            if (method.equals("GET")) {
                Records.DbRecord db = DatabaseManager.require(context, projectId, dbName);
                JSONObject json = dbJson(db);
                JSONArray tables = new JSONArray();
                for (Records.TableInfo t : DatabaseManager.listTables(context, projectId, db.name)) {
                    tables.put(tableSummaryJson(t));
                }
                json.put("tables", tables);
                sendJson(context, req, out, 200, json);
                return;
            }
            if (method.equals("DELETE")) {
                requireWrite(auth);
                DatabaseManager.delete(context, projectId, dbName);
                sendJson(context, req, out, 200, ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String action = parts.get(4);
        if (action.equals("tables")) {
            if (parts.size() == 5 && method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (Records.TableInfo t : DatabaseManager.listTables(context, projectId, dbName)) {
                    arr.put(tableJson(t));
                }
                sendJson(context, req, out, 200, wrap("tables", arr));
                return;
            }
            if (parts.size() == 6 && method.equals("GET")) {
                Records.TableInfo table = DatabaseManager.tableInfo(context, projectId, dbName,
                        decode(parts.get(5)));
                sendJson(context, req, out, 200, tableJson(table));
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        if (action.equals("query") && method.equals("POST")) {
            JSONObject body = readJson(req);
            String sql = body.optString("sql", null);
            List<Object> params = toList(body.optJSONArray("params"));
            Integer limit = body.has("limit") ? body.optInt("limit") : null;
            Integer offset = body.has("offset") ? body.optInt("offset") : null;
            Long budget = body.has("timeBudgetMs") ? body.optLong("timeBudgetMs") : null;
            Records.QueryResult result = DatabaseManager.execute(context, projectId, dbName, sql, params,
                    limit, offset, budget, auth.canWrite);
            ProjectStore.logOperation(context, projectId, "api.query",
                    (result.isSelect ? "query" : "write") + " (" + result.durationMs + "ms)");
            sendJson(context, req, out, 200, queryResultJson(result));
            return;
        }

        if (action.equals("transaction") && method.equals("POST")) {
            requireWrite(auth);
            JSONObject body = readJson(req);
            JSONArray statementsJson = body.optJSONArray("statements");
            if (statementsJson == null || statementsJson.length() == 0) {
                throw new DataException("'statements' must be a non-empty array", 400);
            }
            List<String> statements = new ArrayList<>();
            List<List<Object>> params = new ArrayList<>();
            for (int i = 0; i < statementsJson.length(); i++) {
                Object item = statementsJson.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject s = (JSONObject) item;
                    statements.add(s.optString("sql", null));
                    params.add(toList(s.optJSONArray("params")));
                } else {
                    statements.add(String.valueOf(item));
                    params.add(null);
                }
            }
            Records.QueryResult result = DatabaseManager.executeTransaction(context, projectId, dbName,
                    statements, params, true);
            ProjectStore.logOperation(context, projectId, "api.transaction", statements.size() + " statements");
            sendJson(context, req, out, 200, queryResultJson(result));
            return;
        }

        if (action.equals("export") && parts.size() == 5 && method.equals("GET")) {
            Records.DbRecord db = DatabaseManager.require(context, projectId, dbName);
            File tmp = ContentStore.getStorageLayout(context).newTempFile("export");
            try {
                DatabaseManager.exportTo(context, projectId, db.name, tmp);
                serveDownload(context, req, out, tmp, "application/vnd.sqlite3",
                        db.name + ".db", true);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return;
        }

        if (action.equals("import") && parts.size() == 5 && method.equals("POST")) {
            requireWrite(auth);
            importDatabase(context, req, out, projectId, dbName);
            return;
        }

        throw DataException.notFound("endpoint");
    }

    private static void importDatabase(Context context, SimpleHttpServer.Request req, OutputStream out,
                                       String projectId, String dbName) throws Exception {
        boolean confirm = "true".equalsIgnoreCase(req.query.get("confirm"));
        File temp = ContentStore.getStorageLayout(context).newTempFile("import");
        try {
            long written = storeBody(context, req, temp);
            if (written == 0) throw new DataException("no database content was uploaded", 400);
            DatabaseManager.importFrom(context, projectId, dbName, temp, confirm);
            sendJson(context, req, out, 200, ok());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static JSONObject dbJson(Records.DbRecord db) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", db.id);
        json.put("name", db.name);
        json.put("fileName", db.fileName);
        json.put("sizeBytes", db.sizeBytes);
        json.put("createdAt", db.createdAt);
        json.put("updatedAt", db.updatedAt);
        return json;
    }

    private static JSONObject tableSummaryJson(Records.TableInfo t) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", t.name);
        json.put("type", t.type);
        json.put("rowCount", t.rowCount);
        return json;
    }

    private static JSONObject tableJson(Records.TableInfo t) throws JSONException {
        JSONObject json = tableSummaryJson(t);
        json.put("columnCount", t.columns.size());
        JSONArray columns = new JSONArray();
        for (Records.ColumnInfo c : t.columns) {
            JSONObject col = new JSONObject();
            col.put("name", c.name);
            col.put("type", c.type);
            col.put("notNull", c.notNull);
            col.put("primaryKey", c.primaryKey);
            col.put("default", c.defaultValue == null ? JSONObject.NULL : c.defaultValue);
            columns.put(col);
        }
        json.put("columns", columns);
        JSONArray indexes = new JSONArray();
        for (Records.IndexInfo idx : t.indexes) {
            JSONObject index = new JSONObject();
            index.put("name", idx.name);
            index.put("unique", idx.unique);
            index.put("columns", new JSONArray(idx.columns));
            indexes.put(index);
        }
        json.put("indexes", indexes);
        return json;
    }

    private static JSONObject queryResultJson(Records.QueryResult result) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("isSelect", result.isSelect);
        json.put("keyword", result.keyword == null ? JSONObject.NULL : result.keyword);
        json.put("rowsAffected", result.rowsAffected);
        json.put("truncated", result.truncated);
        json.put("durationMs", result.durationMs);
        if (result.isSelect) {
            json.put("columns", new JSONArray(result.columns));
            JSONArray rows = new JSONArray();
            for (List<Object> row : result.rows) {
                JSONArray r = new JSONArray();
                for (Object value : row) r.put(value);
                rows.put(r);
            }
            json.put("rows", rows);
            json.put("rowCount", result.rows.size());
        }
        return json;
    }

    // ---- files & folders ----

    private static void handleFiles(Context context, SimpleHttpServer.Request req, OutputStream out,
                                    Auth auth, String projectId, List<String> parts) throws Exception {
        String method = req.method.toUpperCase(Locale.US);
        if (parts.size() == 3) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                List<Records.FileRecord> files;
                if (req.query.containsKey("folder")) {
                    files = FileStore.list(context, projectId, req.query.get("folder"));
                } else {
                    files = FileStore.listAll(context, projectId);
                }
                for (Records.FileRecord f : files) arr.put(fileJson(f));
                sendJson(context, req, out, 200, wrap("files", arr));
                return;
            }
            if (method.equals("POST") || method.equals("PUT")) {
                requireWrite(auth);
                uploadFile(context, req, out, projectId);
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String fileId = decode(parts.get(3));
        if (parts.size() == 4) {
            if (method.equals("GET")) {
                sendJson(context, req, out, 200, fileJson(FileStore.require(context, projectId, fileId)));
                return;
            }
            if (method.equals("PATCH") || method.equals("PUT")) {
                requireWrite(auth);
                JSONObject body = readJson(req);
                Records.FileRecord current = FileStore.require(context, projectId, fileId);
                if (body.has("name")) current = FileStore.rename(context, projectId, fileId, body.optString("name"));
                if (body.has("folder")) current = FileStore.move(context, projectId, fileId, body.optString("folder"));
                sendJson(context, req, out, 200, fileJson(current));
                return;
            }
            if (method.equals("DELETE")) {
                requireWrite(auth);
                FileStore.delete(context, projectId, fileId);
                sendJson(context, req, out, 200, ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String action = parts.get(4);
        if (action.equals("download") && method.equals("GET")) {
            Records.FileRecord record = FileStore.require(context, projectId, fileId);
            File content = FileStore.contentFile(context, record);
            boolean inline = "1".equals(req.query.get("inline")) || "true".equals(req.query.get("inline"));
            boolean attachment = !inline || !MimeTypes.isInlineSafe(record.mimeType);
            serveDownload(context, req, out, content, record.mimeType, record.originalName, attachment);
            return;
        }
        throw DataException.notFound("endpoint");
    }

    private static void handleFolders(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      Auth auth, String projectId, List<String> parts) throws Exception {
        String method = req.method.toUpperCase(Locale.US);
        if (parts.size() != 3) throw DataException.notFound("endpoint");
        if (method.equals("GET")) {
            JSONArray arr = new JSONArray();
            for (Records.FolderRecord f : FileStore.listFolders(context, projectId)) {
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("path", f.path);
                o.put("createdAt", f.createdAt);
                arr.put(o);
            }
            sendJson(context, req, out, 200, wrap("folders", arr));
            return;
        }
        if (method.equals("POST")) {
            requireWrite(auth);
            JSONObject body = readJson(req);
            String path = body.optString("path", null);
            FileStore.createFolder(context, projectId, path);
            sendJson(context, req, out, 201, ok());
            return;
        }
        if (method.equals("DELETE")) {
            requireWrite(auth);
            FileStore.deleteFolder(context, projectId, req.query.get("path"));
            sendJson(context, req, out, 200, ok());
            return;
        }
        throw new DataException("method not allowed", 405);
    }

    private static JSONObject fileJson(Records.FileRecord f) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", f.id);
        json.put("projectId", f.projectId);
        json.put("name", f.originalName);
        json.put("folder", f.folderPath);
        json.put("mimeType", f.mimeType);
        json.put("sizeBytes", f.sizeBytes);
        json.put("checksum", f.checksum == null ? JSONObject.NULL : f.checksum);
        json.put("description", f.description);
        json.put("tags", f.tags);
        json.put("createdAt", f.createdAt);
        json.put("updatedAt", f.updatedAt);
        json.put("downloadPath", BASE + "/projects/" + f.projectId + "/files/" + f.id + "/download");
        return json;
    }

    private static void uploadFile(Context context, SimpleHttpServer.Request req, OutputStream out,
                                   String projectId) throws Exception {
        ProjectStore.require(context, projectId);
        String folder = req.query.get("folder");
        String name = req.query.get("name");
        String description = req.query.get("description");
        String tags = req.query.get("tags");
        String contentType = req.headers.get("content-type");

        Records.FileRecord record;
        if (contentType != null && contentType.toLowerCase(Locale.US).contains("multipart/form-data")) {
            String boundary = extractBoundary(contentType);
            if (boundary == null) throw new DataException("multipart boundary missing", 400);
            MultipartParser.ParsedFile parsed = MultipartParser.parseSingleFile(req.body, boundary);
            if (parsed == null) throw new DataException("no file part found in upload", 400);
            InputStream in = new java.io.ByteArrayInputStream(parsed.bytes);
            record = FileStore.upload(context, projectId, folder,
                    name != null ? name : parsed.filename, null, in, parsed.bytes.length, description, tags);
        } else {
            if (req.bodyStream == null || req.contentLength < 0) {
                throw new DataException("Content-Length is required for streamed uploads", 411);
            }
            if (req.contentLength == 0) throw new DataException("empty upload body", 400);
            InputStream in = new LimitedInputStream(req.bodyStream, req.contentLength);
            record = FileStore.upload(context, projectId, folder, name, contentType, in,
                    req.contentLength, description, tags);
        }
        ServerStats.get().logUpload(record.originalName, (int) Math.min(record.sizeBytes, Integer.MAX_VALUE));
        sendJson(context, req, out, 201, fileJson(record));
    }

    // ---- backups ----

    private static void handleBackups(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      Auth auth, String projectId, List<String> parts) throws Exception {
        String method = req.method.toUpperCase(Locale.US);
        if (parts.size() == 3) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (Records.BackupRecord b : BackupManager.list(context, projectId)) arr.put(backupJson(b));
                sendJson(context, req, out, 200, wrap("backups", arr));
                return;
            }
            if (method.equals("POST")) {
                requireWrite(auth);
                JSONObject body = readJson(req);
                boolean includeFiles = !body.has("includeFiles") || body.optBoolean("includeFiles", true);
                Records.BackupRecord backup = BackupManager.create(context, projectId, includeFiles,
                        body.optString("note", ""));
                sendJson(context, req, out, 201, backupJson(backup));
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String backupId = decode(parts.get(3));
        if (backupId.equals("import") && method.equals("POST")) {
            requireWrite(auth);
            importBackup(context, req, out, projectId);
            return;
        }
        if (parts.size() == 4) {
            if (method.equals("GET")) {
                sendJson(context, req, out, 200, backupJson(BackupManager.require(context, projectId, backupId)));
                return;
            }
            if (method.equals("DELETE")) {
                requireWrite(auth);
                BackupManager.delete(context, projectId, backupId);
                sendJson(context, req, out, 200, ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String action = parts.get(4);
        if (action.equals("restore") && method.equals("POST")) {
            requireWrite(auth);
            JSONObject body = readJson(req);
            BackupManager.restore(context, projectId, backupId, body.optBoolean("confirm", false));
            sendJson(context, req, out, 200, ok());
            return;
        }
        if (action.equals("validate") && method.equals("POST")) {
            Map<String, String> manifest = BackupManager.validate(context, projectId, backupId);
            JSONObject json = new JSONObject();
            json.put("valid", true);
            for (Map.Entry<String, String> e : manifest.entrySet()) json.put(e.getKey(), e.getValue());
            sendJson(context, req, out, 200, json);
            return;
        }
        if (action.equals("download") && method.equals("GET")) {
            Records.BackupRecord backup = BackupManager.require(context, projectId, backupId);
            File archive = BackupManager.archiveFile(context, projectId, backupId);
            serveDownload(context, req, out, archive, "application/zip", backup.fileName, true);
            return;
        }
        throw DataException.notFound("endpoint");
    }

    private static void importBackup(Context context, SimpleHttpServer.Request req, OutputStream out,
                                     String projectId) throws Exception {
        boolean confirm = "true".equalsIgnoreCase(req.query.get("confirm"));
        File temp = ContentStore.getStorageLayout(context).newTempFile("restore-import");
        try {
            long written = storeBody(context, req, temp);
            if (written == 0) throw new DataException("no backup content was uploaded", 400);
            BackupManager.importArchive(context, projectId, temp, confirm);
            sendJson(context, req, out, 200, ok());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static JSONObject backupJson(Records.BackupRecord b) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", b.id);
        json.put("projectId", b.projectId);
        json.put("fileName", b.fileName);
        json.put("sizeBytes", b.sizeBytes);
        json.put("includesFiles", b.includesFiles);
        json.put("valid", b.valid);
        json.put("note", b.note);
        json.put("createdAt", b.createdAt);
        json.put("downloadPath", BASE + "/projects/" + b.projectId + "/backups/" + b.id + "/download");
        return json;
    }

    // ---- tokens ----

    private static void handleTokens(Context context, SimpleHttpServer.Request req, OutputStream out,
                                     Auth auth, List<String> parts) throws Exception {
        requireAdmin(auth);
        String method = req.method.toUpperCase(Locale.US);
        if (parts.size() == 1) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (Records.ApiTokenRecord t : TokenStore.list(context)) {
                    JSONObject json = new JSONObject();
                    json.put("id", t.id);
                    json.put("name", t.name);
                    json.put("projectId", t.projectId == null ? JSONObject.NULL : t.projectId);
                    json.put("canWrite", t.canWrite);
                    json.put("createdAt", t.createdAt);
                    json.put("lastUsedAt", t.lastUsedAt);
                    json.put("revoked", t.revoked);
                    arr.put(json);
                }
                sendJson(context, req, out, 200, wrap("tokens", arr));
                return;
            }
            if (method.equals("POST")) {
                JSONObject body = readJson(req);
                String projectId = body.has("projectId") && !body.isNull("projectId")
                        ? body.optString("projectId") : null;
                boolean canWrite = !body.has("canWrite") || body.optBoolean("canWrite", true);
                TokenStore.Created created = TokenStore.create(context, body.optString("name", null),
                        projectId, canWrite);
                JSONObject json = new JSONObject();
                json.put("id", created.record.id);
                json.put("name", created.record.name);
                json.put("projectId", created.record.projectId == null ? JSONObject.NULL : created.record.projectId);
                json.put("canWrite", created.record.canWrite);
                json.put("createdAt", created.record.createdAt);
                json.put("token", created.token);
                json.put("warning", "store this token now; it cannot be shown again");
                sendJson(context, req, out, 201, json);
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        String id = decode(parts.get(1));
        if (method.equals("DELETE")) {
            TokenStore.revoke(context, id);
            sendJson(context, req, out, 200, ok());
            return;
        }
        throw new DataException("method not allowed", 405);
    }

    // ---- helpers: request parsing ----

    private static List<String> segments(String path) {
        String rest = path.length() > BASE.length() ? path.substring(BASE.length()) : "";
        List<String> parts = new ArrayList<>();
        for (String s : rest.split("/")) {
            if (!s.isEmpty()) parts.add(s);
        }
        return parts;
    }

    private static String decode(String value) {
        if (value == null) return null;
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String extractBoundary(String contentType) {
        int idx = contentType.toLowerCase(Locale.US).indexOf("boundary=");
        if (idx < 0) return null;
        String boundary = contentType.substring(idx + "boundary=".length()).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        int semi = boundary.indexOf(';');
        if (semi >= 0) boundary = boundary.substring(0, semi).trim();
        return boundary.isEmpty() ? null : boundary;
    }

    private static JSONObject readJson(SimpleHttpServer.Request req) throws DataException {
        if (req.body == null || req.body.length == 0) return new JSONObject();
        try {
            return new JSONObject(new String(req.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new DataException("request body is not valid JSON", 400);
        }
    }

    private static void requireBody(JSONObject body) throws DataException {
        if (body == null) throw new DataException("a JSON object body is required", 400);
    }

    private static long optLong(JSONObject body, String key, long fallback) {
        Object value = body.opt(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static List<Object> toList(JSONArray array) {
        if (array == null) return null;
        List<Object> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            list.add(value == JSONObject.NULL ? null : value);
        }
        return list;
    }

    private static void requireMethod(String actual, String expected) throws DataException {
        if (!expected.equals(actual)) throw new DataException("method not allowed", 405);
    }

    /** Streams the request body to {@code dest}, never buffering the whole file. */
    private static long storeBody(Context context, SimpleHttpServer.Request req, File dest) throws Exception {
        String contentType = req.headers.get("content-type");
        if (contentType != null && contentType.toLowerCase(Locale.US).contains("multipart/form-data")) {
            String boundary = extractBoundary(contentType);
            if (boundary == null) throw new DataException("multipart boundary missing", 400);
            MultipartParser.ParsedFile parsed = MultipartParser.parseSingleFile(req.body, boundary);
            if (parsed == null) throw new DataException("no file part found in upload", 400);
            try (OutputStream out = new java.io.FileOutputStream(dest)) {
                out.write(parsed.bytes);
            }
            return parsed.bytes.length;
        }

        long written = 0;
        try (OutputStream out = new java.io.FileOutputStream(dest)) {
            if (req.body != null && req.body.length > 0) {
                out.write(req.body);
                return req.body.length;
            }
            if (req.bodyStream != null) {
                InputStream in = req.contentLength >= 0
                        ? new LimitedInputStream(req.bodyStream, req.contentLength)
                        : req.bodyStream;
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                }
            }
        }
        return written;
    }

    // ---- helpers: responses ----

    private static JSONObject wrap(String key, Object array) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(key, array);
        return json;
    }

    private static JSONObject ok() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("status", "ok");
        return json;
    }

    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date());
    }

    private static void sendJson(Context context, SimpleHttpServer.Request req, OutputStream out, int code, Object body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        writeRaw(out, code, statusText(code), "application/json; charset=utf-8", bytes, context, req, null);
    }

    private static void sendError(Context context, SimpleHttpServer.Request req, OutputStream out, int code, String message)
            throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("error", message == null ? "error" : message);
            json.put("status", code);
        } catch (JSONException ignored) {
        }
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        writeRaw(out, code, statusText(code), "application/json; charset=utf-8", bytes, context, req, null);
    }

    private static void sendUnauthorized(SimpleHttpServer.Request req, OutputStream out, Context context)
            throws IOException {
        byte[] bytes = "{\"error\":\"unauthorized: send an API token or the access password\"}"
                .getBytes(StandardCharsets.UTF_8);
        String extra = "WWW-Authenticate: Basic realm=\"PhoneServer\"";
        writeRaw(out, 401, "Unauthorized", "application/json; charset=utf-8", bytes, context, req, extra);
    }

    private static void sendPreflight(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws IOException {
        String origin = corsOrigin(context, req);
        if (origin == null) {
            writeRaw(out, 204, "No Content", "text/plain", new byte[0], context, req, null);
            return;
        }
        String extra = "Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Authorization, Content-Type, X-Auth-Password\r\n"
                + "Access-Control-Max-Age: 600";
        writeRaw(out, 204, "No Content", "text/plain", new byte[0], context, req, extra);
    }

    private static String corsOrigin(Context context, SimpleHttpServer.Request req) {
        String configured = ContentStore.getAllowedOrigins(context);
        if (configured == null) return null;
        configured = configured.trim();
        if (configured.isEmpty()) return null;
        if (configured.equals("*")) return "*";
        String origin = req.headers.get("origin");
        if (origin == null) return null;
        for (String allowed : configured.split(",")) {
            if (allowed.trim().equalsIgnoreCase(origin)) return origin;
        }
        return null;
    }

    private static void addCors(Context context, SimpleHttpServer.Request req, StringBuilder headers) {
        String origin = corsOrigin(context, req);
        if (headers == null || origin == null) return;
        headers.append("Access-Control-Allow-Origin: ").append(origin).append("\r\n");
        if (!origin.equals("*")) headers.append("Vary: Origin\r\n");
    }

    /** Writes a small in-memory response. */
    private static void writeRaw(OutputStream out, int code, String status, String contentType, byte[] body,
                                 Context context, SimpleHttpServer.Request req, String extraHeaders)
            throws IOException {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("X-Content-Type-Options: nosniff\r\n");
        if (context != null && req != null) addCors(context, req, headers);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            headers.append(extraHeaders).append("\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /**
     * Streams a file from disk with Content-Length, HTTP range support and safe
     * download headers. The file is never loaded into memory.
     */
    private static void serveDownload(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      File file, String contentType, String downloadName, boolean attachment)
            throws IOException {
        long length = file.length();
        long start = 0;
        long end = length - 1;
        int code = 200;
        String range = req.headers.get("range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash).trim());
                    String endPart = spec.substring(dash + 1).trim();
                    if (!endPart.isEmpty()) end = Long.parseLong(endPart);
                } else if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1).trim());
                    start = Math.max(0, length - suffix);
                }
            } catch (NumberFormatException e) {
                start = 0;
                end = length - 1;
            }
            if (start > end || start >= length) {
                String headers = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */" + length + "\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(headers.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            if (end >= length) end = length - 1;
            code = 206;
        }
        long contentLength = end - start + 1;

        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(code).append(' ').append(code == 206 ? "Partial Content" : "OK").append("\r\n");
        headers.append("Content-Type: ").append(contentType == null ? "application/octet-stream" : contentType).append("\r\n");
        headers.append("Content-Length: ").append(contentLength).append("\r\n");
        headers.append("Accept-Ranges: bytes\r\n");
        headers.append("X-Content-Type-Options: nosniff\r\n");
        headers.append("Cache-Control: private, max-age=0\r\n");
        if (code == 206) {
            headers.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(length).append("\r\n");
        }
        headers.append("Content-Disposition: ").append(attachment ? "attachment" : "inline")
                .append("; filename=\"").append(safeHeaderName(downloadName)).append("\"\r\n");
        addCors(context, req, headers);
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(start);
            byte[] buffer = new byte[65536];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, toRead);
                if (read == -1) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        out.flush();
    }

    private static String safeHeaderName(String name) {
        if (name == null || name.isEmpty()) return "download";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 150; i++) {
            char c = name.charAt(i);
            if (c == '"' || c == '\\' || c == '\r' || c == '\n' || c < 0x20) sb.append('_');
            else sb.append(c);
        }
        return sb.length() == 0 ? "download" : sb.toString();
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            default: return "Error";
        }
    }

    /** An InputStream that reads at most {@code limit} bytes from a socket. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = Math.max(0, limit);
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = delegate.read();
            if (b != -1) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int read = delegate.read(b, off, toRead);
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
