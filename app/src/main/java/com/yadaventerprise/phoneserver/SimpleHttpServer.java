package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private final int port;
    private final Context appContext;
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
            try {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> handleClient(client), "PhoneServer-Client");
                t.start();
            } catch (IOException e) {
                if (running) Log.e(TAG, "Accept failed", e);
            }
        }
    }

    // ---- request reading (byte-safe, so binary uploads survive intact) ----

    private static class Request {
        String method;
        String path;
        Map<String, String> query = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
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
        while ((line = readLine(in)) != null && !line.isEmpty()) {
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
        if (contentLength > 0) {
            req.body = readExactly(in, contentLength);
        }

        return req;
    }

    private byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(buffer, read, length - read);
            if (r == -1) break;
            read += r;
        }
        if (read < length) {
            byte[] trimmed = new byte[read];
            System.arraycopy(buffer, 0, trimmed, 0, read);
            return trimmed;
        }
        return buffer;
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
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            Request req = readRequest(in);
            if (req == null) return;

            CountingOutputStream out = new CountingOutputStream(s.getOutputStream());
            route(req, out);

            String ip = s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : "unknown";
            String userAgent = req.headers.get("user-agent");
            ServerStats.get().recordRequest(ip, userAgent, req.path, req.body.length, (int) out.count);
        } catch (IOException e) {
            Log.e(TAG, "Client handling error", e);
        }
    }

    private boolean isAuthorized(Request req) {
        String stored = ContentStore.getPassword(appContext);
        if (stored == null || stored.isEmpty()) return true;

        String supplied = req.headers.get("x-auth-password");
        if (supplied == null) supplied = req.query.get("pw");
        if (stored.equals(supplied)) return true;

        String basicPassword = extractBasicAuthPassword(req);
        return stored.equals(basicPassword);
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

    private void route(Request req, OutputStream out) throws IOException {
        String path = req.path;

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

        // everything below this line requires the access password (if one is set)
        if (path.equals("/api/browse") || path.startsWith("/files/") || path.equals("/files")
                || path.equals("/api/mkdir") || path.equals("/api/delete")
                || path.equals("/api/rename") || path.equals("/api/upload")
                || path.equals("/api/stats")) {
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

        File destFile = new File(dir, parsed.filename);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
            fos.write(parsed.bytes);
        }
        ContentStore.scanFile(appContext, destFile);
        ServerStats.get().logUpload(parsed.filename, parsed.bytes.length);
        String responseJson = "{\"status\":\"ok\",\"name\":\"" + parsed.filename + "\"}";
        writeResponse(out, 200, "application/json", responseJson.getBytes(StandardCharsets.UTF_8));
    }

    private void serveFileListingHtml(OutputStream out) throws IOException {
        File dir = ContentStore.getPublicFilesDir(appContext);
        StringBuilder html = new StringBuilder();
        html.append("<html><head><title>Files</title></head><body style='font-family:sans-serif;background:#0D0F14;color:#F2F4F8'>");
        html.append("<h2>Shared Files</h2><ul>");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                html.append("<li>")
                        .append(f.isDirectory() ? "[folder] " : "")
                        .append(f.isDirectory() ? f.getName() : "<a style='color:#2979FF' href='/files/" + f.getName() + "'>" + f.getName() + "</a>")
                        .append("</li>");
            }
        }
        html.append("</ul></body></html>");
        writeResponse(out, 200, "text/html; charset=utf-8", html.toString().getBytes(StandardCharsets.UTF_8));
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
        byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 401 Unauthorized\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "WWW-Authenticate: Basic realm=\"PhoneServer\"\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void writeResponse(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        writeResponse(out, code, contentType, body, null);
    }

    private void writeResponse(OutputStream out, int code, String contentType, byte[] body, List<KeyValue> extraHeaders) throws IOException {
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 400 ? "Bad Request"
                : code == 401 ? "Unauthorized" : code == 405 ? "Method Not Allowed" : "Error";
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(code).append(" ").append(status).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("Connection: close\r\n");
        headers.append("Access-Control-Allow-Origin: *\r\n");
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
