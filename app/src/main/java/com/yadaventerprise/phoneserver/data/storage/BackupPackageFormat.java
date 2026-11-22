package com.yadaventerprise.phoneserver.data.storage;

import com.yadaventerprise.phoneserver.data.storage.validation.BackupPackageValidator;
import com.yadaventerprise.phoneserver.data.storage.validation.ValidationResult;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

public final class BackupPackageFormat {
    public static final String MANIFEST_ENTRY = "backup/manifest.json";
    public static final String METADATA_ENTRY = "metadata.json";
    public static final String PAYLOAD_ENTRY = "payload.enc";
    public static final String INTEGRITY_ENTRY = "integrity.json";

    private BackupPackageFormat() {}

    public static boolean isPackage(File file) {
        if (file == null || !file.exists() || !file.isFile()) return false;
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry(MANIFEST_ENTRY) != null && zip.getEntry(PAYLOAD_ENTRY) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static void create(File outFile, JSONObject manifest, JSONObject metadata, File encryptedPayload) throws IOException {
        File tmp = new File(outFile.getAbsolutePath() + ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            writeJsonEntry(zos, MANIFEST_ENTRY, manifest);
            writeJsonEntry(zos, METADATA_ENTRY, metadata);
            JSONObject integrity = new JSONObject();
            integrity.put("payloadSha256", sha256(encryptedPayload));
            integrity.put("payloadBytes", encryptedPayload.length());
            writeJsonEntry(zos, INTEGRITY_ENTRY, integrity);
            ZipEntry payload = new ZipEntry(PAYLOAD_ENTRY);
            payload.setTime(System.currentTimeMillis());
            zos.putNextEntry(payload);
            try (InputStream in = new BufferedInputStream(new FileInputStream(encryptedPayload))) {
                copy(in, zos);
            }
            zos.closeEntry();
        } catch (Exception e) {
            tmp.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("could not create backup package: " + e.getMessage(), e);
        }
        if (!tmp.renameTo(outFile)) {
            if (outFile.exists() && !outFile.delete()) { tmp.delete(); throw new IOException("could not replace existing package"); }
            if (!tmp.renameTo(outFile)) { tmp.delete(); throw new IOException("could not finalise backup package"); }
        }
    }

    public static Map<String, String> readManifest(File file) throws IOException {
        ValidationResult vr = BackupPackageValidator.validatePackage(file);
        if (!vr.isValid()) {
            throw new IOException(vr.getIssues().isEmpty() ? "invalid backup package" : vr.getIssues().get(0).message);
        }
        try (ZipFile zip = new ZipFile(file)) {
            JSONObject manifest = readJson(zip, MANIFEST_ENTRY);
            Map<String, String> map = new LinkedHashMap<>();
            map.put("format", manifest.optString("format"));
            map.put("version", manifest.optString("version"));
            map.put("backupId", manifest.optString("backupId"));
            map.put("projectId", manifest.optString("projectId"));
            map.put("projectName", manifest.optString("projectName"));
            map.put("createdAt", String.valueOf(manifest.optLong("createdAt", 0)));
            map.put("includesFiles", String.valueOf(manifest.optBoolean("includesFiles", true)));
            map.put("includesDatabases", String.valueOf(manifest.optBoolean("includesDatabases", true)));
            map.put("backupType", manifest.optString("backupType", BackupOptions.TYPE_FULL));
            map.put("appVersion", manifest.optString("appVersion", ""));
            map.put("encrypted", "true");
            return map;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not read backup package", e);
        }
    }

    public static void extractPayload(File packageFile, File outEncrypted) throws IOException {
        ValidationResult vr = BackupPackageValidator.validatePackage(packageFile);
        if (!vr.isValid()) throw new IOException(vr.getIssues().isEmpty() ? "invalid backup package" : vr.getIssues().get(0).message);
        try (ZipFile zip = new ZipFile(packageFile)) {
            JSONObject integrity = readJson(zip, INTEGRITY_ENTRY);
            ZipEntry payload = zip.getEntry(PAYLOAD_ENTRY);
            if (payload == null) throw new IOException("missing payload.enc");
            File tmp = new File(outEncrypted.getAbsolutePath() + ".tmp");
            try (InputStream in = new BufferedInputStream(zip.getInputStream(payload));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                copy(in, out);
            }
            String expected = integrity.optString("payloadSha256", "");
            String actual = sha256(tmp);
            if (!expected.isEmpty() && !expected.equalsIgnoreCase(actual)) {
                tmp.delete();
                throw new IOException("backup package payload integrity verification failed");
            }
            if (!tmp.renameTo(outEncrypted)) {
                if (outEncrypted.exists() && !outEncrypted.delete()) { tmp.delete(); throw new IOException("could not write payload"); }
                if (!tmp.renameTo(outEncrypted)) { tmp.delete(); throw new IOException("could not write payload"); }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not extract backup payload", e);
        }
    }

    private static JSONObject readJson(ZipFile zip, String name) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("missing " + name);
        try (InputStream in = zip.getInputStream(e)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            copy(in, bos);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static void writeJsonEntry(ZipOutputStream zos, String name, JSONObject json) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setTime(System.currentTimeMillis());
        zos.putNextEntry(e);
        byte[] bytes = jsonToBytes(json);
        zos.write(bytes);
        zos.closeEntry();
    }

    private static byte[] jsonToBytes(JSONObject json) throws Exception {
        String s = null;
        try { s = json.toString(2); } catch (Exception ignored) {}
        if (s == null) {
            try { s = json.toString(); } catch (Exception ignored) {}
        }
        if (s == null) s = "{}";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
    }

    private static String sha256(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) digest.update(buf, 0, r);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("could not compute checksum", e);
        }
    }
}
