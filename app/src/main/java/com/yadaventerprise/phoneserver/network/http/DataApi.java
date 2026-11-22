package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.DatabaseManager;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.remote.RemoteSession;
import com.yadaventerprise.phoneserver.data.remote.RemoteSessionStore;
import com.yadaventerprise.phoneserver.data.security.IntrusionDetector;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.security.TokenPolicyStore;
import com.yadaventerprise.phoneserver.data.stats.ServerStats;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.data.storage.BackupManager;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.storage.FileStore;
import com.yadaventerprise.phoneserver.data.storage.ImportExportService;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.security.RateLimiter;
import com.yadaventerprise.phoneserver.security.TokenUtil;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.util.MimeTypes;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    private static final int DEFAULT_DAILY_QUOTA = 100000;
    private static final int DEFAULT_MONTHLY_QUOTA = 3000000;
    private static final int DEFAULT_CONCURRENT_LIMIT = 32;

    private static volatile RateLimiter limiter;
    private static volatile int limiterMax;
    private static final java.util.concurrent.ConcurrentHashMap<String, TokenLimiter> tokenLimiters =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> concurrentCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> quotaLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class TokenLimiter {
        final int max;
        final RateLimiter limiter;

        TokenLimiter(int max) {
            this.max = max;
            this.limiter = new RateLimiter(max, API_TOKEN_WINDOW_MS);
        }
    }

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
                || p.endsWith("/remote/files/upload")
                || (p.contains("/databases/") && p.endsWith("/import"))
                || p.endsWith("/backups/import");
    }

    // ---- entry point ----

    public static void handle(Context context, SimpleHttpServer.Request req, OutputStream out) {
        String concurrentTokenId = null;
        try {
            if (req.bodyTooLarge) {
                sendError(context, req, out, 413, "request body is too large for a buffered request; "
                        + "upload large files as a raw body (non-multipart)");
                return;
            }
            concurrentTokenId = dispatch(context, req, out);
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
        } finally {
            if (concurrentTokenId != null) releaseConcurrent(concurrentTokenId);
            recordApiUsage(context, req);
        }
    }

    private static String dispatch(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws Exception {
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);

        // CORS preflight is answered before auth so browsers can negotiate.
        if (method.equals("OPTIONS")) {
            sendPreflight(context, req, out);
            return null;
        }

        List<String> parts = segments(req.path);
        String head = parts.isEmpty() ? "" : parts.get(0);

        if (head.equals("remote") && RemoteApi.isPublicPath(parts)) {
            if (!enforceRateLimit(context, req, out)) return null;
            RemoteApi.handlePublic(context, req, out, parts);
            return null;
        }

        // Public, non-sensitive liveness probe.
        if (head.equals("health")) {
            requireMethod(method, "GET");
            sendHealth(context, req, out);
            return null;
        }

        // Everything past here is authenticated.
        if (!enforceRateLimit(context, req, out)) return null;
        Auth auth = authenticate(context, req);
        if (auth == null) {
            sendUnauthorized(req, out, context);
            return null;
        }
        if (auth.tokenId != null && !enforceTokenRateLimit(context, req, out, auth)) return null;
        String concurrentTokenId = null;
        if (auth.tokenId != null) {
            if (!acquireConcurrent(context, req, out, auth)) return null;
            concurrentTokenId = auth.tokenId;
            if (!enforceQuota(context, req, out, auth)) { releaseConcurrent(concurrentTokenId); return null; }
        }

        if (head.isEmpty()) {
            requireMethod(method, "GET");
            sendIndex(req, out);
            return concurrentTokenId;
        }
        if (head.equals("system")) {
            if (parts.size() == 1) {
                requireMethod(method, "GET");
                sendSystem(context, req, out, auth);
            } else {
                SystemApi.handle(context, req, out, auth, parts);
            }
            return concurrentTokenId;
        }
        if (head.equals("remote")) {
            RemoteApi.handle(context, req, out, auth, parts);
            return concurrentTokenId;
        }
        if (head.equals("tokens")) {
            handleTokens(context, req, out, auth, parts);
            return concurrentTokenId;
        }
        if (head.equals("developer")) {
            DeveloperApi.handle(context, req, out, auth, parts);
            return concurrentTokenId;
        }
        if (head.equals("ai")) {
            AiApi.handle(context, req, out, auth, parts);
            return concurrentTokenId;
        }
        if (head.equals("projects")) {
            handleProjects(context, req, out, auth, parts);
            return concurrentTokenId;
        }
        if (head.equals("mail")) {
            MailApi.handle(context, req, out, auth, parts);
            return concurrentTokenId;
        }
        throw DataException.notFound("endpoint");
    }

    // ---- authentication & limits ----

    /** A resolved caller: either the interactive password (admin) or an API token. */
    static final class Auth {
        boolean admin;
        boolean canWrite = true;
        /** null => every project (admin or an unscoped token). */
        String projectId;
        String tokenId;
        TokenPolicy policy;
        RemoteSession remoteSession;

        String ownerId() {
            if (remoteSession != null) return remoteSession.subjectType + ":" + remoteSession.subjectId;
            if (tokenId != null && !tokenId.isEmpty()) return "token:" + tokenId;
            return admin ? "admin:local" : "";
        }
    }

    private static Auth authenticate(Context context, SimpleHttpServer.Request req) {
        // Bearer token first, so non-interactive clients have a scoped option.
        String bearer = bearerToken(req);
        if (bearer != null && req.path != null && req.path.startsWith(BASE + "/remote/")) {
            RemoteSession session = RemoteSessionStore.get(context).verify(bearer);
            if (session != null) {
                Auth auth = new Auth();
                auth.remoteSession = session;
                auth.canWrite = false;
                SimpleHttpServer.noteActor(session.subjectType, session.subjectId);
                return auth;
            }
        }
        if (bearer != null) {
            Records.ApiTokenRecord token = TokenStore.verify(context, bearer, req.remoteIp);
            if (token == null) {
                IntrusionDetector.recordAuthFailure(context, req.remoteIp, req.method, req.path,
                        "invalid, suspended, or expired API token");
                return null;
            }
            TokenPolicy policy = TokenPolicyStore.get(context).get(token.id, token);
            if (policy.isExpired() || (token.expiresAt > 0 && token.expiresAt <= System.currentTimeMillis())) {
                IntrusionDetector.recordAuthFailure(context, req.remoteIp, req.method, req.path,
                        "expired API token");
                return null;
            }
            Auth auth = new Auth();
            auth.tokenId = token.id;
            auth.policy = policy;
            auth.admin = policy.isAdmin();
            auth.canWrite = policy.canWrite();
            auth.projectId = token.projectId;
            SimpleHttpServer.noteActor("token", token.id);
            return auth;
        }

        String stored = ContentStore.getPassword(context);
        if (stored == null || stored.isEmpty()) {
            // No password configured: refuse anonymous admin access to the data
            // API. A token is required (created from the app UI).
            IntrusionDetector.recordAuthFailure(context, req.remoteIp, req.method, req.path,
                    "no API credential");
            return null;
        }
        String supplied = req.headers.get("x-auth-password");
        if (supplied == null) supplied = req.query.get("pw");
        if (TokenUtil.constantTimeEquals(stored, supplied)
                || TokenUtil.constantTimeEquals(stored, basicPassword(req))) {
            Auth auth = new Auth();
            auth.admin = true;
            auth.canWrite = true;
            SimpleHttpServer.noteActor("password", "");
            return auth;
        }
        IntrusionDetector.recordAuthFailure(context, req.remoteIp, req.method, req.path,
                "wrong access password");
        return null;
    }

    private static boolean enforceTokenRateLimit(Context context, SimpleHttpServer.Request req,
                                                 OutputStream out, Auth auth) throws IOException {
        if (auth.policy == null || auth.policy.rateLimitPerMinute <= 0) return true;
        TokenLimiter tokenLimiter = tokenLimiters.get(auth.tokenId);
        if (tokenLimiter == null || tokenLimiter.max != auth.policy.rateLimitPerMinute) {
            tokenLimiter = new TokenLimiter(auth.policy.rateLimitPerMinute);
            tokenLimiters.put(auth.tokenId, tokenLimiter);
        }
        if (tokenLimiter.limiter.tryAcquire(auth.tokenId)) return true;
        IntrusionDetector.recordRateLimit(context, req.remoteIp, req.method, req.path);
        sendRateLimited(out, context, req, "token rate limit exceeded, slow down", 60);
        return false;
    }

    private static boolean acquireConcurrent(Context context, SimpleHttpServer.Request req, OutputStream out, Auth auth) throws IOException {
        Records.ApiTokenRecord token = TokenStore.findById(context, auth.tokenId);
        int limit = token == null ? DEFAULT_CONCURRENT_LIMIT : token.concurrentLimit;
        if (limit <= 0) limit = DEFAULT_CONCURRENT_LIMIT;
        java.util.concurrent.atomic.AtomicInteger counter = concurrentCounts.computeIfAbsent(auth.tokenId, k -> new java.util.concurrent.atomic.AtomicInteger(0));
        while (true) {
            int current = counter.get();
            if (current < 0) {
                counter.compareAndSet(current, 0);
                continue;
            }
            if (current >= limit) {
                sendRateLimited(out, context, req, "concurrent request limit exceeded", 1);
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    private static void releaseConcurrent(String tokenId) {
        if (tokenId == null) return;
        java.util.concurrent.atomic.AtomicInteger counter = concurrentCounts.get(tokenId);
        if (counter == null) return;
        int prev = counter.getAndUpdate(v -> v > 0 ? v - 1 : 0);
        if (prev <= 0) counter.set(0);
    }

    private static boolean enforceQuota(Context context, SimpleHttpServer.Request req, OutputStream out, Auth auth) throws IOException {
        Records.ApiTokenRecord token = TokenStore.findById(context, auth.tokenId);
        long daily = token == null ? 0 : token.dailyQuota;
        long monthly = token == null ? 0 : token.monthlyQuota;
        if (daily <= 0) daily = DEFAULT_DAILY_QUOTA;
        if (monthly <= 0) monthly = DEFAULT_MONTHLY_QUOTA;
        long now = System.currentTimeMillis();
        com.yadaventerprise.phoneserver.data.developer.ApiUsageStore usage = com.yadaventerprise.phoneserver.data.developer.ApiUsageStore.get(context);
        if (!usage.tryConsumeQuota(auth.tokenId, now, daily, monthly)) {
            long dayCount = usage.countForTokenInUtcDay(auth.tokenId, now);
            if (dayCount >= daily) sendRateLimited(out, context, req, "daily API quota exceeded", retryAfterSeconds(now, true));
            else sendRateLimited(out, context, req, "monthly API quota exceeded", retryAfterSeconds(now, false));
            return false;
        }
        req.quotaReserved = true;
        return true;
    }

    private static int retryAfterSeconds(long nowMs, boolean daily) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(nowMs);
        if (daily) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
        } else {
            cal.add(java.util.Calendar.MONTH, 1);
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
        }
        long diff = (cal.getTimeInMillis() - nowMs) / 1000L;
        if (diff < 1) diff = 1;
        if (diff > Integer.MAX_VALUE) diff = 3600;
        return (int) diff;
    }

    private static void sendRateLimited(OutputStream out, Context context, SimpleHttpServer.Request req, String message, int retryAfterSeconds) throws IOException {
        IntrusionDetector.recordRateLimit(context, req.remoteIp, req.method, req.path);
        try {
            JSONObject body = new JSONObject();
            body.put("error", message);
            body.put("status", 429);
            writeRaw(out, 429, "Too Many Requests", "application/json; charset=utf-8",
                    body.toString().getBytes(StandardCharsets.UTF_8), context, req, "Retry-After: " + Math.max(1, retryAfterSeconds));
        } catch (Exception e) {
            writeRaw(out, 429, "Too Many Requests", "application/json; charset=utf-8",
                    ("{\"error\":\"" + message + "\",\"status\":429}").getBytes(StandardCharsets.UTF_8), context, req, "Retry-After: " + Math.max(1, retryAfterSeconds));
        }
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
        IntrusionDetector.recordRateLimit(context, req.remoteIp, req.method, req.path);
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

    static void requirePermission(Auth auth, String permission) throws DataException {
        if (auth.admin) return;
        if (auth.policy == null || !auth.policy.can(permission)) {
            throw DataException.forbidden("this token lacks the " + permission + " permission");
        }
    }

    static void requireWrite(Auth auth, String permission) throws DataException {
        requirePermission(auth, permission);
        if (!auth.canWrite) throw DataException.forbidden("this credential is read-only");
    }

    private static void requireAdmin(Auth auth) throws DataException {
        if (!auth.admin) throw DataException.forbidden("administrator access required");
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
        endpoints.put("GET " + BASE + "/system/monitor");
        endpoints.put("GET " + BASE + "/system/diagnostics");
        endpoints.put("GET/PATCH " + BASE + "/system/automation");
        endpoints.put("GET/POST " + BASE + "/system/tasks");
        endpoints.put("GET/PATCH/POST/DELETE " + BASE + "/system/tasks/{id}");
        endpoints.put("GET/POST " + BASE + "/system/webhooks");
        endpoints.put("GET/PATCH/DELETE " + BASE + "/system/webhooks/{id}");
        endpoints.put("POST " + BASE + "/system/webhooks/{id}/test");
        endpoints.put("POST " + BASE + "/system/start");
        endpoints.put("POST " + BASE + "/system/stop");
        endpoints.put("POST " + BASE + "/system/restart");
        endpoints.put("GET " + BASE + "/system/logs");
        endpoints.put("GET " + BASE + "/system/maintenance");
        endpoints.put("POST " + BASE + "/system/maintenance/clean");
        endpoints.put("POST " + BASE + "/system/maintenance/verify-backups");
        endpoints.put("POST " + BASE + "/remote/auth/login");
        endpoints.put("POST " + BASE + "/remote/auth/logout");
        endpoints.put("GET " + BASE + "/remote/dashboard");
        endpoints.put("POST " + BASE + "/remote/pairing/request");
        endpoints.put("POST " + BASE + "/remote/pairing/status");
        endpoints.put("GET/POST " + BASE + "/remote/pairing");
        endpoints.put("POST " + BASE + "/remote/pairing/{id}/approve");
        endpoints.put("POST " + BASE + "/remote/pairing/{id}/reject");
        endpoints.put("GET " + BASE + "/remote/devices");
        endpoints.put("GET/PATCH/DELETE " + BASE + "/remote/devices/{id}");
        endpoints.put("POST " + BASE + "/remote/devices/{id}/heartbeat");
        endpoints.put("POST " + BASE + "/remote/devices/{id}/disconnect");
        endpoints.put("GET/POST " + BASE + "/remote/users");
        endpoints.put("GET/PATCH/DELETE " + BASE + "/remote/users/{id}");
        endpoints.put("DELETE " + BASE + "/remote/users/{id}/sessions");
        endpoints.put("GET " + BASE + "/remote/roles");
        endpoints.put("GET " + BASE + "/remote/audit");
        endpoints.put("GET/PATCH " + BASE + "/remote/settings");
        endpoints.put("POST " + BASE + "/remote/server/{action}");
        endpoints.put("GET " + BASE + "/remote/files/list");
        endpoints.put("GET " + BASE + "/remote/files/download");
        endpoints.put("POST " + BASE + "/remote/files/upload");
        endpoints.put("POST " + BASE + "/remote/files/delete");
        endpoints.put("POST " + BASE + "/remote/files/rename");
        endpoints.put("POST " + BASE + "/remote/files/mkdir");
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
        endpoints.put("GET/POST " + BASE + "/mail/accounts");
        endpoints.put("GET/PATCH/DELETE " + BASE + "/mail/accounts/{id}");
        endpoints.put("POST " + BASE + "/mail/accounts/{id}/test");
        endpoints.put("POST " + BASE + "/mail/accounts/{id}/sync");
        endpoints.put("GET " + BASE + "/mail/accounts/{id}/messages");
        endpoints.put("POST " + BASE + "/mail/accounts/{id}/send");
        endpoints.put("POST " + BASE + "/mail/accounts/{id}/drafts");
        endpoints.put("GET " + BASE + "/mail/messages/{id}");
        endpoints.put("POST " + BASE + "/mail/messages/{id}/read");
        endpoints.put("DELETE " + BASE + "/mail/messages/{id}");
        endpoints.put("GET " + BASE + "/mail/messages/{id}/attachments/{index}");
        json.put("endpoints", endpoints);
        sendJson(null, req, out, 200, json);
    }

    // ---- projects ----

    private static void handleProjects(Context context, SimpleHttpServer.Request req, OutputStream out,
                                       Auth auth, List<String> parts) throws Exception {
        String method = req.method.toUpperCase(Locale.US);
        if (parts.size() == 1) {
            if (method.equals("GET")) {
                requirePermission(auth, TokenPolicy.PROJECTS_READ);
                JSONArray arr = new JSONArray();
                for (Records.ProjectRecord p : ProjectStore.list(context)) {
                    requireProjectAccess(auth, p.id);
                    arr.put(projectJson(context, p));
                }
                sendJson(context, req, out, 200, wrap("projects", arr));
                return;
            }
            if (method.equals("POST")) {
                requireWrite(auth, TokenPolicy.PROJECTS_WRITE);
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
                requirePermission(auth, TokenPolicy.PROJECTS_READ);
                sendJson(context, req, out, 200, projectJson(context, ProjectStore.require(context, projectId)));
                return;
            }
            if (method.equals("PATCH") || method.equals("PUT")) {
                requireWrite(auth, TokenPolicy.PROJECTS_WRITE);
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
                requireWrite(auth, TokenPolicy.PROJECTS_WRITE);
                ProjectStore.delete(context, projectId, req.query.get("confirm"));
                sendJson(context, req, out, 200, ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String section = parts.get(2);
        if (section.equals("stats") && parts.size() == 3 && method.equals("GET")) {
            requirePermission(auth, TokenPolicy.PROJECTS_READ);
            sendJson(context, req, out, 200, projectStatsJson(context, projectId));
            return;
        }
        if (section.equals("usage") && parts.size() == 3 && method.equals("GET")) {
            requirePermission(auth, TokenPolicy.PROJECTS_READ);
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
                requirePermission(auth, TokenPolicy.DATABASES_READ);
                JSONArray arr = new JSONArray();
                for (Records.DbRecord db : DatabaseManager.list(context, projectId)) arr.put(dbJson(db));
                sendJson(context, req, out, 200, wrap("databases", arr));
                return;
            }
            if (method.equals("POST")) {
                requireWrite(auth, TokenPolicy.DATABASES_WRITE);
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
                requirePermission(auth, TokenPolicy.DATABASES_READ);
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
                requireWrite(auth, TokenPolicy.DATABASES_WRITE);
                DatabaseManager.delete(context, projectId, dbName);
                sendJson(context, req, out, 200, ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String action = parts.get(4);
        if (action.equals("tables")) {
            if (parts.size() == 5 && method.equals("GET")) {
                requirePermission(auth, TokenPolicy.DATABASES_READ);
                JSONArray arr = new JSONArray();
                for (Records.TableInfo t : DatabaseManager.listTables(context, projectId, dbName)) {
                    arr.put(tableJson(t));
                }
                sendJson(context, req, out, 200, wrap("tables", arr));
                return;
            }
            if (parts.size() == 6 && method.equals("GET")) {
                requirePermission(auth, TokenPolicy.DATABASES_READ);
                Records.TableInfo table = DatabaseManager.tableInfo(context, projectId, dbName,
                        decode(parts.get(5)));
                sendJson(context, req, out, 200, tableJson(table));
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        if (action.equals("query") && method.equals("POST")) {
            requirePermission(auth, TokenPolicy.DATABASES_READ);
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
            requireWrite(auth, TokenPolicy.DATABASES_WRITE);
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
            requirePermission(auth, TokenPolicy.DATABASES_READ);
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
            requireWrite(auth, TokenPolicy.DATABASES_WRITE);
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
                requirePermission(auth, TokenPolicy.FILES_READ);
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
                requireWrite(auth, TokenPolicy.FILES_WRITE);
                uploadFile(context, req, out, projectId);
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String fileId = decode(parts.get(3));
        if (parts.size() == 4) {
            if (method.equals("GET")) {
                requirePermission(auth, TokenPolicy.FILES_READ);
                sendJson(context, req, out, 200, fileJson(FileStore.require(context, projectId, fileId)));
                return;
            }
            if (method.equals("PATCH") || method.equals("PUT")) {
                requireWrite(auth, TokenPolicy.FILES_WRITE);
                JSONObject body = readJson(req);
                Records.FileRecord current = FileStore.require(context, projectId, fileId);
                if (body.has("name")) current = FileStore.rename(context, projectId, fileId, body.optString("name"));
                if (body.has("folder")) current = FileStore.move(context, projectId, fileId, body.optString("folder"));
                sendJson(context, req, out, 200, fileJson(current));
                return;
            }
            if (method.equals("DELETE")) {
                requireWrite(auth, TokenPolicy.FILES_WRITE);
                FileStore.delete(context, projectId, fileId);
                sendJson(context, req, out, 200, ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String action = parts.get(4);
        if (action.equals("download") && method.equals("GET")) {
            requirePermission(auth, TokenPolicy.FILES_READ);
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
            requirePermission(auth, TokenPolicy.FILES_READ);
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
            requireWrite(auth, TokenPolicy.FILES_WRITE);
            JSONObject body = readJson(req);
            String path = body.optString("path", null);
            FileStore.createFolder(context, projectId, path);
            sendJson(context, req, out, 201, ok());
            return;
        }
        if (method.equals("DELETE")) {
            requireWrite(auth, TokenPolicy.FILES_WRITE);
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
        JSONObject hookData = new JSONObject();
        try {
            hookData.put("name", record.originalName);
            hookData.put("size", record.sizeBytes);
            hookData.put("projectId", projectId);
            hookData.put("source", "api");
        } catch (Exception ignored) {
        }
        WebhookDispatcher.emit(context, WebhookDispatcher.EVENT_FILE_UPLOADED, hookData);
        sendJson(context, req, out, 201, fileJson(record));
    }

    // ---- backups ----

    private static void handleBackups(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      Auth auth, String projectId, List<String> parts) throws Exception {
        String method = req.method.toUpperCase(Locale.US);
        if (parts.size() == 3) {
            if (method.equals("GET")) {
                requirePermission(auth, TokenPolicy.BACKUPS_READ);
                JSONArray arr = new JSONArray();
                for (Records.BackupRecord b : BackupManager.list(context, projectId)) arr.put(backupJson(b));
                sendJson(context, req, out, 200, wrap("backups", arr));
                return;
            }
            if (method.equals("POST")) {
                requireWrite(auth, TokenPolicy.BACKUPS_WRITE);
                JSONObject body = readJson(req);
                com.yadaventerprise.phoneserver.data.storage.BackupOptions options =
                        new com.yadaventerprise.phoneserver.data.storage.BackupOptions();
                options.includeFiles = !body.has("includeFiles") || body.optBoolean("includeFiles", true);
                options.note = body.optString("note", "");
                String type = body.optString("type", com.yadaventerprise.phoneserver.data.storage.BackupOptions.TYPE_FULL);
                options.type = com.yadaventerprise.phoneserver.data.storage.BackupOptions.TYPE_INCREMENTAL.equals(type)
                        ? com.yadaventerprise.phoneserver.data.storage.BackupOptions.TYPE_INCREMENTAL
                        : com.yadaventerprise.phoneserver.data.storage.BackupOptions.TYPE_FULL;
                options.parentBackupId = body.optString("parentBackupId", "");
                String password = body.optString("password", req.headers.get("x-backup-password"));
                if (password != null && !password.isEmpty()) options.password = password.toCharArray();
                Records.BackupRecord backup = BackupManager.create(context, projectId, options);
                sendJson(context, req, out, 201, backupJson(backup));
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String backupId = decode(parts.get(3));
        if (backupId.equals("import") && method.equals("POST")) {
            requireWrite(auth, TokenPolicy.BACKUPS_WRITE);
            importBackup(context, req, out, projectId);
            return;
        }
        if (backupId.equals("import-preview") && method.equals("POST")) {
            requireWrite(auth, TokenPolicy.BACKUPS_WRITE);
            previewBackupImport(context, req, out, projectId);
            return;
        }
        if (backupId.equals("import-validate") && method.equals("POST")) {
            requirePermission(auth, TokenPolicy.BACKUPS_READ);
            previewBackupImport(context, req, out, projectId);
            return;
        }
        if (backupId.equals("export-config") && method.equals("POST")) {
            requirePermission(auth, TokenPolicy.BACKUPS_READ);
            JSONObject body = readJson(req);
            JSONArray categories = body.optJSONArray("categories");
            sendJson(context, req, out, 200, ImportExportService.exportConfig(context, projectId, categories));
            return;
        }
        if (backupId.equals("import-config-preview") && method.equals("POST")) {
            requirePermission(auth, TokenPolicy.BACKUPS_READ);
            JSONObject body = readJson(req);
            sendJson(context, req, out, 200, ImportExportService.previewConfigImport(context, projectId, body).toJson());
            return;
        }
        if (backupId.equals("import-config") && method.equals("POST")) {
            requireWrite(auth, TokenPolicy.BACKUPS_WRITE);
            JSONObject body = readJson(req);
            sendJson(context, req, out, 200, ImportExportService.applyConfigImport(context, projectId, body));
            return;
        }
        if (parts.size() == 4) {
            if (method.equals("GET")) {
                requirePermission(auth, TokenPolicy.BACKUPS_READ);
                sendJson(context, req, out, 200, backupJson(BackupManager.require(context, projectId, backupId)));
                return;
            }
            if (method.equals("DELETE")) {
                requireWrite(auth, TokenPolicy.BACKUPS_WRITE);
                BackupManager.delete(context, projectId, backupId);
                sendJson(context, req, out, 200, ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }

        String action = parts.get(4);
        if (action.equals("restore") && method.equals("POST")) {
            requireWrite(auth, TokenPolicy.BACKUPS_WRITE);
            JSONObject body = readJson(req);
            com.yadaventerprise.phoneserver.data.storage.RestoreOptions options =
                    new com.yadaventerprise.phoneserver.data.storage.RestoreOptions();
            options.confirm = body.optBoolean("confirm", false);
            options.createRecoveryPoint = !body.has("createRecoveryPoint") || body.optBoolean("createRecoveryPoint", true);
            options.restoreDatabases = !body.has("restoreDatabases") || body.optBoolean("restoreDatabases", true);
            options.restoreFiles = !body.has("restoreFiles") || body.optBoolean("restoreFiles", true);
            options.restoreProjectConfig = !body.has("restoreProjectConfig") || body.optBoolean("restoreProjectConfig", true);
            String password = body.optString("password", req.headers.get("x-backup-password"));
            if (password != null && !password.isEmpty()) options.password = password.toCharArray();
            BackupManager.restore(context, projectId, backupId, options);
            sendJson(context, req, out, 200, ok());
            return;
        }
        if (action.equals("validate") && method.equals("POST")) {
            requirePermission(auth, TokenPolicy.BACKUPS_READ);
            JSONObject body = readJson(req);
            String password = body.optString("password", req.headers.get("x-backup-password"));
            char[] pw = password != null && !password.isEmpty() ? password.toCharArray() : null;
            try {
                Map<String, String> manifest = BackupManager.validate(context, projectId, backupId, pw);
                JSONObject json = new JSONObject();
                json.put("valid", true);
                for (Map.Entry<String, String> e : manifest.entrySet()) {
                    String key = e.getKey();
                    if (key == null) continue;
                    String lower = key.toLowerCase(Locale.US);
                    if (lower.contains("password") || lower.contains("secret") || lower.contains("token")
                            || lower.contains("apikey") || lower.contains("api_key") || lower.contains("credential")) {
                        continue;
                    }
                    json.put(key, e.getValue());
                }
                sendJson(context, req, out, 200, json);
            } finally {
                if (pw != null) java.util.Arrays.fill(pw, '\0');
            }
            return;
        }
        if (action.equals("preview") && method.equals("POST")) {
            requirePermission(auth, TokenPolicy.BACKUPS_READ);
            JSONObject body = readJson(req);
            String password = body.optString("password", req.headers.get("x-backup-password"));
            char[] pw = password != null && !password.isEmpty() ? password.toCharArray() : null;
            try {
                com.yadaventerprise.phoneserver.data.storage.BackupPreview preview =
                        BackupManager.preview(context, projectId, backupId, pw);
                sendJson(context, req, out, preview.valid ? 200 : 422, preview.toJson());
            } finally {
                if (pw != null) java.util.Arrays.fill(pw, '\0');
            }
            return;
        }
        if (action.equals("download") && method.equals("GET")) {
            requirePermission(auth, TokenPolicy.BACKUPS_READ);
            Records.BackupRecord backup = BackupManager.require(context, projectId, backupId);
            File archive = BackupManager.archiveFile(context, projectId, backupId);
            String mime = backup.encrypted || archive.getName().endsWith(".enc")
                    ? "application/octet-stream" : "application/zip";
            serveDownload(context, req, out, archive, mime, backup.fileName, true);
            return;
        }
        throw DataException.notFound("endpoint");
    }

    private static void importBackup(Context context, SimpleHttpServer.Request req, OutputStream out,
                                     String projectId) throws Exception {
        boolean confirm = "true".equalsIgnoreCase(req.query.get("confirm"));
        String password = req.headers.get("x-backup-password");
        File temp = ContentStore.getStorageLayout(context).newTempFile("restore-import");
        try {
            long written = storeBody(context, req, temp);
            if (written == 0) throw new DataException("no backup content was uploaded", 400);
            com.yadaventerprise.phoneserver.data.storage.RestoreOptions options =
                    new com.yadaventerprise.phoneserver.data.storage.RestoreOptions();
            options.confirm = confirm;
            options.createRecoveryPoint = !"false".equalsIgnoreCase(req.query.get("createRecoveryPoint"));
            options.restoreDatabases = !"false".equalsIgnoreCase(req.query.get("restoreDatabases"));
            options.restoreFiles = !"false".equalsIgnoreCase(req.query.get("restoreFiles"));
            options.restoreProjectConfig = !"false".equalsIgnoreCase(req.query.get("restoreProjectConfig"));
            if (password != null && !password.isEmpty()) options.password = password.toCharArray();
            BackupManager.importArchive(context, projectId, temp, options);
            sendJson(context, req, out, 200, ok());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void previewBackupImport(Context context, SimpleHttpServer.Request req, OutputStream out,
                                            String projectId) throws Exception {
        String password = req.headers.get("x-backup-password");
        File temp = ContentStore.getStorageLayout(context).newTempFile("restore-preview");
        try {
            long written = storeBody(context, req, temp);
            if (written == 0) throw new DataException("no backup content was uploaded", 400);
            char[] pw = password != null && !password.isEmpty() ? password.toCharArray() : null;
            try {
                com.yadaventerprise.phoneserver.data.storage.BackupPreview preview = BackupManager.inspectArchive(context, projectId, temp, pw);
                sendJson(context, req, out, preview.valid ? 200 : 422, preview.toJson());
            } finally {
                if (pw != null) java.util.Arrays.fill(pw, '\0');
            }
        } finally {
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
        json.put("encrypted", b.encrypted);
        json.put("backupType", b.backupType == null ? "full" : b.backupType);
        json.put("checksum", b.checksum == null ? "" : b.checksum);
        json.put("parentBackupId", b.parentBackupId == null ? "" : b.parentBackupId);
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
        TokenPolicyStore policyStore = TokenPolicyStore.get(context);
        if (parts.size() == 1) {
            if (method.equals("GET")) {
                JSONArray arr = new JSONArray();
                for (Records.ApiTokenRecord t : TokenStore.list(context)) {
                    arr.put(tokenJson(t, policyStore.get(t.id, t)));
                }
                sendJson(context, req, out, 200, wrap("tokens", arr));
                return;
            }
            if (method.equals("POST")) {
                JSONObject body = readJson(req);
                String projectId = body.has("projectId") && !body.isNull("projectId")
                        ? body.optString("projectId") : null;
                String role = body.optString("role", "");
                if (!TokenPolicy.isValidRole(role)) {
                    boolean canWrite = !body.has("canWrite") || body.optBoolean("canWrite", true);
                    role = canWrite ? TokenPolicy.ROLE_EDITOR : TokenPolicy.ROLE_VIEWER;
                }
                TokenPolicy policy = TokenPolicy.preset(role);
                applyPolicyBody(policy, body);
                long expiresAt = policy.expiresAt;
                if (expiresAt <= 0) expiresAt = body.optLong("expiresAt", 0);
                boolean canWrite = policy.canWrite();
                TokenStore.Created created = TokenStore.create(context, body.optString("name", null),
                        projectId, canWrite, expiresAt, Math.max(0, body.optLong("dailyQuota", 0)), Math.max(0, body.optLong("monthlyQuota", 0)), Math.max(0, body.optInt("concurrentLimit", 0)));
                policy.tokenId = created.record.id;
                policy.expiresAt = expiresAt;
                policyStore.save(policy);
                JSONObject json = tokenJson(created.record, policy);
                json.put("token", created.token);
                json.put("warning", "store this token now; it cannot be shown again");
                sendJson(context, req, out, 201, json);
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        String id = decode(parts.get(1));
        if (parts.size() == 3 && "regenerate".equals(parts.get(2))) {
            requireMethod(method, "POST");
            Records.ApiTokenRecord record = TokenStore.findById(context, id);
            if (record == null) throw DataException.notFound("token");
            TokenStore.Created created = TokenStore.regenerate(context, id);
            TokenPolicy policy = policyStore.get(id, created.record);
            JSONObject json = tokenJson(created.record, policy);
            json.put("token", created.token);
            json.put("warning", "store this token now; it cannot be shown again");
            sendJson(context, req, out, 200, json);
            return;
        }
        if (parts.size() != 2) throw DataException.notFound("token endpoint");
        if (method.equals("DELETE")) {
            TokenStore.revoke(context, id);
            policyStore.delete(id);
            sendJson(context, req, out, 200, ok());
            return;
        }
        if (method.equals("PATCH") || method.equals("PUT")) {
            Records.ApiTokenRecord record = TokenStore.findById(context, id);
            if (record == null) throw DataException.notFound("token");
            JSONObject body = readJson(req);
            TokenPolicy policy = policyStore.get(id, record);
            if (body.has("name")) TokenStore.rename(context, id, body.optString("name", record.name));
            if (body.has("suspended")) TokenStore.setSuspended(context, id, body.optBoolean("suspended", false));
            if (body.has("expiresAt")) TokenStore.setExpiresAt(context, id, Math.max(0, body.optLong("expiresAt", 0)));
            if (body.has("revoked") && body.optBoolean("revoked", false)) TokenStore.revoke(context, id);
            if (body.has("dailyQuota") || body.has("monthlyQuota") || body.has("concurrentLimit")) {
                TokenStore.updateQuotas(context, id, Math.max(0, body.optLong("dailyQuota", record.dailyQuota)), Math.max(0, body.optLong("monthlyQuota", record.monthlyQuota)), Math.max(0, body.optInt("concurrentLimit", record.concurrentLimit)));
            }
            applyPolicyBody(policy, body);
            if (body.has("expiresAt")) policy.expiresAt = Math.max(0, body.optLong("expiresAt", 0));
            policyStore.save(policy);
            Records.ApiTokenRecord updated = TokenStore.findById(context, id);
            sendJson(context, req, out, 200, tokenJson(updated == null ? record : updated, policy));
            return;
        }
        throw new DataException("method not allowed", 405);
    }

    private static JSONObject tokenJson(Records.ApiTokenRecord token, TokenPolicy policy) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", token.id);
        json.put("name", token.name);
        json.put("projectId", token.projectId == null ? JSONObject.NULL : token.projectId);
        json.put("canWrite", policy.canWrite());
        json.put("role", policy.role);
        JSONArray permissions = new JSONArray();
        for (String permission : policy.permissions) permissions.put(permission);
        json.put("permissions", permissions);
        json.put("rateLimitPerMinute", policy.rateLimitPerMinute);
        json.put("dailyQuota", token.dailyQuota);
        json.put("monthlyQuota", token.monthlyQuota);
        json.put("concurrentLimit", token.concurrentLimit);
        json.put("expiresAt", token.expiresAt > 0 ? token.expiresAt : policy.expiresAt);
        json.put("createdAt", token.createdAt);
        json.put("lastUsedAt", token.lastUsedAt);
        json.put("lastUsedIp", token.lastUsedIp == null ? "" : token.lastUsedIp);
        json.put("keyPrefix", token.keyPrefix == null ? "" : token.keyPrefix);
        json.put("suspended", token.suspended);
        json.put("revoked", token.revoked);
        json.put("status", token.revoked ? "revoked" : (token.suspended ? "suspended" : "active"));
        return json;
    }

    static void applyPolicyBody(TokenPolicy policy, JSONObject body) throws DataException {
        if (body.has("role")) {
            String role = body.optString("role", "");
            if (!TokenPolicy.isValidRole(role)) throw new DataException("unknown token role", 400);
            policy.role = role;
            policy.permissions.clear();
            policy.permissions.addAll(TokenPolicy.permissionsForRole(role));
        }
        if (body.has("permissions")) {
            JSONArray array = body.optJSONArray("permissions");
            if (array == null) throw new DataException("'permissions' must be an array", 400);
            policy.permissions.clear();
            for (int i = 0; i < array.length(); i++) {
                String permission = array.optString(i, "");
                if (!TokenPolicy.allPermissions().contains(permission)) {
                    throw new DataException("unknown permission: " + permission, 400);
                }
                policy.permissions.add(permission);
            }
        }
        if (body.has("rateLimitPerMinute")) {
            int limit = body.optInt("rateLimitPerMinute", 0);
            if (limit < 0 || limit > 6000) {
                throw new DataException("rateLimitPerMinute must be between 0 and 6000", 400);
            }
            policy.rateLimitPerMinute = limit;
        }
        if (body.has("expiresAt")) {
            long expiresAt = body.optLong("expiresAt", 0);
            if (expiresAt < 0) throw new DataException("expiresAt must be positive or 0", 400);
            policy.expiresAt = expiresAt;
        }
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

    static JSONObject readJson(SimpleHttpServer.Request req) throws DataException {
        if (req.body == null || req.body.length == 0) return new JSONObject();
        try {
            return new JSONObject(new String(req.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new DataException("request body is not valid JSON", 400);
        }
    }

    static void requireBody(JSONObject body) throws DataException {
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

    static void requireMethod(String actual, String... expected) throws DataException {
        for (String value : expected) {
            if (value.equals(actual)) return;
        }
        throw new DataException("method not allowed", 405);
    }

    static void recordApiUsage(Context context, SimpleHttpServer.Request req) {
        if (context == null || req == null || req.path == null || !isApiPath(req.path)) return;
        try {
            com.yadaventerprise.phoneserver.data.developer.ApiUsageEntry entry =
                    new com.yadaventerprise.phoneserver.data.developer.ApiUsageEntry();
            entry.time = System.currentTimeMillis();
            entry.tokenId = req.tokenId == null ? "" : req.tokenId;
            entry.tokenName = req.actor == null ? "" : req.actor;
            entry.method = req.method == null ? "" : req.method;
            entry.path = req.path;
            entry.status = req.responseStatus;
            entry.durationMs = Math.max(0, System.currentTimeMillis() - req.startedAtMillis);
            entry.bytesIn = req.contentLength > 0 ? req.contentLength : (req.body == null ? 0 : req.body.length);
            entry.bytesOut = req.responseBytes;
            entry.rateLimited = req.responseStatus == 429;
            entry.ip = req.remoteIp == null ? "" : req.remoteIp;
            if (entry.tokenId != null && !entry.tokenId.isEmpty()) {
                Records.ApiTokenRecord token = TokenStore.findById(context, entry.tokenId);
                if (token != null) {
                    entry.tokenName = token.name;
                    entry.keyPrefix = token.keyPrefix == null ? "" : token.keyPrefix;
                }
            }
            com.yadaventerprise.phoneserver.data.developer.ApiUsageStore store = com.yadaventerprise.phoneserver.data.developer.ApiUsageStore.get(context);
            if (req.quotaReserved) store.replaceReservation(entry.tokenId, entry);
            else store.append(entry);
        } catch (Exception ignored) {
        }
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

    static JSONObject wrap(String key, Object array) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(key, array);
        return json;
    }

    static JSONObject ok() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("status", "ok");
        return json;
    }

    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date());
    }

    static void sendJson(Context context, SimpleHttpServer.Request req, OutputStream out, int code, Object body)
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
        SimpleHttpServer.noteStatus(code);
        SimpleHttpServer.noteResponseBytes(body == null ? 0 : body.length);
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
    static void serveDownload(Context context, SimpleHttpServer.Request req, OutputStream out,
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
                SimpleHttpServer.noteStatus(416);
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
        SimpleHttpServer.noteStatus(code);

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
