package com.yadaventerprise.phoneserver.data.reliability;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class BackupIntegrityVerifier {

    private static final int BUFFER_SIZE = 64 * 1024;

    private BackupIntegrityVerifier() {
    }

    public static Result verify(File file, String expectedSha256) {
        Result result = new Result();
        result.expectedHash = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(java.util.Locale.US);
        if (file == null || !file.isFile()) {
            result.reason = "backup file is missing";
            return result;
        }
        if (result.expectedHash.isEmpty()) {
            result.reason = "backup checksum is missing";
            return result;
        }
        try {
            result.actualHash = sha256(file);
            result.valid = constantTimeEquals(result.actualHash, result.expectedHash);
            if (!result.valid) result.reason = "backup checksum does not match";
        } catch (Exception e) {
            result.reason = "backup checksum could not be computed";
        }
        return result;
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    public static final class Result {
        public boolean valid;
        public String expectedHash = "";
        public String actualHash = "";
        public String reason = "";
    }
}
