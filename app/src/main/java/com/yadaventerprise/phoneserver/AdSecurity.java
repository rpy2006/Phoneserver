package com.yadaventerprise.phoneserver;

import android.util.Base64;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Passwords, sessions, signed ad tokens, privacy-preserving visitor hashes and input validation. */
final class AdSecurity {
    private AdSecurity() {}

    static final class User {
        final long id;
        final String email, name, company, role;
        final long balance;

        User(JSONObject r) {
            id = r.optLong("id");
            email = r.optString("email");
            name = r.optString("name");
            company = r.optString("company");
            role = r.optString("role");
            balance = r.optLong("balance_micros");
        }

        boolean isAdmin() { return "admin".equals(role); }
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITERATIONS = 60_000;
    private static final long SESSION_MS = 12L * 3600_000L;
    private static final String DUMMY_HASH = hashPassword("dummy-password-for-constant-time");

    // ---------------- passwords (PBKDF2-HMAC-SHA256) ----------------

    static String hashPassword(String pw) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] h = pbkdf2(pw.getBytes(StandardCharsets.UTF_8), salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(h);
    }

    static boolean verifyPassword(String pw, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
            byte[] h = pbkdf2(pw.getBytes(StandardCharsets.UTF_8), Base64.decode(p[2], Base64.NO_WRAP), Integer.parseInt(p[1]));
            return MessageDigest.isEqual(h, Base64.decode(p[3], Base64.NO_WRAP));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Burns the same CPU as a real check so unknown emails cannot be told apart by timing. */
    static void fakeVerify(String pw) {
        verifyPassword(pw, DUMMY_HASH);
    }

    static byte[] pbkdf2(byte[] pw, byte[] salt, int iterations) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pw.length == 0 ? new byte[1] : pw, "HmacSHA256"));
            byte[] block = Arrays.copyOf(salt, salt.length + 4);
            block[salt.length + 3] = 1; // block index 1 (32 bytes = exactly one SHA-256 block)
            byte[] u = mac.doFinal(block);
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            return t;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static void checkPasswordPolicy(String pw) {
        if (pw == null || pw.length() < 10 || pw.length() > 128) throw AdHttp.bad("Password must be 10-128 characters");
        boolean letter = false, digit = false;
        for (char ch : pw.toCharArray()) {
            if (Character.isLetter(ch)) letter = true;
            if (Character.isDigit(ch)) digit = true;
        }
        if (!letter || !digit) throw AdHttp.bad("Password must contain letters and digits");
    }

    // ---------------- sessions ----------------

    static String createSession(AdDb db, long userId) {
        String token = randomToken(32);
        long now = System.currentTimeMillis();
        db.run("INSERT INTO sessions(token_hash,user_id,created_at,expires_at) VALUES(?,?,?,?)",
                sha256Hex(token), userId, now, now + SESSION_MS);
        return token;
    }

    /** Returns the signed-in user for a "Authorization: Bearer" header, or null. */
    static User authenticate(AdDb db, AdHttp.Req r) {
        String a = r.header("authorization");
        if (a == null || !a.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String tok = a.substring(7).trim();
        if (tok.length() < 20 || tok.length() > 100) return null;
        JSONObject row = db.one("SELECT u.id,u.email,u.name,u.company,u.role,u.balance_micros FROM sessions s"
                + " JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>? AND u.status='active'",
                sha256Hex(tok), System.currentTimeMillis());
        if (row == null) return null;
        r.token = tok;
        return new User(row);
    }

    // ---------------- signed ad tokens ----------------

    static String signToken(AdDb db, String payload) {
        String p = b64u(payload.getBytes(StandardCharsets.UTF_8));
        byte[] mac = hmac(db.secret(), p.getBytes(StandardCharsets.US_ASCII));
        return p + "." + b64u(Arrays.copyOf(mac, 16));
    }

    /** Returns the payload if the signature is valid, else null. */
    static String verifyToken(AdDb db, String token) {
        if (token == null || token.length() > 400) return null;
        int i = token.indexOf('.');
        if (i < 1) return null;
        try {
            String p = token.substring(0, i);
            byte[] mac = Arrays.copyOf(hmac(db.secret(), p.getBytes(StandardCharsets.US_ASCII)), 16);
            byte[] given = Base64.decode(token.substring(i + 1), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (!MessageDigest.isEqual(mac, given)) return null;
            return new String(Base64.decode(p, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Daily-rotating, one-way visitor id used only for frequency capping and fraud checks.
     * No cookies, no raw IP stored, and it cannot be linked across days.
     */
    static String visitor(AdDb db, String ip, String ua) {
        byte[] daySalt = hmac(db.secret(), ("day:" + AdDb.today()).getBytes(StandardCharsets.UTF_8));
        return hex(hmac(daySalt, (ip + "|" + ua).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }

    // ---------------- crypto utils ----------------

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return b64u(b);
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return hex(b);
    }

    static double randomDouble() { return RNG.nextDouble(); }

    static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return sb.toString();
    }

    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }

    static String b64u(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }

    // ---------------- validation ----------------

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}$");
    private static final Pattern DOMAIN = Pattern.compile("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$");

    static boolean isEmail(String s) {
        return s != null && s.length() <= 254 && EMAIL.matcher(s).matches();
    }

    /** Accepts "https://www.Example.com/page" and returns "example.com", or null if it is not a valid domain. */
    static String normalizeDomain(String s) {
        if (s == null) return null;
        String d = s.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^https?://", "");
        int cut = d.length();
        for (char ch : new char[]{'/', '?', '#', ':'}) {
            int i = d.indexOf(ch);
            if (i >= 0 && i < cut) cut = i;
        }
        d = d.substring(0, cut);
        if (d.startsWith("www.")) d = d.substring(4);
        if (d.equals("localhost") || DOMAIN.matcher(d).matches()) return d;
        return null;
    }

    /** Only http/https destinations; no whitespace, control characters or credentials in the URL. */
    static boolean isHttpUrl(String s) {
        if (s == null || s.length() < 8 || s.length() > 2048) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch <= 0x20 || ch == 0x7f || ch == '\\') return false;
        }
        try {
            URI u = new URI(s);
            String sch = u.getScheme();
            return ("http".equalsIgnoreCase(sch) || "https".equalsIgnoreCase(sch))
                    && u.getHost() != null && u.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Host part of an Origin or Referer header, lowercased and without port; null if absent or invalid. */
    static String hostOf(String url) {
        if (url == null || url.isEmpty() || url.equals("null")) return null;
        try {
            String h = new URI(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean domainMatches(String host, String siteDomain) {
        if (host == null || siteDomain == null) return false;
        return host.equals(siteDomain) || host.endsWith("." + siteDomain);
    }

    /** Trims, drops control characters and collapses runs of whitespace. Output is escaped by the client, never trusted. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append(ch < 0x20 || ch == 0x7f ? ' ' : ch);
        }
        return sb.toString().trim();
    }

    /** Converts a decimal amount in currency units to integer micros, rejecting negatives and absurd values. */
    static long micros(JSONObject b, String key, boolean required) {
        if (!b.has(key) || b.isNull(key)) {
            if (required) throw AdHttp.bad(key + " is required");
            return 0;
        }
        try {
            BigDecimal v = new BigDecimal(String.valueOf(b.opt(key)).trim());
            if (v.signum() < 0) throw AdHttp.bad(key + " cannot be negative");
            if (v.compareTo(new BigDecimal("1000000000")) > 0) throw AdHttp.bad(key + " is too large");
            return v.setScale(6, RoundingMode.HALF_UP).movePointRight(6).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw AdHttp.bad(key + " must be a number");
        }
    }
}
