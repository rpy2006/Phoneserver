package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.sharing.ShareAccessEvent;
import com.yadaventerprise.phoneserver.data.sharing.ShareAccessLogStore;
import com.yadaventerprise.phoneserver.data.sharing.ShareLink;
import com.yadaventerprise.phoneserver.data.sharing.SharePassword;
import com.yadaventerprise.phoneserver.data.sharing.ShareSessionStore;
import com.yadaventerprise.phoneserver.data.sharing.ShareStore;
import com.yadaventerprise.phoneserver.data.sharing.ShareToken;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.security.PathGuard;
import com.yadaventerprise.phoneserver.security.RateLimiter;
import com.yadaventerprise.phoneserver.util.MimeTypes;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ShareHandler {

    private static final String COOKIE_NAME = "ps_share";
    private static final String TOKEN_PATTERN = "pss_[a-f0-9]{64}";
    private static final int MAX_PASSWORD_BODY = 8192;
    private static final RateLimiter REQUEST_LIMITER = new RateLimiter(240, 60_000L);
    private static final RateLimiter PASSWORD_LIMITER = new RateLimiter(20, 60_000L);
    private static final String CSS =
            "*{box-sizing:border-box}body{margin:0;background:#f4f7f4;color:#1b1f1b;"
            + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}"
            + "main{max-width:760px;margin:0 auto;padding:24px 18px 48px}"
            + ".brand{font-size:13px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;"
            + "color:#2e7d32;margin-bottom:18px}.card{background:#fff;border:1px solid #e6e8e6;"
            + "border-radius:18px;padding:22px;margin-bottom:16px;box-shadow:0 8px 26px rgba(17,40,20,.06)}"
            + "h1{font-size:26px;line-height:1.2;margin:0 0 8px}h2{font-size:17px;margin:0 0 10px}"
            + ".muted{color:#6e756e;font-size:13px;line-height:1.5}.meta{color:#6e756e;font-size:13px;"
            + "line-height:1.7;margin:12px 0 0}.pill{display:inline-block;border-radius:999px;"
            + "background:#e8f5e9;color:#2e7d32;padding:5px 10px;font-size:12px;font-weight:700;margin-right:6px}"
            + ".actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:20px}.button{display:inline-block;"
            + "text-decoration:none;border-radius:12px;padding:12px 16px;font-weight:700;font-size:14px;"
            + "background:#2e7d32;color:#fff;border:1px solid #2e7d32}.button.secondary{background:#fff;"
            + "color:#2e7d32;border-color:#c9d7ca}.button.disabled{background:#eef1ee;color:#8a938a;"
            + "border-color:#e0e5e0}.warn{background:#fff8e1;border:1px solid #f1df9c;color:#7a5a00;"
            + "border-radius:12px;padding:11px 13px;font-size:13px;line-height:1.5;margin-top:16px}"
            + ".err{background:#ffebee;border:1px solid #ffc9cf;color:#a61b2b;border-radius:12px;"
            + "padding:11px 13px;font-size:13px;margin-bottom:14px}input[type=password]{width:100%;"
            + "padding:13px 14px;border:1px solid #cfd8cf;border-radius:12px;font-size:16px;margin:12px 0 4px}"
            + "button{font:inherit;cursor:pointer}ul.files{list-style:none;padding:0;margin:0}"
            + "ul.files li{display:flex;gap:12px;align-items:center;justify-content:space-between;"
            + "padding:13px 0;border-bottom:1px solid #edefed}ul.files li:last-child{border-bottom:0}"
            + ".name{min-width:0;overflow-wrap:anywhere}.name a{color:#1b1f1b;text-decoration:none;"
            + "font-weight:650}.name a:hover{color:#2e7d32}.sub{font-size:12px;color:#6e756e;margin-top:3px}"
            + ".downloads{font-size:12px;color:#2e7d32;text-decoration:none;font-weight:700;white-space:nowrap}"
            + ".crumbs{font-size:13px;color:#6e756e;margin-bottom:16px;overflow-wrap:anywhere}"
            + ".crumbs a{color:#2e7d32;text-decoration:none}.empty{padding:20px 0;color:#6e756e;font-size:14px}"
            + "footer{color:#8a938a;font-size:11px;text-align:center;margin-top:24px}";

    private ShareHandler() {
    }

    public static boolean isSharePath(String path) {
        return path != null && (path.equals("/s") || path.startsWith("/s/"));
    }

    public static void handle(Context context, SimpleHttpServer.Request req, OutputStream out,
                              boolean secure) throws IOException {
        String ip = req.remoteIp == null ? "unknown" : req.remoteIp;
        if (!REQUEST_LIMITER.tryAcquire(ip)) {
            log(context, null, ShareAccessEvent.DENIED_RATE_LIMIT, 429,
                    "too many share requests", ip, "", req.headers.get("user-agent"));
            sendHtml(context, out, 429, "Too many requests",
                    "<div class=\"card\"><h1>Too many requests</h1>"
                            + "<p class=\"muted\">Please wait a moment and try again.</p></div>");
            return;
        }

        Route route = parse(req.path);
        if (route == null || !route.token.matches(TOKEN_PATTERN)) {
            log(context, null, ShareAccessEvent.INVALID_TOKEN, 404,
                    "invalid share link", ip, "", req.headers.get("user-agent"));
            sendNotFound(context, out, secure);
            return;
        }

        ShareStore store = ShareStore.get(context);
        String tokenHash = ShareToken.hash(route.token);
        ShareLink link = store.findByTokenHash(tokenHash);
        if (link == null) {
            log(context, null, ShareAccessEvent.INVALID_TOKEN, 404,
                    "unknown share link", ip, "", req.headers.get("user-agent"));
            sendNotFound(context, out, secure);
            return;
        }

        long now = System.currentTimeMillis();
        if (link.revoked) {
            log(context, link, ShareAccessEvent.DENIED_REVOKED, 410,
                    "revoked link", ip, route.relative, req.headers.get("user-agent"));
            sendState(context, out, 410, "Link revoked",
                    "This sharing link is no longer active.", secure);
            return;
        }
        if (link.isExpired(now)) {
            log(context, link, ShareAccessEvent.DENIED_EXPIRED, 410,
                    "expired link", ip, route.relative, req.headers.get("user-agent"));
            sendState(context, out, 410, "Link expired",
                    "This sharing link has expired.", secure);
            return;
        }

        if (link.isPasswordProtected()
                && !ShareSessionStore.isValid(cookie(req.headers.get("cookie"), COOKIE_NAME), link.id)) {
            if ("POST".equalsIgnoreCase(req.method)) {
                String password = formValue(req.body, "password");
                if (!PASSWORD_LIMITER.tryAcquire(ip + ":" + link.id)) {
                    log(context, link, ShareAccessEvent.DENIED_RATE_LIMIT, 429,
                            "too many password attempts", ip, route.relative,
                            req.headers.get("user-agent"));
                    sendUnlock(context, out, link, route, true, 429, secure);
                    return;
                }
                if (password != null && SharePassword.verify(password, link.passwordHash)) {
                    String session = ShareSessionStore.create(link.id);
                    log(context, link, ShareAccessEvent.UNLOCKED, 200,
                            "password accepted", ip, route.relative,
                            req.headers.get("user-agent"));
                    String cookieHeader = COOKIE_NAME + "=" + session
                            + "; Path=/s/; HttpOnly; SameSite=Lax; Max-Age="
                            + (ShareSessionStore.DEFAULT_TTL_MS / 1000L)
                            + (secure ? "; Secure" : "");
                    String body = "";
                    SimpleHttpServer.sendResponse(out, context, 303, "text/plain",
                            body.getBytes(StandardCharsets.UTF_8),
                            "Location: " + req.path,
                            "Set-Cookie: " + cookieHeader,
                            "Cache-Control: no-store",
                            "Referrer-Policy: no-referrer");
                    return;
                }
                log(context, link, ShareAccessEvent.DENIED_PASSWORD, 401,
                        "incorrect password", ip, route.relative,
                        req.headers.get("user-agent"));
                sendUnlock(context, out, link, route, true, 401, secure);
                return;
            }
            sendUnlock(context, out, link, route, false, 401, secure);
            return;
        }

        if (!"GET".equalsIgnoreCase(req.method)) {
            sendState(context, out, 405, "Method not allowed",
                    "This sharing link can only be opened in a browser.", secure);
            return;
        }

        if (route.action.isEmpty() || "view".equals(route.action)) {
            serveView(context, req, out, store, link, route, tokenHash, ip, now, secure);
        } else if ("download".equals(route.action)) {
            serveDownload(context, req, out, store, link, route, tokenHash, ip, now, secure);
        } else {
            sendNotFound(context, out, secure);
        }
    }

    private static void serveView(Context context, SimpleHttpServer.Request req, OutputStream out,
                                  ShareStore store, ShareLink link, Route route, String tokenHash,
                                  String ip, long now, boolean secure) throws IOException {
        File root = resolveRoot(context, link);
        if (root == null || !root.exists()) {
            log(context, link, ShareAccessEvent.VIEW, 404, "shared path is missing",
                    ip, route.relative, req.headers.get("user-agent"));
            sendNotFound(context, out, secure);
            return;
        }
        if (ShareLink.TYPE_FILE.equals(link.type)) {
            if (!route.relative.isEmpty() || !root.isFile()) {
                sendNotFound(context, out, secure);
                return;
            }
            store.recordAccess(link.id, ip, now);
            if (route.action.isEmpty()) {
                log(context, link, ShareAccessEvent.VISIT, 200, "file landing page",
                        ip, root.getName(), req.headers.get("user-agent"));
                renderFilePage(context, out, link, route.token, root, "", true, secure);
            } else if (MimeTypes.isInlineSafe(MimeTypes.guess(root.getName()))) {
                log(context, link, ShareAccessEvent.VIEW, 200, "file preview",
                        ip, root.getName(), req.headers.get("user-agent"));
                streamFile(out, root, false);
            } else {
                log(context, link, ShareAccessEvent.VIEW, 200, "preview unavailable",
                        ip, root.getName(), req.headers.get("user-agent"));
                renderFilePage(context, out, link, route.token, root, "", true, secure);
            }
            return;
        }

        if (!root.isDirectory()) {
            sendNotFound(context, out, secure);
            return;
        }
        File target = PathGuard.safeResolve(root, route.relative);
        if (target == null || !target.exists() || !PathGuard.isInside(root, target)
                || containsHidden(route.relative)) {
            log(context, link, ShareAccessEvent.VIEW, 404, "shared path is missing",
                    ip, route.relative, req.headers.get("user-agent"));
            sendNotFound(context, out, secure);
            return;
        }

        if (target.isDirectory()) {
            store.recordAccess(link.id, ip, now);
            log(context, link, ShareAccessEvent.VIEW, 200, "folder view",
                    ip, route.relative, req.headers.get("user-agent"));
            renderFolder(context, out, link, route.token, target, route.relative, secure);
            return;
        }
        if (!target.isFile()) {
            sendNotFound(context, out, secure);
            return;
        }
        store.recordAccess(link.id, ip, now);
        if (MimeTypes.isInlineSafe(MimeTypes.guess(target.getName()))) {
            log(context, link, ShareAccessEvent.VIEW, 200, "file preview",
                    ip, route.relative, req.headers.get("user-agent"));
            streamFile(out, target, false);
        } else {
            log(context, link, ShareAccessEvent.VIEW, 200, "preview unavailable",
                    ip, route.relative, req.headers.get("user-agent"));
            renderFilePage(context, out, link, route.token, target, route.relative, true, secure);
        }
    }

    private static void serveDownload(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      ShareStore store, ShareLink link, Route route, String tokenHash,
                                      String ip, long now, boolean secure) throws IOException {
        if (!link.canDownload()) {
            log(context, link, ShareAccessEvent.DENIED_PERMISSION, 403,
                    "download not permitted", ip, route.relative,
                    req.headers.get("user-agent"));
            sendState(context, out, 403, "View only",
                    "This link allows viewing only. Downloads are disabled.", secure);
            return;
        }
        if (ShareLink.TYPE_FILE.equals(link.type) && !route.relative.isEmpty()) {
            sendNotFound(context, out, secure);
            return;
        }
        File root = resolveRoot(context, link);
        if (root == null || !root.exists()) {
            sendNotFound(context, out, secure);
            return;
        }
        File target = ShareLink.TYPE_FILE.equals(link.type)
                ? root : PathGuard.safeResolve(root, route.relative);
        if (target == null || !target.exists() || !target.isFile()
                || (ShareLink.TYPE_FOLDER.equals(link.type) && !PathGuard.isInside(root, target))
                || containsHidden(route.relative)) {
            log(context, link, ShareAccessEvent.DOWNLOAD, 404, "file not found",
                    ip, route.relative, req.headers.get("user-agent"));
            sendNotFound(context, out, secure);
            return;
        }

        ShareStore.ConsumeResult consumed = store.consumeDownload(tokenHash, now);
        if (!consumed.ok()) {
            String event = ShareStore.ERROR_LIMIT.equals(consumed.error)
                    ? ShareAccessEvent.DENIED_LIMIT : ShareAccessEvent.DENIED_EXPIRED;
            int status = ShareStore.ERROR_INVALID.equals(consumed.error) ? 404 : 410;
            log(context, link, event, status, consumed.error, ip, route.relative,
                    req.headers.get("user-agent"));
            if (status == 404) {
                sendNotFound(context, out, secure);
            } else {
                sendState(context, out, status, "Download unavailable",
                        ShareStore.ERROR_LIMIT.equals(consumed.error)
                                ? "This sharing link has reached its download limit."
                                : "This sharing link is no longer active.", secure);
            }
            return;
        }
        store.recordAccess(link.id, ip, now);
        log(context, consumed.link, ShareAccessEvent.DOWNLOAD, 200, "download started",
                ip, route.relative.isEmpty() ? target.getName() : route.relative,
                req.headers.get("user-agent"));
        streamFile(out, target, true);
    }

    private static void renderFolder(Context context, OutputStream out, ShareLink link, String token,
                                     File dir, String relative, boolean secure) throws IOException {
        String base = "/s/" + token;
        String rootName = displayName(link, null);
        String title = relative.isEmpty() ? rootName : lastName(relative);
        StringBuilder html = new StringBuilder();
        html.append(pageStart(title, secure));
        html.append("<div class=\"brand\">PhoneServer · Shared folder</div>");
        html.append("<div class=\"card\"><h1>").append(esc(title)).append("</h1>");
        html.append("<div>");
        html.append("<span class=\"pill\">").append(link.canDownload() ? "View &amp; download" : "View only").append("</span>");
        if (link.maxDownloads > 0) {
            html.append("<span class=\"pill\">").append(link.remainingDownloads()).append(" downloads left</span>");
        }
        html.append("</div>");
        html.append("<div class=\"meta\">").append(esc(metaText(link))).append("</div>");
        html.append("</div>");

        html.append("<div class=\"card\">");
        html.append("<div class=\"crumbs\">");
        if (relative.isEmpty()) {
            html.append(esc(rootName));
        } else {
            html.append("<a href=\"").append(base).append("\">").append(esc(rootName)).append("</a>");
            String[] parts = relative.split("/");
            String accumulated = "";
            for (int i = 0; i < parts.length; i++) {
                accumulated = accumulated.isEmpty() ? parts[i] : accumulated + "/" + parts[i];
                html.append(" / ");
                if (i == parts.length - 1) {
                    html.append(esc(parts[i]));
                } else {
                    html.append("<a href=\"").append(base).append("/view/")
                            .append(encodePath(accumulated)).append("\">")
                            .append(esc(parts[i])).append("</a>");
                }
            }
        }
        html.append("</div>");

        File[] children = dir.listFiles();
        List<File> entries = new ArrayList<>();
        if (children != null) entries.addAll(Arrays.asList(children));
        Collections.sort(entries, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        html.append("<ul class=\"files\">");
        int shown = 0;
        for (File child : entries) {
            if (ContentStore.isHiddenName(child.getName())) continue;
            String childRel = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            File safe = PathGuard.safeResolve(dir, child.getName());
            if (safe == null || !safe.exists()) continue;
            boolean directory = safe.isDirectory();
            boolean inline = !directory && MimeTypes.isInlineSafe(MimeTypes.guess(child.getName()));
            boolean canDownload = !directory && link.canDownload() && !link.isDownloadLimitReached();
            shown++;
            html.append("<li><div class=\"name\">");
            if (directory) {
                html.append("<a href=\"").append(base).append("/view/")
                        .append(encodePath(childRel)).append("\">").append(esc(child.getName())).append("/</a>");
                html.append("<div class=\"sub\">Folder</div>");
            } else if (inline) {
                html.append("<a href=\"").append(base).append("/view/")
                        .append(encodePath(childRel)).append("\">").append(esc(child.getName())).append("</a>");
                html.append("<div class=\"sub\">").append(formatBytes(safe.length())).append("</div>");
            } else {
                html.append("<span>").append(esc(child.getName())).append("</span>");
                html.append("<div class=\"sub\">").append(formatBytes(safe.length()))
                        .append(inline ? "" : " · no preview").append("</div>");
            }
            html.append("</div>");
            if (canDownload) {
                html.append("<a class=\"downloads\" href=\"").append(base).append("/download/")
                        .append(encodePath(childRel)).append("\">Download</a>");
            } else if (!directory && link.canDownload() && link.isDownloadLimitReached()) {
                html.append("<span class=\"downloads\">Limit reached</span>");
            }
            html.append("</li>");
        }
        html.append("</ul>");
        if (shown == 0) {
            html.append("<div class=\"empty\">This folder is empty.</div>");
        }
        html.append("</div>");
        html.append("<footer>Shared from PhoneServer</footer>");
        html.append(pageEnd());
        sendHtml(context, out, 200, title, html.toString());
    }

    private static void renderFilePage(Context context, OutputStream out, ShareLink link, String token,
                                       File file, String relative, boolean previewAvailable,
                                       boolean secure) throws IOException {
        String base = "/s/" + token;
        String suffix = relative.isEmpty() ? "" : "/" + encodePath(relative);
        String title = displayName(link, file);
        boolean preview = previewAvailable && MimeTypes.isInlineSafe(MimeTypes.guess(file.getName()));
        boolean download = link.canDownload() && !link.isDownloadLimitReached();
        StringBuilder html = new StringBuilder();
        html.append(pageStart(title, secure));
        html.append("<div class=\"brand\">PhoneServer · Shared file</div>");
        html.append("<div class=\"card\"><h1>").append(esc(title)).append("</h1>");
        html.append("<div>");
        html.append("<span class=\"pill\">").append(link.canDownload() ? "View &amp; download" : "View only").append("</span>");
        if (link.maxDownloads > 0) {
            html.append("<span class=\"pill\">").append(link.remainingDownloads()).append(" downloads left</span>");
        }
        html.append("</div>");
        html.append("<div class=\"meta\">").append(esc(file.getName())).append("<br>")
                .append(formatBytes(file.length())).append("<br>")
                .append(esc(metaText(link))).append("</div>");
        html.append("<div class=\"actions\">");
        if (preview) {
            html.append("<a class=\"button secondary\" href=\"").append(base).append("/view")
                    .append(suffix).append("\">Preview</a>");
        }
        if (link.canDownload()) {
            if (download) {
                html.append("<a class=\"button\" href=\"").append(base).append("/download")
                        .append(suffix).append("\">Download</a>");
            } else {
                html.append("<span class=\"button disabled\">Download limit reached</span>");
            }
        }
        html.append("</div>");
        if (!preview) {
            html.append("<div class=\"warn\">This file type cannot be previewed in the browser.");
            if (!link.canDownload()) html.append(" The owner has disabled downloads for this link.");
            html.append("</div>");
        }
        html.append("</div>");
        html.append("<footer>Shared from PhoneServer</footer>");
        html.append(pageEnd());
        sendHtml(context, out, 200, title, html.toString());
    }

    private static void sendUnlock(Context context, OutputStream out, ShareLink link, Route route,
                                   boolean failed, int status, boolean secure) throws IOException {
        String action = "/s/" + route.token;
        if (!route.action.isEmpty() && !route.relative.isEmpty()) {
            action += "/" + route.action + "/" + encodePath(route.relative);
        } else if (!route.action.isEmpty()) {
            action += "/" + route.action;
        }
        String title = displayName(link, null);
        StringBuilder html = new StringBuilder();
        html.append(pageStart("Password required", secure));
        html.append("<div class=\"brand\">PhoneServer · Protected share</div>");
        html.append("<div class=\"card\"><h1>").append(esc(title)).append("</h1>");
        html.append("<p class=\"muted\">This sharing link is protected. Enter the password to continue.</p>");
        if (failed) {
            html.append("<div class=\"err\">").append(status == 429
                    ? "Too many attempts. Please wait a minute and try again."
                    : "Incorrect password. Please try again.").append("</div>");
        }
        html.append("<form method=\"post\" action=\"").append(action).append("\">");
        html.append("<input type=\"password\" name=\"password\" placeholder=\"Password\" "
                + "autocomplete=\"current-password\" autofocus required>");
        html.append("<div class=\"actions\"><button class=\"button\" type=\"submit\">Unlock</button></div>");
        html.append("</form></div>");
        html.append("<footer>Shared from PhoneServer</footer>");
        html.append(pageEnd());
        sendHtml(context, out, status, "Password required", html.toString());
    }

    private static void sendState(Context context, OutputStream out, int status, String title,
                                  String message, boolean secure) throws IOException {
        String html = pageStart(title, secure)
                + "<div class=\"brand\">PhoneServer · Shared link</div>"
                + "<div class=\"card\"><h1>" + esc(title) + "</h1>"
                + "<p class=\"muted\">" + esc(message) + "</p></div>"
                + "<footer>Shared from PhoneServer</footer>"
                + pageEnd();
        sendHtml(context, out, status, title, html);
    }

    private static void sendNotFound(Context context, OutputStream out, boolean secure) throws IOException {
        sendState(context, out, 404, "Link not found",
                "This sharing link does not exist or is no longer available.", secure);
    }

    private static void sendHtml(Context context, OutputStream out, int status, String title,
                                 String body) throws IOException {
        SimpleHttpServer.sendResponse(out, context, status, "text/html; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8),
                "Cache-Control: no-store",
                "Referrer-Policy: no-referrer",
                "X-Content-Type-Options: nosniff",
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; "
                        + "img-src 'self' data:; media-src 'self'; form-action 'self'; "
                        + "base-uri 'none'; frame-ancestors 'none'");
    }

    private static void streamFile(OutputStream out, File file, boolean attachment) throws IOException {
        String mime = MimeTypes.guess(file.getName());
        String dispositionType = attachment ? "attachment" : "inline";
        String safeName = PathGuard.sanitizeFileName(file.getName());
        String asciiName = safeName.replace("\"", "_").replace("\r", "_").replace("\n", "_");
        String encodedName = URLEncoder.encode(safeName, "UTF-8").replace("+", "%20");
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n");
        headers.append("Content-Type: ").append(mime).append("\r\n");
        headers.append("Content-Length: ").append(file.length()).append("\r\n");
        headers.append("Content-Disposition: ").append(dispositionType)
                .append("; filename=\"").append(asciiName)
                .append("\"; filename*=UTF-8''").append(encodedName).append("\r\n");
        headers.append("Cache-Control: no-store\r\n");
        headers.append("X-Content-Type-Options: nosniff\r\n");
        headers.append("Referrer-Policy: no-referrer\r\n");
        headers.append("Connection: close\r\n");
        headers.append("\r\n");
        SimpleHttpServer.noteStatus(200);
        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        out.flush();
    }

    private static File resolveRoot(Context context, ShareLink link) {
        String normalized = PathGuard.normalizeRelative(link.rootPath);
        if (normalized == null || normalized.isEmpty() || containsHidden(normalized)) return null;
        return PathGuard.safeResolve(ContentStore.getPublicFilesDir(context), normalized);
    }

    private static String pageStart(String title, boolean secure) {
        String notice = secure ? ""
                : "<div class=\"warn\">This link uses HTTP and is not encrypted. "
                + "Do not enter sensitive information unless you trust this network.</div>";
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + " · PhoneServer</title><style>" + CSS
                + "</style></head><body><main>" + notice;
    }

    private static String pageEnd() {
        return "</main></body></html>";
    }

    private static String metaText(ShareLink link) {
        String expiry = link.expiresAt <= 0
                ? "No expiry"
                : "Expires " + formatDate(link.expiresAt);
        String downloads = link.maxDownloads <= 0
                ? "Unlimited downloads"
                : link.downloadCount + " of " + link.maxDownloads + " downloads used";
        return expiry + " · " + downloads;
    }

    private static String displayName(ShareLink link, File fallback) {
        if (link != null && link.name != null && !link.name.trim().isEmpty()) return link.name.trim();
        if (fallback != null) return fallback.getName();
        if (link != null && link.rootPath != null && !link.rootPath.isEmpty()) return lastName(link.rootPath);
        return "Shared files";
    }

    private static String lastName(String path) {
        if (path == null || path.isEmpty()) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static boolean containsHidden(String relative) {
        if (relative == null || relative.isEmpty()) return false;
        for (String segment : relative.split("/")) {
            if (ContentStore.isHiddenName(segment)) return true;
        }
        return false;
    }

    private static String encodePath(String relative) {
        StringBuilder out = new StringBuilder();
        if (relative == null) return "";
        for (String segment : relative.split("/")) {
            if (segment.isEmpty()) continue;
            if (out.length() > 0) out.append('/');
            out.append(SimpleHttpServer.urlEncodePath(segment));
        }
        return out.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1f MB", value);
        return String.format(Locale.US, "%.1f GB", value / 1024.0);
    }

    private static String formatDate(long millis) {
        return new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(new Date(millis));
    }

    private static String esc(String value) {
        return SimpleHttpServer.escapeHtml(value);
    }

    private static Route parse(String path) {
        if (path == null || !path.startsWith("/s/")) return null;
        String rest = path.substring(3);
        if (rest.isEmpty()) return null;
        String[] parts = rest.split("/", -1);
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        Route route = new Route();
        route.token = decode(parts[0]);
        if (route.token == null) return null;
        if (parts.length == 1) return route;
        route.action = parts[1] == null ? "" : parts[1];
        if (!route.action.isEmpty() && !"view".equals(route.action) && !"download".equals(route.action)) {
            return null;
        }
        if (parts.length > 2) {
            StringBuilder relative = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                String segment = decode(parts[i]);
                if (segment == null || segment.isEmpty()) return null;
                if (segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) return null;
                if (!PathGuard.isValidSegment(segment)) return null;
                if (relative.length() > 0) relative.append('/');
                relative.append(segment);
            }
            route.relative = PathGuard.normalizeRelative(relative.toString());
            if (route.relative == null) return null;
        }
        return route;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static String cookie(String header, String name) {
        if (header == null || name == null) return null;
        for (String part : header.split(";")) {
            String value = part.trim();
            int eq = value.indexOf('=');
            if (eq <= 0) continue;
            if (value.substring(0, eq).trim().equals(name)) {
                return value.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static String formValue(byte[] body, String name) {
        if (body == null || body.length == 0 || body.length > MAX_PASSWORD_BODY) return null;
        String form = new String(body, StandardCharsets.UTF_8);
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                String key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                if (!name.equals(key)) continue;
                String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                return value.length() > 512 ? null : value;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static void log(Context context, ShareLink link, String event, int status, String detail,
                            String ip, String path, String userAgent) {
        ShareAccessLogStore.get(context).record(event, link, status, detail, ip, path, userAgent);
    }

    private static final class Route {
        String token = "";
        String action = "";
        String relative = "";
    }
}
