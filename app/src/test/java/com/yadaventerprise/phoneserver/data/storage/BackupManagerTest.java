package com.yadaventerprise.phoneserver.data.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.DatabaseManager;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.network.http.DataException;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

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

    @Test
    public void encryptedBackupRequiresPasswordAndRestores() throws Exception {
        seedProject();
        BackupOptions options = BackupOptions.full(true, "encrypted");
        options.password = "backup-pass".toCharArray();
        Records.BackupRecord record = BackupManager.create(context, projectId, options);
        assertTrue(record.encrypted);
        assertTrue(BackupManager.archiveFile(context, projectId, record.id).getName().endsWith(".enc"));

        BackupPreview previewNoPassword = BackupManager.preview(context, projectId, record.id, null);
        assertFalse(previewNoPassword.valid);
        assertTrue(previewNoPassword.error.toLowerCase().contains("password"));

        DataException noPassword = assertThrows(DataException.class,
                () -> BackupManager.validate(context, projectId, record.id, null));
        assertEquals(400, noPassword.httpStatus());

        Map<String, String> manifest = BackupManager.validate(context, projectId, record.id,
                "backup-pass".toCharArray());
        assertEquals(BackupArchive.FORMAT, manifest.get("format"));

        DatabaseManager.delete(context, projectId, "main");
        RestoreOptions restore = RestoreOptions.confirmed("backup-pass".toCharArray());
        BackupManager.restore(context, projectId, record.id, restore);
        assertEquals(1, DatabaseManager.list(context, projectId).size());
    }

    @Test
    public void selectiveRestoreOnlyRestoresRequestedCategories() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        DatabaseManager.execute(context, projectId, "main", "UPDATE t SET v='changed'", null, null, null, null, true);
        FileStore.upload(context, projectId, "", "keep.txt", "text/plain", DataTestSupport.bytes("changed"), 8, "", "");

        RestoreOptions dbOnly = RestoreOptions.confirmed(null);
        dbOnly.restoreDatabases = true;
        dbOnly.restoreFiles = false;
        dbOnly.restoreProjectConfig = false;
        BackupManager.restore(context, projectId, record.id, dbOnly);

        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main", "SELECT v FROM t", null, null, null, null, true);
        assertEquals("kept", result.rows.get(0).get(0));
        List<Records.FileRecord> files = FileStore.listAll(context, projectId);
        assertEquals(1, files.size());
        String content = new String(java.nio.file.Files.readAllBytes(FileStore.contentFile(context, files.get(0)).toPath()), StandardCharsets.UTF_8);
        assertEquals("changed", content);
    }

    @Test
    public void restorePreviewDoesNotModifyProjectData() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        DatabaseManager.execute(context, projectId, "main", "UPDATE t SET v='changed'", null, null, null, null, true);

        BackupPreview preview = BackupManager.preview(context, projectId, record.id, null);
        assertTrue(preview.valid);
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main", "SELECT v FROM t", null, null, null, null, true);
        assertEquals("changed", result.rows.get(0).get(0));
    }

    @Test
    public void corruptedEncryptedBackupFailsBeforeRestore() throws Exception {
        seedProject();
        BackupOptions options = BackupOptions.full(true, "encrypted");
        options.password = "backup-pass".toCharArray();
        Records.BackupRecord record = BackupManager.create(context, projectId, options);
        File archive = BackupManager.archiveFile(context, projectId, record.id);
        byte[] bytes = java.nio.file.Files.readAllBytes(archive.toPath());
        bytes[bytes.length - 20] ^= 0x1;
        java.nio.file.Files.write(archive.toPath(), bytes);

        DataException error = assertThrows(DataException.class,
                () -> BackupManager.restore(context, projectId, record.id, RestoreOptions.confirmed("backup-pass".toCharArray())));
        assertEquals(422, error.httpStatus());
        assertEquals(1, DatabaseManager.list(context, projectId).size());
    }

    @Test
    public void incorrectPasswordDoesNotRestore() throws Exception {
        seedProject();
        BackupOptions options = BackupOptions.full(true, "encrypted");
        options.password = "backup-pass".toCharArray();
        Records.BackupRecord record = BackupManager.create(context, projectId, options);
        DatabaseManager.execute(context, projectId, "main", "UPDATE t SET v='changed'", null, null, null, null, true);

        DataException error = assertThrows(DataException.class,
                () -> BackupManager.restore(context, projectId, record.id, RestoreOptions.confirmed("wrong".toCharArray())));
        assertTrue(error.httpStatus() == 400 || error.httpStatus() == 422);
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main", "SELECT v FROM t", null, null, null, null, true);
        assertEquals("changed", result.rows.get(0).get(0));
    }

    @Test
    public void validateRejectsChecksumTampering() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        File archive = BackupManager.archiveFile(context, projectId, record.id);
        byte[] bytes = java.nio.file.Files.readAllBytes(archive.toPath());
        bytes[bytes.length - 10] ^= 0x1;
        java.nio.file.Files.write(archive.toPath(), bytes);

        DataException error = assertThrows(DataException.class,
                () -> BackupManager.validate(context, projectId, record.id));
        assertEquals(422, error.httpStatus());
        assertTrue(error.getMessage().toLowerCase().contains("checksum"));
    }

    @Test
    public void failedFileReplacementRollsBackAndPreservesOriginal() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        DatabaseManager.execute(context, projectId, "main", "UPDATE t SET v='changed'", null, null, null, null, true);
        BackupManager.setRestoreFailureHookForTest(phase -> { if ("before_install_files".equals(phase)) throw new IOException("injected file replacement failure"); });
        try {
            DataException err = assertThrows(DataException.class, () -> BackupManager.restore(context, projectId, record.id, RestoreOptions.confirmed(null)));
            assertTrue(err.getMessage().contains("injected file replacement failure"));
        } finally {
            BackupManager.setRestoreFailureHookForTest(null);
        }
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main", "SELECT v FROM t", null, null, null, null, true);
        assertEquals("changed", result.rows.get(0).get(0));
        List<Records.FileRecord> files = FileStore.listAll(context, projectId);
        assertEquals(1, files.size());
        String content = new String(java.nio.file.Files.readAllBytes(FileStore.contentFile(context, files.get(0)).toPath()), StandardCharsets.UTF_8);
        assertEquals("changed", content);
        assertNoTempVisibleArtifacts();
        assertHasOperation("backup.restore.failed", "injected file replacement failure");
        assertHasOperation("backup.restore.rollback", "injected file replacement failure");
    }

    @Test
    public void failedPostRestoreVerificationRollsBackAndKeepsRecoveryPoint() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        DatabaseManager.execute(context, projectId, "main", "UPDATE t SET v='changed'", null, null, null, null, true);
        BackupManager.setRestoreFailureHookForTest(phase -> { if ("before_post_verify".equals(phase)) throw new DataException("injected post-verify failure", 500); });
        try {
            DataException err = assertThrows(DataException.class, () -> BackupManager.restore(context, projectId, record.id, RestoreOptions.confirmed(null)));
            assertTrue(err.getMessage().contains("post-verify"));
        } finally {
            BackupManager.setRestoreFailureHookForTest(null);
        }
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main", "SELECT v FROM t", null, null, null, null, true);
        assertEquals("changed", result.rows.get(0).get(0));
        assertFalse(FileStore.listAll(context, projectId).isEmpty());
        Records.BackupRecord recovery = findRecoveryBackup();
        assertNotNull(recovery);
        assertTrue(recovery.valid);
        BackupPreview preview = BackupManager.preview(context, projectId, recovery.id, null);
        assertTrue(preview.valid);
        assertNoTempVisibleArtifacts();
        assertHasOperation("backup.restore.failed", "post-verify");
    }

    @Test
    public void interruptedRestorationRollsBackAutomatically() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        DatabaseManager.execute(context, projectId, "main", "UPDATE t SET v='changed'", null, null, null, null, true);
        BackupManager.setRestoreFailureHookForTest(phase -> { if ("after_install_databases".equals(phase)) throw new RuntimeException("interrupted"); });
        try {
            assertThrows(DataException.class, () -> BackupManager.restore(context, projectId, record.id, RestoreOptions.confirmed(null)));
        } finally {
            BackupManager.setRestoreFailureHookForTest(null);
        }
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main", "SELECT v FROM t", null, null, null, null, true);
        assertEquals("changed", result.rows.get(0).get(0));
        String content = new String(java.nio.file.Files.readAllBytes(FileStore.contentFile(context, FileStore.listAll(context, projectId).get(0)).toPath()), StandardCharsets.UTF_8);
        assertEquals("precious", content);
        assertNoTempVisibleArtifacts();
        assertHasOperation("backup.restore.rollback", "interrupted");
    }

    @Test
    public void insufficientStorageFailurePreservesOriginalData() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        DatabaseManager.execute(context, projectId, "main", "UPDATE t SET v='changed'", null, null, null, null, true);
        BackupManager.setRestoreFailureHookForTest(phase -> { if ("before_install_databases".equals(phase)) throw new IOException("No space left on device"); });
        try {
            DataException err = assertThrows(DataException.class, () -> BackupManager.restore(context, projectId, record.id, RestoreOptions.confirmed(null)));
            assertTrue(err.getMessage().toLowerCase().contains("space"));
        } finally {
            BackupManager.setRestoreFailureHookForTest(null);
        }
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main", "SELECT v FROM t", null, null, null, null, true);
        assertEquals("changed", result.rows.get(0).get(0));
        assertNoTempVisibleArtifacts();
        assertHasOperation("backup.restore.failed", "space");
    }

    @Test
    public void recoveryPointCanRestoreOriginalAfterFailedRestore() throws Exception {
        seedProject();
        Records.BackupRecord record = BackupManager.create(context, projectId, true, "");
        DatabaseManager.execute(context, projectId, "main", "UPDATE t SET v='changed'", null, null, null, null, true);
        BackupManager.setRestoreFailureHookForTest(phase -> { if ("before_post_verify".equals(phase)) throw new DataException("injected post-verify failure", 500); });
        try {
            assertThrows(DataException.class, () -> BackupManager.restore(context, projectId, record.id, RestoreOptions.confirmed(null)));
        } finally {
            BackupManager.setRestoreFailureHookForTest(null);
        }
        Records.BackupRecord recovery = findRecoveryBackup();
        assertNotNull(recovery);
        BackupManager.restore(context, projectId, recovery.id, RestoreOptions.confirmed(null));
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main", "SELECT v FROM t", null, null, null, null, true);
        assertEquals("changed", result.rows.get(0).get(0));
        assertNoTempVisibleArtifacts();
        assertHasOperation("backup.restore.recovery_restored", recovery.id);
    }


    private Records.BackupRecord findRecoveryBackup() throws Exception {
        for (Records.BackupRecord b : BackupManager.list(context, projectId)) if ("recovery".equals(b.backupType)) return b;
        return null;
    }

    private void assertNoTempVisibleArtifacts() throws Exception {
        StorageLayout layout = ContentStoreBridge.layout(context);
        File filesDir = layout.filesDir(projectId);
        File dbDir = layout.databasesDir(projectId);
        assertNoTemp(filesDir);
        assertNoTemp(dbDir);
        File config = layout.projectConfig(projectId);
        assertFalse(config.getName().contains(".tmp"));
    }

    private void assertNoTemp(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            assertFalse("temp artifact visible: " + c.getName(), c.getName().contains(".tmp") || c.getName().contains(".restore") || c.getName().contains("before"));
            if (c.isDirectory()) assertNoTemp(c);
        }
    }

    private void assertHasOperation(String action, String detailFragment) {
        android.database.sqlite.SQLiteDatabase db = com.yadaventerprise.phoneserver.data.database.SystemDatabase.get(context).getReadableDatabase();
        android.database.Cursor c = db.rawQuery("SELECT action, detail FROM operations WHERE project_id=? ORDER BY id DESC", new String[]{projectId});
        try {
            while (c.moveToNext()) {
                String a = c.getString(0);
                String d = c.getString(1);
                if (action.equals(a) && (detailFragment == null || (d != null && d.contains(detailFragment)))) return;
            }
        } finally {
            c.close();
        }
        org.junit.Assert.fail("missing operation " + action + " containing " + detailFragment);
    }

    @Test
    public void importRejectsSensitiveProjectConfig() throws Exception {
        seedProject();
        File staging = tmp.newFolder("bad-import");
        File dbDir = new File(staging, "databases");
        assertTrue(dbDir.mkdirs());
        java.nio.file.Files.write(new File(dbDir, "main.db").toPath(), new byte[0]);
        org.json.JSONObject metadata = new org.json.JSONObject();
        metadata.put("format", "phoneserver-project-metadata");
        metadata.put("version", 1);
        metadata.put("files", new org.json.JSONArray());
        metadata.put("databases", new org.json.JSONArray());
        metadata.put("folders", new org.json.JSONArray());
        java.nio.file.Files.write(new File(staging, "metadata.json").toPath(),
                metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        org.json.JSONObject config = new org.json.JSONObject();
        config.put("format", "phoneserver-project");
        config.put("name", "Alpha");
        config.put("password", "secret");
        java.nio.file.Files.write(new File(staging, "project-config.json").toPath(),
                config.toString(2).getBytes(StandardCharsets.UTF_8));

        java.util.Map<String, String> manifest = new java.util.LinkedHashMap<>();
        manifest.put("format", BackupArchive.FORMAT);
        manifest.put("version", BackupArchive.VERSION);
        manifest.put("projectId", projectId);
        manifest.put("projectName", "Alpha");
        manifest.put("includesFiles", "true");
        manifest.put("includesDatabases", "true");
        File archive = new File(tmp.getRoot(), "bad.zip");
        java.util.List<BackupArchive.Entry> entries = new java.util.ArrayList<>();
        entries.add(new BackupArchive.Entry("metadata.json", new File(staging, "metadata.json")));
        entries.add(new BackupArchive.Entry("project-config.json", new File(staging, "project-config.json")));
        BackupArchive.create(archive, manifest, entries);

        DataException error = assertThrows(DataException.class,
                () -> BackupManager.importArchive(context, projectId, archive, true));
        assertEquals(422, error.httpStatus());
        assertTrue(error.getMessage().toLowerCase().contains("sensitive"));
    }
}
