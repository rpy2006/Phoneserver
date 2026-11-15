package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.util.MimeTypes;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import org.json.JSONObject;
import java.util.TimeZone;

public final class WebDavHandler {

    private static final String ROOT = "/dav";
    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
    private static final long MAX_PUT_BYTES = ContentStore.DEFAULT_MAX_UPLOAD_BYTES;

    private WebDavHandler() {
    }

    public static boolean isDavPath(String path) {
        return path != null && (path.equals(ROOT) || path.startsWith(ROOT + "/"));
    }

    public static boolean requiresWrite(String method) {
        if (method == null) return false;
        String value = method.toUpperCase(Locale.US);
        return value.equals("PUT") || value.equals("DELETE") || value.equals("MKCOL")
                || value.equals("MOVE") || value.equals("COPY") || value.equals("PROPPATCH")
                || value.equals("LOCK") || value.equals("UNLOCK");
    }

    public static boolean wantsStreamedBody(String method, String path) {
        return isDavPath(path) && "PUT".equalsIgnoreCase(method);
    }

    public static void handle(Context context, SimpleHttpServer.Request req, OutputStream out) {
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);
        try {
            if (method.equals("OPTIONS")) {
                send(context, out, 200, "text/plain", new byte[0],
                        "DAV: 1, 2", "Allow: OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, MOVE, COPY");
                return;
            }
            if (method.equals("PROPFIND")) {
                propfind(context, req, out);
                return;
            }
            if (method.equals("GET") || method.equals("HEAD")) {
                get(context, req, out, method.equals("HEAD"));
                return;
            }
            if (method.equals("PUT")) {
                put(context, req, out);
                return;
            }
            if (method.equals("DELETE")) {
                delete(context, req, out);
                return;
            }
            if (method.equals("MKCOL")) {
                mkcol(context, req, out);
                return;
            }
            if (method.equals("MOVE") || method.equals("COPY")) {
                moveOrCopy(context, req, out, method.equals("MOVE"));
                return;
            }
            send(context, out, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8),
                    "Allow: OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, MOVE, COPY");
        } catch (Exception e) {
            try {
                send(context, out, 500, "text/plain", "WebDAV error".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
    }

    private static void propfind(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws IOException {
        File target = resolve(context, req.path);
        if (target == null || !target.exists()) {
            send(context, out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        int depth = 0;
        String depthHeader = req.headers.get("depth");
        if (depthHeader != null && depthHeader.trim().equals("1")) depth = 1;
        List<File> entries = new ArrayList<>();
        entries.add(target);
        if (depth == 1 && target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) entries.add(child);
            }
        }
        StringBuilder xml = new StringBuilder(XML_HEADER);
        xml.append("<D:multistatus xmlns:D=\"DAV:\">");
        for (File file : entries) {
            String relative = relative(context, file);
            xml.append("<D:response><D:href>").append(escape(encodeHref(relative)))
                    .append("</D:href><D:propstat><D:prop>");
            xml.append("<D:displayname>").append(escape(file.getName().isEmpty() ? "dav" : file.getName()))
                    .append("</D:displayname>");
            if (file.isDirectory()) {
                xml.append("<D:resourcetype><D:collection/></D:resourcetype>");
                xml.append("<D:getcontentlength>0</D:getcontentlength>");
            } else {
                xml.append("<D:resourcetype/>");
                xml.append("<D:getcontentlength>").append(file.length()).append("</D:getcontentlength>");
                xml.append("<D:getcontenttype>").append(escape(MimeTypes.guess(file.getName())))
                        .append("</D:getcontenttype>");
            }
            xml.append("<D:getlastmodified>").append(rfc1123(file.lastModified()))
                    .append("</D:getlastmodified>");
            xml.append("<D:getetag>").append(etag(file)).append("</D:getetag>");
            xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
        }
        xml.append("</D:multistatus>");
        send(context, out, 207, "application/xml; charset=utf-8",
                xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void get(Context context, SimpleHttpServer.Request req, OutputStream out, boolean head)
            throws IOException {
        File target = resolve(context, req.path);
        if (target == null || !target.exists()) {
            send(context, out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (target.isDirectory()) {
            StringBuilder html = new StringBuilder("<html><body><h1>WebDAV</h1><ul>");
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    String name = child.getName();
                    String path = relative(context, child);
                    html.append("<li><a href=\"").append(escape(encodeHref(path))).append("\">")
                            .append(escape(name)).append(child.isDirectory() ? "/" : "").append("</a></li>");
                }
            }
            html.append("</ul></body></html>");
            send(context, out, 200, "text/html; charset=utf-8",
                    html.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        long length = target.length();
        String type = MimeTypes.guess(target.getName());
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Last-Modified: " + rfc1123(target.lastModified()) + "\r\n"
                + "ETag: " + etag(target) + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Connection: close\r\n\r\n";
        SimpleHttpServer.noteStatus(200);
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        if (!head) {
            try (FileInputStream in = new FileInputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
        }
        out.flush();
    }

    private static void put(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws IOException {
        File target = resolve(context, req.path);
        if (target == null) {
            send(context, out, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (target.isDirectory()) {
            send(context, out, 409, "text/plain", "Target is a collection".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (req.contentLength > MAX_PUT_BYTES) {
            send(context, out, 413, "text/plain", "File is too large".getBytes(StandardCharsets.UTF_8));
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            send(context, out, 409, "text/plain", "Could not create parent folder".getBytes(StandardCharsets.UTF_8));
            return;
        }
        boolean existed = target.exists();
        File temp = new File(target.getParentFile(), "." + target.getName() + ".dav." + System.nanoTime());
        try {
            long written = 0;
            try (OutputStream fileOut = new FileOutputStream(temp)) {
                if (req.bodyStream != null) {
                    written = copy(req.bodyStream, fileOut, MAX_PUT_BYTES);
                } else if (req.body != null && req.body.length > 0) {
                    fileOut.write(req.body);
                    written = req.body.length;
                }
            }
            if (written > MAX_PUT_BYTES) {
                temp.delete();
                send(context, out, 413, "text/plain", "File is too large".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (target.exists() && !target.delete()) {
                temp.delete();
                send(context, out, 500, "text/plain", "Could not replace file".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                send(context, out, 500, "text/plain", "Could not save file".getBytes(StandardCharsets.UTF_8));
                return;
            }
            ContentStore.scanFile(context, target);
            JSONObject hookData = new JSONObject();
            try {
                hookData.put("name", target.getName());
                hookData.put("size", target.length());
                hookData.put("source", "webdav");
            } catch (Exception ignored) {
            }
            WebhookDispatcher.emit(context, WebhookDispatcher.EVENT_FILE_UPLOADED, hookData);
            send(context, out, existed ? 204 : 201, "text/plain", new byte[0]);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("body too large");
            out.write(buffer, 0, read);
        }
        return total;
    }

    private static void delete(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws IOException {
        String relative = relativePath(req.path);
        if (relative.isEmpty()) {
            send(context, out, 403, "text/plain", "Cannot delete the WebDAV root".getBytes(StandardCharsets.UTF_8));
            return;
        }
        File target = resolve(context, req.path);
        if (target == null || !target.exists()) {
            send(context, out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        boolean deleted = deleteRecursive(target);
        send(context, out, deleted ? 204 : 500, "text/plain",
                deleted ? new byte[0] : "Could not delete".getBytes(StandardCharsets.UTF_8));
    }

    private static void mkcol(Context context, SimpleHttpServer.Request req, OutputStream out)
            throws IOException {
        File target = resolve(context, req.path);
        if (target == null) {
            send(context, out, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (target.exists()) {
            send(context, out, 405, "text/plain", "Already exists".getBytes(StandardCharsets.UTF_8));
            return;
        }
        boolean created = target.mkdirs();
        send(context, out, created ? 201 : 409, "text/plain",
                created ? new byte[0] : "Could not create collection".getBytes(StandardCharsets.UTF_8));
    }

    private static void moveOrCopy(Context context, SimpleHttpServer.Request req, OutputStream out,
                                   boolean move) throws IOException {
        File source = resolve(context, req.path);
        String destinationHeader = req.headers.get("destination");
        if (source == null || !source.exists() || destinationHeader == null) {
            send(context, out, 400, "text/plain", "Invalid source or destination".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String destinationPath = destinationPath(destinationHeader);
        File destination = resolve(context, destinationPath);
        if (destination == null) {
            send(context, out, 403, "text/plain", "Invalid destination".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String overwrite = req.headers.get("overwrite");
        boolean overwriteAllowed = overwrite == null || !overwrite.equalsIgnoreCase("F");
        boolean existed = destination.exists();
        if (existed) {
            if (!overwriteAllowed) {
                send(context, out, 412, "text/plain", "Destination exists".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!deleteRecursive(destination)) {
                send(context, out, 500, "text/plain", "Could not replace destination".getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            send(context, out, 409, "text/plain", "Could not create destination folder".getBytes(StandardCharsets.UTF_8));
            return;
        }
        boolean ok;
        if (move) {
            ok = source.renameTo(destination);
            if (!ok) ok = copyRecursive(source, destination) && deleteRecursive(source);
        } else {
            ok = copyRecursive(source, destination);
        }
        send(context, out, ok ? (existed ? 204 : 201) : 500, "text/plain",
                ok ? new byte[0] : "Operation failed".getBytes(StandardCharsets.UTF_8));
    }

    private static File resolve(Context context, String requestPath) {
        String relative = relativePath(requestPath);
        if (relative.isEmpty()) return ContentStore.getPublicFilesDir(context);
        return ContentStore.resolveSafePath(context, relative);
    }

    private static String relativePath(String requestPath) {
        String rest = requestPath == null ? "" : requestPath;
        if (rest.startsWith(ROOT)) rest = rest.substring(ROOT.length());
        while (rest.startsWith("/")) rest = rest.substring(1);
        try {
            rest = URLDecoder.decode(rest, "UTF-8");
        } catch (Exception ignored) {
        }
        return rest;
    }

    private static String destinationPath(String destination) {
        try {
            java.net.URI uri = new java.net.URI(destination);
            String path = uri.getPath();
            if (path == null) return null;
            int index = path.indexOf(ROOT);
            if (index < 0) return null;
            return path.substring(index);
        } catch (Exception e) {
            return null;
        }
    }

    private static String relative(Context context, File file) {
        try {
            String root = ContentStore.getPublicFilesDir(context).getCanonicalPath();
            String path = file.getCanonicalPath();
            String relative = path.equals(root) ? "" : path.substring(root.length() + 1);
            return relative.replace(File.separatorChar, '/');
        } catch (Exception e) {
            return file.getName();
        }
    }

    private static String encodeHref(String relative) {
        StringBuilder out = new StringBuilder(ROOT);
        out.append('/');
        if (relative == null || relative.isEmpty()) return out.toString();
        String[] parts = relative.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('/');
            out.append(percentEncode(parts[i]));
        }
        return out.toString();
    }

    private static String percentEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static String etag(File file) {
        return "\"" + Long.toHexString(file.length()) + "-" + Long.toHexString(file.lastModified()) + "\"";
    }

    private static String rfc1123(long time) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(time));
    }

    private static boolean copyRecursive(File source, File destination) {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) return false;
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!copyRecursive(child, new File(destination, child.getName()))) return false;
                }
            }
            return true;
        }
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean deleteRecursive(File file) {
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

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void send(Context context, OutputStream out, int status, String contentType,
                             byte[] body, String... headers) throws IOException {
        SimpleHttpServer.sendResponse(out, context, status, contentType, body, headers);
    }
}
