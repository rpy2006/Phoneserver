package com.yadaventerprise.phoneserver;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Request/response types and small JSON helpers shared by the Ad Network Manager classes. */
final class AdHttp {
    private AdHttp() {}

    static final class Req {
        String method = "GET";
        String path = "/";
        String ip = "";
        final Map<String, String> q = new HashMap<>();
        final Map<String, String> h = new HashMap<>();
        byte[] body = new byte[0];
        AdSecurity.User user;
        String token;

        String header(String name) { return h.get(name.toLowerCase(Locale.ROOT)); }
        String param(String name) { return q.get(name); }
    }

    static final class Res {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];

        Res(int status) { this.status = status; }

        Res header(String k, String v) {
            headers.put(k, v);
            return this;
        }
    }

    /** Thrown by validation code; the server turns it into a JSON error response. */
    static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static HttpError bad(String m) { return new HttpError(400, m); }
    static HttpError forbidden(String m) { return new HttpError(403, m); }
    static HttpError notFound(String m) { return new HttpError(404, m); }

    // ---- JSON ----

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static Res json(int status, Object o) {
        return bytes(status, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Res ok(Object o) { return json(200, o); }

    static Res empty(int status) { return new Res(status); }

    static Res text(int status, String contentType, String s) {
        return bytes(status, contentType, s.getBytes(StandardCharsets.UTF_8));
    }

    static Res bytes(int status, String contentType, byte[] b) {
        Res r = new Res(status);
        r.headers.put("Content-Type", contentType);
        r.body = b;
        return r;
    }

    static Res redirect(String url) {
        return new Res(302).header("Location", url).header("Cache-Control", "no-store");
    }

    // ---- request body validation ----

    static JSONObject body(Req r) {
        if (r.body.length == 0) throw bad("Missing JSON body");
        try {
            return new JSONObject(new String(r.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw bad("Invalid JSON");
        }
    }

    static String str(JSONObject b, String k, int min, int max) {
        String v = b.isNull(k) ? "" : AdSecurity.clean(b.optString(k, ""));
        if (v.length() < min) throw bad(k + " is required");
        if (v.length() > max) throw bad(k + " is too long (max " + max + " characters)");
        return v;
    }

    static long num(JSONObject b, String k, long min, long max, long def) {
        if (!b.has(k) || b.isNull(k)) return def;
        long v;
        try {
            v = b.getLong(k);
        } catch (JSONException e) {
            throw bad(k + " must be a number");
        }
        if (v < min || v > max) throw bad(k + " must be between " + min + " and " + max);
        return v;
    }
}
