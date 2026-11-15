package com.yadaventerprise.phoneserver.data.sharing;

import com.yadaventerprise.phoneserver.security.TokenUtil;
import java.security.SecureRandom;

public final class ShareToken {

    public static final String PREFIX = "pss_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareToken() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + toHex(bytes);
    }

    public static String hash(String token) {
        return token == null || token.isEmpty() ? "" : TokenUtil.hash(token);
    }

    public static boolean matches(String token, String expectedHash) {
        if (token == null || token.isEmpty() || expectedHash == null || expectedHash.isEmpty()) return false;
        return TokenUtil.constantTimeEquals(hash(token), expectedHash);
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
