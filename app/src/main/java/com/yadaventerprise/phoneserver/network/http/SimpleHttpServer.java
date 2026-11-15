package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.security.AccessLogEntry;
import com.yadaventerprise.phoneserver.data.security.AccessLogStore;
import com.yadaventerprise.phoneserver.data.security.IntrusionDetector;
import com.yadaventerprise.phoneserver.data.security.IpBlockStore;
import com.yadaventerprise.phoneserver.data.security.SecuritySettingsStore;
import com.yadaventerprise.phoneserver.data.servers.ApiEndpointStore;
import com.yadaventerprise.phoneserver.data.stats.DeviceStats;
import com.yadaventerprise.phoneserver.data.stats.ServerStats;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.model.ApiEndpointConfig;
import com.yadaventerprise.phoneserver.model.KeyValue;
import com.yadaventerprise.phoneserver.security.PathGuard;
import com.yadaventerprise.phoneserver.security.TokenUtil;
import com.yadaventerprise.phoneserver.service.ServerService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A small, dependency-free HTTP server built on plain java.net sockets.
 *
 * Public (no password needed):
 *   GET  /                 -> website/index.html
 *   GET  /status           -> basic JSON status
 *
 * Protected by the access password (see ContentStore.getPassword), sent as
 * either header "X-Auth-Password" or query param "pw":
 *   GET  /files/<path>          -> downloads a file
 *   GET  /api/browse?path=..    -> JSON listing of a folder
 *   POST /api/mkdir             -> JSON body {"path":"..","name":".."}
 *   POST /api/delete            -> JSON body {"path":".."}  (file or folder, recursive)
 *   POST /api/rename            -> JSON body {"path":"..","newName":".."}
 *   POST /api/upload?path=..    -> multipart/form-data, field "file"
 *   GET  /api/<custom>          -> user-defined JSON endpoints (unprotected, unrelated to files)
 *
 * One thread per connection. Simple on purpose - this runs on a phone, not a
 * data-center box, and simple code is far easier to keep working than clever code.
 */
public class SimpleHttpServer {

    private static final String TAG = "SimpleHttpServer";

    /** Longest request/header line accepted; anything larger is treated as a malformed request. */
    private static final int MAX_HEADER_LINE = 8192;
    /** Maximum number of request headers accepted. */
    private static final int MAX_HEADERS = 100;
    /** How long a client may stay silent before its connection is dropped (slowloris defence). */
    private static final int CLIENT_TIMEOUT_MS = 15_000;
    /** Maximum simultaneous client threads, so a connection flood cannot exhaust the device. */
    private static final int MAX_CLIENTS = 64;

    private final int port;
    private final Context appContext;
    private final Semaphore clientSlots = new Semaphore(MAX_CLIENTS);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    public SimpleHttpServer(Context context, int port) {
        this.appContext = context.getApplicationContext();
        this.port = port;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public synchronized void start() {
        if (running) return;
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            acceptThread = new Thread(this::acceptLoop, "PhoneServer-Accept");
            acceptThread.start();
            Log.i(TAG, "Server started on port " + port);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server", e);
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        Log.i(TAG, "Server stopped");
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running) Log.e(TAG, "Accept failed", e);
                continue;
            }
            if (!clientSlots.tryAcquire()) {
                // Too many clients already being served: shed load instead of spawning unbounded threads.
                try {
                    client.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                }
                try {
                    client.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            try {
                Thread t = new Thread(() -> handleClient(client), "PhoneServer-Client");
                t.start();
            } catch (Throwable t) {
                clientSlots.release();
                try {
                    client.close();
                } catch (IOException ignored) {
                }
                Log.e(TAG, "Could not start client thread", t);
            }
        }
    }

    // ---- request reading (byte-safe, so binary uploads survive intact) ----

    /**
     * Cap for request bodies buffered into memory for the JSON data API. Larger
     * bodies (file uploads) are streamed straight off the socket instead, so a
     * phone is never asked to hold a big upload in RAM. Multipart uploads, which
     * are parsed in memory, are rejected above this size.
     */
    static final int MAX_BUFFERED_API_BODY = 64 * 1024 * 1024;

    static class Request {
        String method;
        String path;
        String remoteIp = "unknown";
        Map<String, String> query = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        /** When non-null the body was NOT buffered and must be read from here. */
        java.io.InputStream bodyStream;
        long contentLength = -1;
        /** Set when a buffered body exceeded {@link #MAX_BUFFERED_API_BODY}. */
        boolean bodyTooLarge = false;
        long startedAtMillis;
        int responseStatus;
        String actor = "anonymous";
        String tokenId = "";
        boolean blocked;
    }

    private static final ThreadLocal<Request> CURRENT_REQUEST = new ThreadLocal<>();

    /** Called by response writers so the access log records the real status code. */
    static void noteStatus(int code) {
        Request req = CURRENT_REQUEST.get();
        if (req != null) req.responseStatus = code;
    }

    static void noteActor(String actor, String tokenId) {
        Request req = CURRENT_REQUEST.get();
        if (req == null) return;
        req.actor = actor == null ? "anonymous" : actor;
        req.tokenId = tokenId == null ? "" : tokenId;
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            buf.write(c);
            if (buf.size() > MAX_HEADER_LINE) throw new IOException("header line too long");
            prev = c;
        }
        if (buf.size() == 0) return null;
        return buf.toString("ISO-8859-1");
    }

    private Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;

        Request req = new Request();
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        req.method = parts[0];

        String fullPath = parts[1];
        int q = fullPath.indexOf('?');
        if (q >= 0) {
            req.path = fullPath.substring(0, q);
            parseQuery(fullPath.substring(q + 1), req.query);
        } else {
            req.path = fullPath;
        }

        String line;
        int headerCount = 0;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (++headerCount > MAX_HEADERS) throw new IOException("too many headers");
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                req.headers.put(key, value);
            }
        }

        int contentLength = 0;
        String cl = req.headers.get("content-length");
        if (cl != null) {
            try {
                contentLength = Integer.parseInt(cl.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        req.contentLength = contentLength;

        // Large binary uploads are streamed straight off the socket by the data
        // API instead of being buffered, so the device never holds a whole
        // multi-hundred-megabyte file in memory just to receive it.
        if (DataApi.wantsStreamedBody(req.method, req.path, req.headers)) {
            req.bodyStream = in;
            return req;
        }

        // Every non-streamed body is bounded, regardless of path. Before this
        // cap, a request to any non-API path could declare a huge Content-Length
        // and make the device allocate that much memory before authentication.
        if (contentLength > MAX_BUFFERED_API_BODY) {
            req.bodyTooLarge = true;
            return req;
        }
        if (contentLength > 0) {
            req.body = readExactly(in, contentLength);
        }

        return req;
    }

    private byte[] readExactly(InputStream in, int length) throws IOException {
        // Grow with the bytes that actually arrive instead of pre-allocating the
        // declared Content-Length. A client that only sends a header can no
        // longer make the device reserve (and possibly exhaust) memory upfront.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(length, 8192));
        byte[] chunk = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int r = in.read(chunk, 0, Math.min(chunk.length, remaining));
            if (r == -1) break;
            buffer.write(chunk, 0, r);
            remaining -= r;
        }
        return buffer.toByteArray();
    }

    private void parseQuery(String query, Map<String, String> out) {
        if (query == null || query.isEmpty()) return;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq >= 0) {
                    String key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    out.put(key, value);
                } else if (!pair.isEmpty()) {
                    out.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ---- connection handling ----

    /** Wraps the socket's OutputStream just to count bytes written, for real traffic stats. */
    private static class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count = 0;

        CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        long count() {
            return count;
        }
    }

    private void handleClient(Socket socket) {
        Request req = null;
        CountingOutputStream out = null;
        long started = System.currentTimeMillis();
        try (Socket s = socket) {
            s.setSoTimeout(CLIENT_TIMEOUT_MS);
            InputStream in = s.getInputStream();
            req = readRequest(in);
            if (req == null) return;

            req.startedAtMillis = started;
            out = new CountingOutputStream(s.getOutputStream());
            req.remoteIp = s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : "unknown";
            CURRENT_REQUEST.set(req);

            if (IpBlockStore.get(appContext).isBlocked(req.remoteIp)) {
                req.blocked = true;
                IntrusionDetector.recordBlockedRequest(appContext, req.remoteIp, req.method, req.path);
                writeResponse(out, 403, "application/json",
                        "{\"error\":\"this IP is blocked\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            route(req, out);

            String ip = req.remoteIp;
            String userAgent = req.headers.get("user-agent");
            ServerStats.get().recordRequest(ip, userAgent, req.path, req.body.length, (int) out.count());
        } catch (IOException e) {
            Log.e(TAG, "Client handling error", e);
        } finally {
            logAccess(req, out, started);
            CURRENT_REQUEST.remove();
            clientSlots.release();
        }
    }

    private void logAccess(Request req, CountingOutputStream out, long started) {
        if (req == null) return;
        try {
            if (!SecuritySettingsStore.get(appContext).get().accessLogEnabled) return;
            AccessLogEntry entry = new AccessLogEntry();
            entry.time = started;
            entry.ip = req.remoteIp == null ? "" : req.remoteIp;
            entry.method = req.method == null ? "" : req.method;
            entry.path = req.path == null ? "" : req.path;
            entry.status = req.responseStatus;
            entry.actor = req.actor;
            entry.tokenId = req.tokenId;
            entry.userAgent = req.headers.get("user-agent");
            entry.bytesIn = req.contentLength > 0 ? req.contentLength : req.body.length;
            entry.bytesOut = out == null ? 0 : out.count();
            entry.durationMs = System.currentTimeMillis() - started;
            entry.blocked = req.blocked;
            AccessLogStore.get(appContext).append(entry);
        } catch (Exception e) {
            Log.w(TAG, "could not write access log", e);
        }
    }

    private boolean isAuthorized(Request req) {
        String stored = ContentStore.getPassword(appContext);
        if (stored == null || stored.isEmpty()) return true;

        String supplied = req.headers.get("x-auth-password");
        if (supplied == null) supplied = req.query.get("pw");
        if (TokenUtil.constantTimeEquals(stored, supplied)) {
            noteActor("password", "");
            return true;
        }

        String basicPassword = extractBasicAuthPassword(req);
        if (TokenUtil.constantTimeEquals(stored, basicPassword)) {
            noteActor("password", "");
            return true;
        }
        IntrusionDetector.recordAuthFailure(appContext, req.remoteIp, req.method, req.path,
                "wrong access password");
        return false;
    }

    /** Pulls the password out of a "Authorization: Basic base64(user:pass)" header, if present. */
    private String extractBasicAuthPassword(Request req) {
        String auth = req.headers.get("authorization");
        if (auth == null || !auth.startsWith("Basic ")) return null;
        try {
            String encoded = auth.substring("Basic ".length()).trim();
            byte[] decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            String userPass = new String(decoded, StandardCharsets.UTF_8);
            int colon = userPass.indexOf(':');
            if (colon < 0) return userPass; // no username given, whole thing is the password
            return userPass.substring(colon + 1);
        } catch (Exception e) {
            return null;
        }
    }

    void route(Request req, OutputStream out) throws IOException {
        String path = req.path;

        if (IntrusionDetector.isSuspiciousPath(path)) {
            IntrusionDetector.recordSuspicious(appContext, req.remoteIp, req.method, path,
                    "suspicious request path");
            writeResponse(out, 403, "application/json",
                    "{\"error\":\"request rejected\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        // A buffered body that exceeded the cap is refused for every route, not
        // just the versioned API, so a large Content-Length can never be used to
        // make the device allocate unbounded memory before authentication.
        if (req.bodyTooLarge) {
            writeResponse(out, 413, "application/json",
                    "{\"error\":\"request body too large\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        // The versioned data/storage API has its own authentication, rate
        // limiting and CORS handling, so it is routed before anything else.
        if (DataApi.isApiPath(path)) {
            DataApi.handle(appContext, req, out);
            return;
        }

        if (path.equals("/") || path.equals("/index.html")) {
            serveWebsite(out);
            return;
        }
        if (path.equals("/status")) {
            String json = "{\"server\":\"android-phone\",\"status\":\"online\",\"time\":\""
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\"}";
            writeResponse(out, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
            return;
        }

        boolean fileApi = path.equals("/api/browse") || path.startsWith("/files/") || path.equals("/files")
                || path.equals("/api/mkdir") || path.equals("/api/delete")
                || path.equals("/api/rename") || path.equals("/api/upload");

        // "Allow remote access" is the documented master switch for the file
        // management surface. It was previously saved but never checked.
        if (fileApi && !ContentStore.getAllowRemoteAccess(appContext)) {
            writeResponse(out, 403, "application/json",
                    "{\"error\":\"remote file access is disabled on this device\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        // everything below this line requires the access password (if one is set)
        if (fileApi || path.equals("/api/stats")) {
            if (!isAuthorized(req)) {
                writeUnauthorized(out);
                return;
            }
        }

        if (path.equals("/api/browse")) {
            serveBrowse(out, req.query.get("path"));
        } else if (path.equals("/api/stats")) {
            serveStats(out);
        } else if (path.equals("/api/mkdir") && req.method.equals("POST")) {
            handleMkdir(out, req.body);
        } else if (path.equals("/api/delete") && req.method.equals("POST")) {
            handleDelete(out, req.body);
        } else if (path.equals("/api/rename") && req.method.equals("POST")) {
            handleRename(out, req.body);
        } else if (path.equals("/api/upload") && req.method.equals("POST")) {
            handleUpload(out, req);
        } else if (path.equals("/files")) {
            serveFileListingHtml(out);
        } else if (path.startsWith("/files/")) {
            serveFile(out, path.substring("/files/".length()));
        } else if (path.startsWith("/api/")) {
            serveCustomApi(out, req.path, req.method);
        } else if (!serveWebsiteAsset(out, path)) {
            writeResponse(out, 404, "text/plain", "404 Not Found".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- handlers ----

    private void serveWebsite(OutputStream out) throws IOException {
        File site = ContentStore.getWebsiteFile(appContext);
        byte[] bytes = site.exists() ? readFile(site) : ContentStore.DEFAULT_HTML.getBytes(StandardCharsets.UTF_8);
        writeResponse(out, 200, "text/html; charset=utf-8", bytes);
    }

    /**
     * Serves any other file the user has uploaded into the website folder -
     * style.css, script.js, images, etc. - so relative links/references in
     * their index.html resolve correctly. Public, same as "/" itself.
     * Returns false (caller should 404) if no matching file exists.
     */
    private boolean serveWebsiteAsset(OutputStream out, String path) throws IOException {
        String name;
        try {
            name = URLDecoder.decode(path, "UTF-8");
        } catch (Exception e) {
            name = path;
        }
        File file = ContentStore.resolveSafeWebsitePath(appContext, name);
        if (file == null || !file.exists() || !file.isFile()) {
            return false;
        }
        byte[] bytes = readFile(file);
        writeResponse(out, 200, guessMime(file.getName()), bytes);
        return true;
    }

    private void serveStats(OutputStream out) throws IOException {
        try {
            ServerStats stats = ServerStats.get();
            JSONObject result = new JSONObject();
            result.put("requestCount", stats.getRequestCount());
            result.put("bytesUp", stats.getBytesUp());
            result.put("bytesDown", stats.getBytesDown());
            result.put("activeClients", stats.getActiveClientCount());
            result.put("ramUsedPercent", DeviceStats.getRamUsedPercent(appContext));
            result.put("appCpuPercent", DeviceStats.getAppCpuPercent());
            result.put("uptimeMillis", ServerService.getUptimeMillis());

            JSONArray clientsJson = new JSONArray();
            for (ServerStats.ClientInfo c : stats.getClients()) {
                JSONObject clientJson = new JSONObject();
                clientJson.put("ip", c.ip);
                clientJson.put("os", c.os);
                clientJson.put("browser", c.browser);
                clientJson.put("lastSeen", c.lastSeenMillis);
                clientsJson.put(clientJson);
            }
            result.put("clients", clientsJson);

            JSONArray endpointsJson = new JSONArray();
            for (java.util.Map.Entry<String, Integer> entry : stats.getTopEndpoints(6)) {
                JSONObject e = new JSONObject();
                e.put("path", entry.getKey());
                e.put("count", entry.getValue());
                endpointsJson.put(e);
            }
            result.put("topEndpoints", endpointsJson);

            JSONArray activityJson = new JSONArray();
            for (ServerStats.ActivityEvent event : stats.getRecentActivity()) {
                JSONObject e = new JSONObject();
                e.put("type", event.type);
                e.put("message", event.message);
                e.put("time", event.timeMillis);
                activityJson.put(e);
            }
            result.put("recentActivity", activityJson);

            writeResponse(out, 200, "application/json", result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeResponse(out, 500, "application/json", "{\"error\":\"server error\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBrowse(OutputStream out, String relativePath) throws IOException {
        File dir = ContentStore.resolveSafePath(appContext, relativePath);
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            writeResponse(out, 404, "application/json", "{\"error\":\"folder not found\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            JSONObject result = new JSONObject();
            result.put("path", relativePath == null ? "" : relativePath);
            JSONArray entries = new JSONArray();
            File[] children = dir.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (!ContentStore.getShowHiddenFiles(appContext) && ContentStore.isHiddenName(f.getName())) continue;
                    JSONObject entry = new JSONObject();
                    entry.put("name", f.getName());
                    entry.put("isDir", f.isDirectory());
                    entry.put("size", f.isDirectory() ? 0 : f.length());
                    entry.put("modified", f.lastModified());
                    entries.put(entry);
                }
            }
            result.put("entries", entries);
            writeResponse(out, 200, "application/json", result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeResponse(out, 500, "application/json", "{\"error\":\"server error\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleMkdir(OutputStream out, byte[] body) throws IOException {
        try {
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String path = json.optString("path", "");
            String name = json.optString("name", "");
            if (name.isEmpty() || name.contains("/") || name.contains("..")) {
                writeResponse(out, 400, "application/json", "{\"error\":\"invalid folder name\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            File parent = ContentStore.resolveSafePath(appContext, path);
            if (parent == null) {
                writeResponse(out, 400, "application/json", "{\"error\":\"invalid path\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            File newDir = new File(parent, name);
            boolean ok = newDir.exists() || newDir.mkdirs();
            writeResponse(out, ok ? 200 : 500, "application/json",
                    (ok ? "{\"status\":\"ok\"}" : "{\"error\":\"could not create folder\"}").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeResponse(out, 400, "application/json", "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleDelete(OutputStream out, byte[] body) throws IOException {
        try {
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String path = json.optString("path", "");
            File target = ContentStore.resolveSafePath(appContext, path);
            if (target == null || !target.exists()) {
                writeResponse(out, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            boolean ok = deleteRecursive(target);
            writeResponse(out, ok ? 200 : 500, "application/json",
                    (ok ? "{\"status\":\"ok\"}" : "{\"error\":\"delete failed\"}").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeResponse(out, 400, "application/json", "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private void handleRename(OutputStream out, byte[] body) throws IOException {
        try {
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String path = json.optString("path", "");
            String newName = json.optString("newName", "");
            if (newName.isEmpty() || newName.contains("/") || newName.contains("..")) {
                writeResponse(out, 400, "application/json", "{\"error\":\"invalid name\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            File target = ContentStore.resolveSafePath(appContext, path);
            if (target == null || !target.exists()) {
                writeResponse(out, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            File renamed = new File(target.getParentFile(), newName);
            boolean ok = target.renameTo(renamed);
            writeResponse(out, ok ? 200 : 500, "application/json",
                    (ok ? "{\"status\":\"ok\"}" : "{\"error\":\"rename failed\"}").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeResponse(out, 400, "application/json", "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleUpload(OutputStream out, Request req) throws IOException {
        String contentType = req.headers.get("content-type");
        if (contentType == null || !contentType.contains("multipart/form-data") || !contentType.contains("boundary=")) {
            writeResponse(out, 400, "application/json", "{\"error\":\"expected multipart/form-data\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }

        MultipartParser.ParsedFile parsed = MultipartParser.parseSingleFile(req.body, boundary);
        if (parsed == null) {
            writeResponse(out, 400, "application/json", "{\"error\":\"no file found in upload\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String targetPath = req.query.get("path");
        File dir = ContentStore.resolveSafePath(appContext, targetPath);
        if (dir == null) {
            writeResponse(out, 400, "application/json", "{\"error\":\"invalid path\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!dir.exists()) dir.mkdirs();

        // Never trust the browser-supplied filename: strip directories and
        // reserved characters, then re-check containment before writing.
        String filename = PathGuard.sanitizeFileName(parsed.filename);
        File destFile = new File(dir, filename);
        if (!PathGuard.isInside(dir, destFile)) {
            writeResponse(out, 400, "application/json", "{\"error\":\"invalid file name\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
            fos.write(parsed.bytes);
        }
        ContentStore.scanFile(appContext, destFile);
        ServerStats.get().logUpload(filename, parsed.bytes.length);
        JSONObject response = new JSONObject();
        try {
            response.put("status", "ok");
            response.put("name", filename);
        } catch (Exception ignored) {
        }
        writeResponse(out, 200, "application/json", response.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void serveFileListingHtml(OutputStream out) throws IOException {
        File dir = ContentStore.getPublicFilesDir(appContext);
        StringBuilder html = new StringBuilder();
        html.append("<html><head><title>Files</title></head><body style='font-family:sans-serif;background:#0D0F14;color:#F2F4F8'>");
        html.append("<h2>Shared Files</h2><ul>");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!ContentStore.getShowHiddenFiles(appContext) && ContentStore.isHiddenName(f.getName())) continue;
                String name = f.getName();
                html.append("<li>");
                if (f.isDirectory()) {
                    html.append("[folder] ").append(escapeHtml(name));
                } else {
                    html.append("<a style='color:#2979FF' href='/files/").append(urlEncodePath(name))
                            .append("'>").append(escapeHtml(name)).append("</a>");
                }
                html.append("</li>");
            }
        }
        html.append("</ul></body></html>");
        writeResponse(out, 200, "text/html; charset=utf-8", html.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Escapes text for HTML so a crafted file name cannot inject markup or script. */
    static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Percent-encodes a single path segment for use in a URL (spaces become %20, not +). */
    static String urlEncodePath(String name) {
        try {
            return URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private void serveFile(OutputStream out, String encodedName) throws IOException {
        String name;
        try {
            name = URLDecoder.decode(encodedName, "UTF-8");
        } catch (Exception e) {
            name = encodedName;
        }
        File file = ContentStore.resolveSafePath(appContext, name);
        if (file == null || !file.exists() || !file.isFile()) {
            writeResponse(out, 404, "text/plain", "File not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] bytes = readFile(file);
        writeResponse(out, 200, guessMime(file.getName()), bytes);
    }

    private void serveCustomApi(OutputStream out, String path, String method) throws IOException {
        ApiEndpointConfig config = ApiEndpointStore.find(appContext, path, method);
        if (config != null) {
            writeResponse(out, 200, "application/json",
                    config.body.getBytes(StandardCharsets.UTF_8), config.headers);
            return;
        }
        if (ApiEndpointStore.pathExists(appContext, path)) {
            writeResponse(out, 405, "application/json",
                    "{\"error\":\"this endpoint doesn't accept that method\"}".getBytes(StandardCharsets.UTF_8));
        } else {
            writeResponse(out, 404, "application/json",
                    "{\"error\":\"no endpoint at this path\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- response writing ----

    private void writeUnauthorized(OutputStream out) throws IOException {
        noteStatus(401);
        byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 401 Unauthorized\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "WWW-Authenticate: Basic realm=\"PhoneServer\"\r\n"
                + "Connection: close\r\n"
                + corsHeaderLine()
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /**
     * The configured CORS origin line (may be empty). Credentials are never
     * advertised, so a wildcard origin can never be combined with them.
     */
    private String corsHeaderLine() {
        String origins = ContentStore.getAllowedOrigins(appContext);
        if (origins == null) return "";
        origins = origins.trim();
        if (origins.isEmpty()) return "";
        return "Access-Control-Allow-Origin: " + origins + "\r\n";
    }

    private void writeResponse(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        writeResponse(out, code, contentType, body, null);
    }

    private void writeResponse(OutputStream out, int code, String contentType, byte[] body, List<KeyValue> extraHeaders) throws IOException {
        noteStatus(code);
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found"
                : code == 400 ? "Bad Request" : code == 401 ? "Unauthorized" : code == 405 ? "Method Not Allowed"
                : code == 413 ? "Payload Too Large" : code == 429 ? "Too Many Requests"
                : code == 500 ? "Internal Server Error" : "Error";
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(code).append(" ").append(status).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("Connection: close\r\n");
        String cors = corsHeaderLine();
        if (!cors.isEmpty()) headers.append(cors);
        if (extraHeaders != null) {
            for (KeyValue kv : extraHeaders) {
                if (kv.key == null || kv.key.trim().isEmpty()) continue;
                // Content-Type/Content-Length/Connection are already controlled above - don't let a
                // custom header silently break the response framing.
                String lowerKey = kv.key.trim().toLowerCase(Locale.US);
                if (lowerKey.equals("content-length") || lowerKey.equals("connection")) continue;
                headers.append(kv.key.trim()).append(": ").append(kv.value == null ? "" : kv.value).append("\r\n");
            }
        }
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = 0;
            while (read < buffer.length) {
                int r = fis.read(buffer, read, buffer.length - read);
                if (r == -1) break;
                read += r;
            }
            return buffer;
        }
    }

    private String guessMime(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".3gp")) return "video/3gpp";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        return "application/octet-stream";
    }
}
