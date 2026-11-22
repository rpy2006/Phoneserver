package com.yadaventerprise.phoneserver.data.storage;

import static org.junit.Assert.*;

import com.yadaventerprise.phoneserver.data.storage.validation.BackupPackageValidator;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class BackupPackageFormatTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void packageRoundTripAndValidation() throws Exception {
        File payload = tmp.newFile("payload.enc");
        try (FileOutputStream out = new FileOutputStream(payload)) { out.write("cipher".getBytes(StandardCharsets.UTF_8)); }
        JSONObject manifest = new JSONObject();
        manifest.put("format", BackupPackageValidator.PACKAGE_FORMAT);
        manifest.put("version", BackupPackageValidator.PACKAGE_VERSION);
        manifest.put("backupId", "b1");
        manifest.put("projectId", "p1");
        manifest.put("projectName", "P");
        manifest.put("createdAt", System.currentTimeMillis());
        manifest.put("backupType", "full");
        manifest.put("includesFiles", true);
        manifest.put("includesDatabases", true);
        manifest.put("categories", new org.json.JSONArray().put("databases"));
        JSONObject enc = new JSONObject();
        enc.put("algorithm", "AES/GCM/NoPadding");
        enc.put("kdf", "PBKDF2WithHmacSHA256");
        enc.put("kdfIterations", 210000);
        enc.put("saltBytes", 16);
        enc.put("ivBytes", 12);
        manifest.put("encryption", enc);
        JSONObject metadata = new JSONObject();
        metadata.put("format", "phoneserver-backup-metadata");
        metadata.put("version", 2);
        File pkg = new File(tmp.getRoot(), "b.psbak");
        BackupPackageFormat.create(pkg, manifest, metadata, payload);
        assertTrue(BackupPackageFormat.isPackage(pkg));
        com.yadaventerprise.phoneserver.data.storage.validation.ValidationResult vr = BackupPackageValidator.validatePackage(pkg);
        if (!vr.isValid()) throw new AssertionError(vr.toJson().toString());
        File extracted = new File(tmp.getRoot(), "payload.out");
        BackupPackageFormat.extractPayload(pkg, extracted);
        assertEquals("cipher", new String(java.nio.file.Files.readAllBytes(extracted.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsMissingManifest() throws Exception {
        File bad = tmp.newFile("bad.psbak");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(bad))) {
            zos.putNextEntry(new ZipEntry("payload.enc"));
            zos.write(new byte[]{1});
            zos.closeEntry();
        }
        assertFalse(BackupPackageValidator.validatePackage(bad).isValid());
    }
}
