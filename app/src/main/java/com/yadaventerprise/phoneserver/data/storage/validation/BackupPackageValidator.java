package com.yadaventerprise.phoneserver.data.storage.validation;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

public final class BackupPackageValidator {
    public static final String PACKAGE_FORMAT = "phoneserver-backup-package";
    public static final String PACKAGE_VERSION = "2";

    private BackupPackageValidator() {}

    public static ValidationResult validatePackage(File file) {
        ValidationResult result = new ValidationResult("backup_package");
        if (file == null || !file.exists() || !file.isFile()) {
            result.error("PACKAGE_MISSING", "package", "Backup package file is missing.", "Provide a valid backup package.");
            return result;
        }
        try (ZipFile zip = new ZipFile(file)) {
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null || name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                    result.error("UNSAFE_PATH", name, "Package contains unsafe path.", "Remove unsafe archive entries.");
                    continue;
                }
                if (!names.add(name)) {
                    result.error("DUPLICATE_ENTRY", name, "Duplicate archive entry.", "Rebuild package without duplicate entries.");
                }
                if (e.getSize() > 1024L * 1024L * 1024L) {
                    result.error("OVERSIZED_ENTRY", name, "Archive entry is unexpectedly large.", "Use a smaller valid backup package.");
                }
            }
            requireEntry(zip, names, "backup/manifest.json", result);
            requireEntry(zip, names, "metadata.json", result);
            requireEntry(zip, names, "payload.enc", result);
            requireEntry(zip, names, "integrity.json", result);
            if (!result.isValid()) return result;
            JSONObject manifest = readJson(zip, "backup/manifest.json", result, "MANIFEST_INVALID");
            JSONObject integrity = readJson(zip, "integrity.json", result, "INTEGRITY_INVALID");
            if (manifest == null || integrity == null) return result;
            if (!PACKAGE_FORMAT.equals(manifest.optString("format"))) {
                result.error("UNSUPPORTED_FORMAT", "backup/manifest.json.format", "Unsupported backup package format.", "Use format " + PACKAGE_FORMAT + ".");
            }
            if (!PACKAGE_VERSION.equals(manifest.optString("version"))) {
                result.error("UNSUPPORTED_VERSION", "backup/manifest.json.version", "Unsupported backup package version.", "Use version " + PACKAGE_VERSION + ".");
            }
            if (!manifest.has("encryption")) {
                result.error("ENCRYPTION_METADATA_MISSING", "backup/manifest.json.encryption", "Encryption metadata is missing.", "Include encryption metadata.");
            } else {
                JSONObject enc = manifest.optJSONObject("encryption");
                if (enc == null) result.error("ENCRYPTION_METADATA_INVALID", "backup/manifest.json.encryption", "Encryption metadata must be object.", "Provide valid encryption metadata.");
                else {
                    if (!"AES/GCM/NoPadding".equals(enc.optString("algorithm"))) result.error("ENCRYPTION_ALGO_INVALID", "encryption.algorithm", "Unsupported encryption algorithm.", "Use AES/GCM/NoPadding.");
                    if (enc.optInt("kdfIterations", 0) < 120000) result.error("ENCRYPTION_KDF_INVALID", "encryption.kdfIterations", "KDF iterations too low.", "Use at least 120000 iterations.");
                    if (enc.optString("kdf", "").isEmpty()) result.error("ENCRYPTION_KDF_INVALID", "encryption.kdf", "KDF is required.", "Set kdf to PBKDF2WithHmacSHA256.");
                }
            }
            if (manifest.optString("backupId", "").isEmpty()) result.error("FIELD_REQUIRED", "backup/manifest.json.backupId", "backupId is required.", "Provide backupId.");
            if (manifest.optLong("createdAt", 0) <= 0) result.error("FIELD_REQUIRED", "backup/manifest.json.createdAt", "createdAt is required.", "Provide createdAt epoch millis.");
            if (!manifest.has("categories")) result.error("FIELD_REQUIRED", "backup/manifest.json.categories", "categories are required.", "Provide included data categories.");
            String payloadSha = integrity.optString("payloadSha256", "");
            if (payloadSha.isEmpty()) result.error("INTEGRITY_MISSING", "integrity.json.payloadSha256", "payloadSha256 is required.", "Provide payload hash.");
        } catch (Exception e) {
            result.error("PACKAGE_INVALID", "package", "Backup package is invalid or corrupted.", "Provide a valid backup package file.");
        }
        return result;
    }

    private static void requireEntry(ZipFile zip, Set<String> names, String name, ValidationResult result) {
        if (!names.contains(name) || zip.getEntry(name) == null) {
            result.error("MISSING_ENTRY", name, "Required package file is missing: " + name, "Include required package file.");
        }
    }

    private static JSONObject readJson(ZipFile zip, String name, ValidationResult result, String code) {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return new JSONObject(new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            result.error(code, name, "Invalid JSON in " + name, "Fix JSON structure.");
            return null;
        }
    }
}
