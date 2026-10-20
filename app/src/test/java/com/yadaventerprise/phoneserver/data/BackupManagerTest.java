package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class BackupManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;
    private String projectId;

    @Before
    public void setUp() throws Exception {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        projectId = ProjectStore.create(context, "Alpha", "", 0, 0).id;
    }

    /** Builds a project that holds one database (with a row) and one stored file. */
    private void seedProject() throws Exception {
        DatabaseManager.create(context, projectId, "main");
        DatabaseManager.execute(context, projectId, "main",
                "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)", null, null, null, null, true);
        DatabaseManager.execute(context, projectId, "main",
                "INSERT INTO t (v) VALUES('kept')", null, null, null, null, true);
        FileStore.upload(context, projectId, "", "keep.txt", "text/plain",
                DataTestSupport.bytes("precious"), 8, "", "");
    }

    @Test
    public void createProducesAValidatableArchiveWithDatabasesAndFiles() throws Exception {
        seedProject();

        Records.BackupRecord record = BackupManager.create(context, projectId, true, "first backup");
        assertTrue(record.valid);
        assertTrue(record.sizeBytes > 0);
        assertEquals("first backup", record.note);
        assertTrue(record.includesFiles);

        List<Records.BackupRecord> all = BackupManager.list(context, projectId);
        assertEquals(1, all.size());

        Map<String, String> manifest = BackupManager.validate(context, projectId, record.id);
        assertEquals(BackupArchive.FORMAT, manifest.get("format"));
        assertEquals(projectId, manifest.get("projectId"));

        List<String> entries = BackupArchive.list(
                BackupManager.archiveFile(context, projectId, record.id));
        assertTrue(entries.contains(BackupArchive.MANIFEST_ENTRY));
        assertTrue(entries.contains("databases/main.db"));
        assertTrue(entries.contains("metadata.json"));
    }

    @Test
    public void restoreRequiresExplicitConfirmation() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");

        DataException error = assertThrows(DataException.class,
                () -> BackupManager.restore(context, projectId, record.id, false));
        assertEquals(400, error.httpStatus());
    }

    @Test
    public void restoreBringsBackDeletedDatabasesAndFiles() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");

        // Wipe the project's data after the backup was taken.
        List<Records.FileRecord> files = FileStore.listAll(context, projectId);
        for (Records.FileRecord file : files) {
            FileStore.delete(context, projectId, file.id);
        }
        DatabaseManager.delete(context, projectId, "main");
        assertEquals(0, FileStore.listAll(context, projectId).size());
        assertEquals(0, DatabaseManager.list(context, projectId).size());

        BackupManager.restore(context, projectId, record.id, true);

        // The stored file content and metadata come back.
        List<Records.FileRecord> restored = FileStore.listAll(context, projectId);
        assertEquals(1, restored.size());
        assertEquals("keep.txt", restored.get(0).originalName);
        File content = FileStore.contentFile(context, restored.get(0));
        assertTrue(content.exists());
        assertEquals("precious", new String(java.nio.file.Files.readAllBytes(content.toPath()),
                StandardCharsets.UTF_8));

        // The database file and its rows come back.
        assertEquals(1, DatabaseManager.list(context, projectId).size());
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main",
                "SELECT v FROM t", null, null, null, null, true);
        assertEquals(1, result.rows.size());
        assertEquals("kept", result.rows.get(0).get(0));
    }

    @Test
    public void importRejectsCorruptArchivesAndRequiresConfirmation() throws Exception {
        seedProject();

        File notABackup = tmp.newFile("random.zip");
        java.nio.file.Files.write(notABackup.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));

        DataException invalid = assertThrows(DataException.class,
                () -> BackupManager.importArchive(context, projectId, notABackup, true));
        assertEquals(422, invalid.httpStatus());

        // A valid archive still needs confirmation.
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        File archive = BackupManager.archiveFile(context, projectId, record.id);
        DataException noConfirm = assertThrows(DataException.class,
                () -> BackupManager.importArchive(context, projectId, archive, false));
        assertEquals(400, noConfirm.httpStatus());
    }

    @Test
    public void deleteRemovesArchiveAndMetadata() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        File archive = BackupManager.archiveFile(context, projectId, record.id);
        assertTrue(archive.exists());

        BackupManager.delete(context, projectId, record.id);
        assertFalse(archive.exists());
        assertEquals(0, BackupManager.list(context, projectId).size());
        assertNotNull(record);
    }

    @Test
    public void backupWithoutFilesExcludesStoredFileContent() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, false, "db only");

        List<String> entries = BackupArchive.list(
                BackupManager.archiveFile(context, projectId, record.id));
        assertTrue(entries.contains("databases/main.db"));
        for (String entry : entries) {
            assertFalse("no file content should be archived", entry.startsWith("files/"));
        }
        assertFalse(record.includesFiles);
    }
}
