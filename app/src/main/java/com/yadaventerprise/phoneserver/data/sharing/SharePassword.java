package com.yadaventerprise.phoneserver.data.sharing;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class SharePassword {

    public static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SharePassword() {
    }

    public static String hash(String password) {
        if (password == null || password.isEmpty()) return "";
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        String algorithm = algorithm();
        byte[] derived = derive(password, salt, ITERATIONS, algorithm);
        return encode(algorithm, ITERATIONS, salt, derived);
    }

    public static boolean verify(String password, String encoded) {
        if (password == null || encoded == null || encoded.isEmpty()) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4) return false;
        try {
            String encodedAlgorithm = parts[0];
            String algorithm;
            if ("pbkdf2-sha256".equals(encodedAlgorithm)) {
                algorithm = "PBKDF2WithHmacSHA256";
            } else if ("pbkdf2-sha1".equals(encodedAlgorithm)) {
                algorithm = "PBKDF2WithHmacSHA1";
            } else {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 10_000 || iterations > 2_000_000) return false;
            byte[] salt = fromHex(parts[2]);
            byte[] expected = fromHex(parts[3]);
            if (salt.length == 0 || expected.length == 0) return false;
            byte[] actual = derive(password, salt, iterations, algorithm);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isHashed(String value) {
        return value != null && value.startsWith("pbkdf2-") && value.indexOf('$') > 0;
    }

    private static byte[] derive(String password, byte[] salt, int iterations, String algorithm) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("password hashing unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String algorithm() {
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return "PBKDF2WithHmacSHA256";
        } catch (Exception e) {
            return "PBKDF2WithHmacSHA1";
        }
    }

    private static String encode(String algorithm, int iterations, byte[] salt, byte[] hash) {
        String name = algorithm.toLowerCase(Locale.US).contains("sha256")
                ? "pbkdf2-sha256" : "pbkdf2-sha1";
        return name + "$" + iterations + "$" + toHex(salt) + "$" + toHex(hash);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String value) {
        if (value == null || value.isEmpty() || value.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return new byte[0];
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
