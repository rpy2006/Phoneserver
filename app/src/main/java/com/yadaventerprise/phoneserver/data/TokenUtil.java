package com.yadaventerprise.phoneserver.data;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * API token generation and hashing.
 *
 * The plaintext token is shown to the user exactly once (at creation time);
 * only its SHA-256 hash is persisted. Tokens carry a recognisable prefix so
 * they are easy to spot in logs and revoke.
 */
public final class TokenUtil {

    public static final String PREFIX = "pst_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenUtil() {
    }

    /** Generates a new random token, e.g. {@code pst_3f9c...}. */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + toHex(bytes);
    }

    /** SHA-256 hash (hex) of a token, used as the stored lookup value. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(token.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison to avoid timing side channels. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
