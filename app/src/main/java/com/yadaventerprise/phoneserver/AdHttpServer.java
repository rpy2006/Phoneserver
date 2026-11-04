package com.yadaventerprise.phoneserver;

import android.content.Context;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Small HTTP/1.1 server for the Ad Network Manager. It runs on its own port with its own thread pool,
 * so it can never interfere with the existing PhoneServer HTTP server. One request per connection.
 */
final class AdHttpServer {
    private static final int MAX_HEADER_LINE = 8192;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_BODY = 128 * 1024;
    private static final int MAX_UPLOAD = 3 * 1024 * 1024;
    private static final Pattern UPLOAD_PATH = Pattern.compile("^/api/creatives/[0-9]+/image$");

    private static final String DASHBOARD_CSP = "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self';"
            + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private final Context ctx;
    private final AdDb db;
    private final int port;
    private final AdApi api;
    private final AdDelivery delivery;
    private ServerSocket server;
    private ThreadPoolExecutor pool;
    private volatile boolean running;

    AdHttpServer(Context ctx, AdDb db, int port) {
        this.ctx = ctx;
        this.db = db;
        this.port = port;
        this.api = new AdApi(ctx, db);
        this.delivery = new AdDelivery(ctx, db);
    }

    synchronized void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        server = s;
        pool = new ThreadPoolExecutor(4, 24, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(128));
        running = true;
        Thread t = new Thread(this::acceptLoop, "AdNet-Accept");
        t.start();
    }

    synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        if (pool != null) pool.shutdownNow();
    }

    boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        ServerSocket s = server;
        while (running && s != null) {
            try {
                final Socket c = s.accept();
                try {
                    pool.execute(() -> handle(c));
                } catch (RejectedExecutionException busy) {
                    try {
                        c.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException ignored) {
                    }
                    try {
                        c.close();
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    // ------------------------------------------------------------------ connection handling

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15_000);
            AdHttp.Req req = readRequest(new BufferedInputStream(s.getInputStream(), 8192), s);
            if (req == null) return;
            AdHttp.Res res;
            try {
                res = route(req);
            } catch (AdHttp.HttpError e) {
                res = AdHttp.json(e.status, AdHttp.obj("error", e.getMessage()));
            } catch (Throwable t) {
                android.util.Log.e("AdNet", "request failed", t);
                res = AdHttp.json(500, AdHttp.obj("error", "Internal error"));
            }
            write(s.getOutputStream(), res, req);
        } catch (AdHttp.HttpError e) {
            // malformed request: nothing sensible to answer
        } catch (IOException ignored) {
            // disconnects and timeouts are normal on a public port
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() >= MAX_HEADER_LINE) throw new IOException("header too long");
            sb.append((char) ch);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private AdHttp.Req readRequest(InputStream in, Socket s) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            writeQuick(s, 400);
            return null;
        }
        AdHttp.Req r = new AdHttp.Req();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int qi = target.indexOf('?');
        r.path = qi < 0 ? target : target.substring(0, qi);
        if (!r.path.startsWith("/") || r.path.contains("..") || r.path.contains("%") || r.path.contains("\\")) {
            writeQuick(s, 400);
            return null;
        }
        if (qi >= 0) parseQuery(target.substring(qi + 1), r.q);

        for (int i = 0; i <= MAX_HEADERS; i++) {
            String h = readLine(in);
            if (h == null) return null;
            if (h.isEmpty()) break;
            if (i == MAX_HEADERS) return null;
            int c = h.indexOf(':');
            if (c <= 0) continue;
            r.h.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
        }

        r.ip = s.getInetAddress().getHostAddress();
        if ("1".equals(db.setting("trust_proxy", "0"))) {
            String xff = r.h.get("x-forwarded-for");
            if (xff != null && !xff.isEmpty()) {
                String[] chain = xff.split(",");
                String last = chain[chain.length - 1].trim(); // the entry appended by our own proxy
                if (last.matches("^[0-9a-fA-F:.]{3,45}$")) r.ip = last;
            }
        }

        if (!AdRateLimiter.allow("global:" + r.ip, 900, 60_000)) {
            writeQuick(s, 429);
            return null;
        }

        if (r.h.containsKey("transfer-encoding")) {
            writeQuick(s, 411);
            return null;
        }
        String cl = r.h.get("content-length");
        if (cl != null) {
            int len;
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            int max = UPLOAD_PATH.matcher(r.path).matches() ? MAX_UPLOAD : MAX_BODY;
            if (len < 0) return null;
            if (len > max) {
                writeQuick(s, 413);
                return null;
            }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) return null;
                off += n;
            }
            r.body = body;
        }
        return r;
    }

    private static void writeQuick(Socket s, int status) {
        try {
            s.getOutputStream().write(("HTTP/1.1 " + status + " Error\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException ignored) {
        }
    }

    private static void parseQuery(String qs, Map<String, String> out) {
        if (qs.length() > 4096) return;
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int e = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(e < 0 ? pair : pair.substring(0, e), "UTF-8");
                String v = e < 0 ? "" : URLDecoder.decode(pair.substring(e + 1), "UTF-8");
                if (out.size() < 30) out.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ routing

    private AdHttp.Res route(AdHttp.Req r) throws IOException {
        String p = r.path;
        boolean publicEndpoint = p.equals("/sdk.js") || p.equals("/serve") || p.equals("/imp") || p.equals("/c") || p.startsWith("/media/");
        if (r.method.equals("OPTIONS")) {
            if (!publicEndpoint) return AdHttp.empty(404);
            return AdHttp.empty(204).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Content-Type").header("Access-Control-Max-Age", "86400");
        }
        if (publicEndpoint) {
            switch (p) {
                case "/sdk.js": return r.method.equals("GET") ? delivery.sdk() : AdHttp.empty(405);
                case "/serve": return r.method.equals("GET") ? delivery.serve(r) : AdHttp.empty(405);
                case "/imp": return delivery.imp(r);
                case "/c": return r.method.equals("GET") ? delivery.click(r) : AdHttp.empty(405);
                default: return r.method.equals("GET") ? delivery.media(r) : AdHttp.empty(405);
            }
        }
        if (p.equals("/health")) return AdHttp.json(200, AdHttp.obj("status", "ok"));
        if (p.startsWith("/api/")) {
            try {
                return api.handle(r);
            } catch (IllegalStateException e) {
                if (e.getCause() instanceof JSONException) throw AdHttp.bad("Invalid JSON");
                throw e;
            }
        }
        if (!r.method.equals("GET")) return AdHttp.empty(405);
        switch (p) {
            case "/":
            case "/index.html": return page("dashboard.html", "text/html; charset=utf-8");
            case "/dashboard.js": return page("dashboard.js", "application/javascript; charset=utf-8");
            case "/dashboard.css": return page("dashboard.css", "text/css; charset=utf-8");
            default: return AdHttp.empty(404);
        }
    }

    private AdHttp.Res page(String name, String type) throws IOException {
        return AdHttp.bytes(200, type, delivery.asset(name)).header("Content-Security-Policy", DASHBOARD_CSP).header("Cache-Control", "no-cache");
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }

    private static void setDefault(AdHttp.Res res, String k, String v) {
        if (!res.headers.containsKey(k)) res.headers.put(k, v);
    }

    private void write(OutputStream out, AdHttp.Res res, AdHttp.Req req) throws IOException {
        boolean noBody = res.status == 204 || req.method.equals("HEAD");
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(res.status).append(' ').append(reason(res.status)).append("\r\n");
        setDefault(res, "X-Content-Type-Options", "nosniff");
        if (req.path.startsWith("/api/")) setDefault(res, "Cache-Control", "no-store");
        if (!req.path.equals("/serve") && !req.path.equals("/imp") && !req.path.equals("/sdk.js") && !req.path.startsWith("/media/") && !req.path.equals("/c")) {
            setDefault(res, "X-Frame-Options", "DENY");
            setDefault(res, "Referrer-Policy", "no-referrer");
        }
        for (Map.Entry<String, String> e : res.headers.entrySet()) {
            String v = e.getValue().replace("\r", "").replace("\n", ""); // header injection guard
            sb.append(e.getKey()).append(": ").append(v).append("\r\n");
        }
        sb.append("Content-Length: ").append(noBody ? 0 : res.body.length).append("\r\nConnection: close\r\n\r\n");
        head.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(head.toByteArray());
        if (!noBody) out.write(res.body);
        out.flush();
    }
}
