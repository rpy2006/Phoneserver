package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tests archive creation/validation/extraction. Extraction is deliberately
 * paranoid: it must reject traversal, absolute paths and archive bombs.
 */
public class BackupArchiveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void createThenValidateAndListRoundTrips() throws Exception {
        File payload = tmp.newFile("data.bin");
        try (FileOutputStream out = new FileOutputStream(payload)) {
            out.write(new byte[128]);
        }

        Map<String, String> manifest = new LinkedHashMap<>();
        manifest.put("format", BackupArchive.FORMAT);
        manifest.put("version", BackupArchive.VERSION);
        manifest.put("projectName", "Demo");

        File archive = new File(tmp.getRoot(), "backup.zip");
        BackupArchive.create(archive, manifest,
                Arrays.asList(new BackupArchive.Entry("databases/main.db", payload)));

        assertTrue(archive.exists());
        Map<String, String> readBack = BackupArchive.validate(archive);
        assertEquals(BackupArchive.FORMAT, readBack.get("format"));
        assertEquals("Demo", readBack.get("projectName"));

        List<String> names = BackupArchive.list(archive);
        assertTrue(names.contains(BackupArchive.MANIFEST_ENTRY));
        assertTrue(names.contains("databases/main.db"));
    }

    @Test
    public void create_skipsUnsafeEntryNamesAndMissingSources() throws Exception {
        File payload = tmp.newFile("ok.bin");
        Map<String, String> manifest = new LinkedHashMap<>();
        manifest.put("format", BackupArchive.FORMAT);
        manifest.put("version", BackupArchive.VERSION);

        File archive = new File(tmp.getRoot(), "backup.zip");
        BackupArchive.create(archive, manifest, Arrays.asList(
                new BackupArchive.Entry("../evil.txt", payload),
                new BackupArchive.Entry("files/ok.bin", new File(tmp.getRoot(), "missing"))));

        List<String> names = BackupArchive.list(archive);
        assertFalse(names.contains("../evil.txt"));
        assertFalse(names.contains("files/ok.bin"));
    }

    @Test
    public void validate_rejectsNonBackupArchives() throws Exception {
        File notBackup = tmp.newFile("plain.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(notBackup))) {
            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("hi".getBytes());
            zos.closeEntry();
        }
        assertThrows(IOException.class, () -> BackupArchive.validate(notBackup));
    }

    @Test
    public void validate_rejectsTraversalEntries() throws Exception {
        File archive = tmp.newFile("malicious.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(archive))) {
            zos.putNextEntry(new ZipEntry(BackupArchive.MANIFEST_ENTRY));
            String body = "format=" + BackupArchive.FORMAT + "\nversion=" + BackupArchive.VERSION + "\n";
            zos.write(body.getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("../escape.txt"));
            zos.write("nope".getBytes());
            zos.closeEntry();
        }
        assertThrows(IOException.class, () -> BackupArchive.validate(archive));
    }

    @Test
    public void extract_blocksTraversal() throws Exception {
        File archive = tmp.newFile("traversal.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(archive))) {
            zos.putNextEntry(new ZipEntry("../escape.txt"));
            zos.write("nope".getBytes());
            zos.closeEntry();
        }
        File dest = tmp.newFolder("dest");
        assertThrows(IOException.class, () -> BackupArchive.extract(archive, dest, 1024));
        assertFalse(new File(tmp.getRoot(), "escape.txt").exists());
    }

    @Test
    public void extract_enforcesTotalSizeCap() throws Exception {
        File archive = tmp.newFile("big.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(archive))) {
            zos.putNextEntry(new ZipEntry("files/big.bin"));
            zos.write(new byte[4096]);
            zos.closeEntry();
        }
        File dest = tmp.newFolder("dest2");
        assertThrows(IOException.class, () -> BackupArchive.extract(archive, dest, 100));
    }

    @Test
    public void isSafeEntryName_flagsDangerousNames() {
        assertTrue(BackupArchive.isSafeEntryName("databases/main.db"));
        assertTrue(BackupArchive.isSafeEntryName("files/a/b.txt"));
        assertFalse(BackupArchive.isSafeEntryName("../evil"));
        assertFalse(BackupArchive.isSafeEntryName("/absolute"));
        assertFalse(BackupArchive.isSafeEntryName("a\\b"));
        assertFalse(BackupArchive.isSafeEntryName(""));
        assertFalse(BackupArchive.isSafeEntryName(null));
    }

    @Test
    public void extract_writesFilesUnderDestination() throws Exception {
        File archive = tmp.newFile("good.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(archive))) {
            zos.putNextEntry(new ZipEntry("databases/main.db"));
            zos.write("hello".getBytes());
            zos.closeEntry();
        }
        File dest = tmp.newFolder("dest3");
        BackupArchive.extract(archive, dest, 1024);
        File extracted = new File(dest, "databases/main.db");
        assertTrue(extracted.exists());
        assertEquals(5L, extracted.length());
        assertNotNull(extracted);
    }
}
