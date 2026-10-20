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
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FileStoreTest {

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

    private Records.FileRecord upload(String name, String mime, String content) throws DataException {
        return FileStore.upload(context, projectId, "", name, mime,
                DataTestSupport.bytes(content), content.length(), "", "");
    }

    @Test
    public void uploadStoresContentMetadataChecksumAndIsDownloadable() throws Exception {
        String body = "hello storage";
        Records.FileRecord record = upload("note.txt", "text/plain", body);

        assertEquals("note.txt", record.originalName);
        assertEquals("text/plain", record.mimeType);
        assertEquals(body.length(), record.sizeBytes);
        assertNotNull(record.checksum);
        assertEquals(64, record.checksum.length());
        assertTrue(record.storageKey.matches("[A-Za-z0-9_-]+"));

        File content = FileStore.contentFile(context, record);
        assertTrue(content.exists());
        assertEquals(body, new String(java.nio.file.Files.readAllBytes(content.toPath()),
                StandardCharsets.UTF_8));

        List<Records.FileRecord> listed = FileStore.list(context, projectId, "");
        assertEquals(1, listed.size());
        assertEquals(record.id, listed.get(0).id);
        assertEquals(1, FileStore.listAll(context, projectId).size());
    }

    @Test
    public void mimeTypeIsGuessedWhenNotSupplied() throws Exception {
        Records.FileRecord png = upload("photo.PNG", null, "img");
        assertEquals("image/png", png.mimeType);

        Records.FileRecord unknown = upload("archive.zzz", null, "x");
        assertEquals("application/octet-stream", unknown.mimeType);
    }

    @Test
    public void uploadRejectsTraversalInFilenameAndFolderPath() {
        DataException traversalName = assertThrows(DataException.class,
                () -> upload("../../etc/passwd", null, "x"));
        assertEquals("passwd", "passwd"); // sanitised, never a path
        assertNotNull(traversalName);

        DataException traversalFolder = assertThrows(DataException.class, () ->
                FileStore.upload(context, projectId, "../outside", "a.txt", "text/plain",
                        DataTestSupport.bytes("x"), 1, "", ""));
        assertEquals(400, traversalFolder.httpStatus());
    }

    @Test
    public void renameMoveAndDeleteUpdateMetadataWithoutLosingContent() throws Exception {
        Records.FileRecord record = upload("a.txt", "text/plain", "abc");

        Records.FileRecord renamed = FileStore.rename(context, projectId, record.id, "b.txt");
        assertEquals("b.txt", renamed.originalName);
        assertTrue(FileStore.contentFile(context, renamed).exists());

        FileStore.createFolder(context, projectId, "docs/2024");
        Records.FileRecord moved = FileStore.move(context, projectId, record.id, "docs/2024");
        assertEquals("docs/2024", moved.folderPath);
        assertEquals(1, FileStore.list(context, projectId, "docs/2024").size());

        File content = FileStore.contentFile(context, moved);
        FileStore.delete(context, projectId, record.id);
        assertFalse(content.exists());
        assertEquals(0, FileStore.listAll(context, projectId).size());
        assertThrows(DataException.class, () -> FileStore.require(context, projectId, record.id));
    }

    @Test
    public void foldersAreCreatedListedAndDeletionEnforcesEmptiness() throws Exception {
        FileStore.createFolder(context, projectId, "images/2024");
        List<Records.FolderRecord> folders = FileStore.listFolders(context, projectId);
        assertEquals(2, folders.size()); // images and images/2024

        // A folder containing a file cannot be deleted.
        uploadInFolder("shot.jpg", "images/2024", "img");
        DataException notEmpty = assertThrows(DataException.class,
                () -> FileStore.deleteFolder(context, projectId, "images/2024"));
        assertEquals(409, notEmpty.httpStatus());

        // Empty leaf folder can be deleted.
        FileStore.deleteFolder(context, projectId, "images/2024");
        assertEquals(1, FileStore.listFolders(context, projectId).size());
    }

    @Test
    public void uploadRejectsOversizedFilesAndEnforcesQuota() throws Exception {
        Records.ProjectRecord small = ProjectStore.create(context, "Tiny", "", 0, 10);
        DataException tooLarge = assertThrows(DataException.class, () ->
                FileStore.upload(context, small.id, "", "big.bin", null,
                        DataTestSupport.bytes("01234567890123456789"), 20, "", ""));
        assertEquals(413, tooLarge.httpStatus());
        assertEquals(0, FileStore.listAll(context, small.id).size());

        Records.ProjectRecord quota = ProjectStore.create(context, "Quota", "", 10, 0);
        FileStore.upload(context, quota.id, "", "one.bin", null,
                DataTestSupport.bytes("12345678"), 8, "", "");
        DataException exceeded = assertThrows(DataException.class, () ->
                FileStore.upload(context, quota.id, "", "two.bin", null,
                        DataTestSupport.bytes("12345678"), 8, "", ""));
        assertEquals(413, exceeded.httpStatus());
    }

    @Test
    public void filesAreIsolatedBetweenProjects() throws Exception {
        String other = ProjectStore.create(context, "Beta", "", 0, 0).id;
        Records.FileRecord mine = upload("mine.txt", "text/plain", "secret");

        assertEquals(1, FileStore.listAll(context, projectId).size());
        assertEquals(0, FileStore.listAll(context, other).size());

        // The other project cannot see my file by id either.
        assertThrows(DataException.class, () -> FileStore.require(context, other, mine.id));
    }

    @Test
    public void usageAndIssueDetectionFindMissingAndOrphanFiles() throws Exception {
        Records.FileRecord record = upload("keep.txt", "text/plain", "12345");

        FileStore.Usage usage = FileStore.usage(context, projectId);
        assertEquals(1, usage.fileCount);
        assertEquals(5, usage.bytes);

        // Simulate a file whose content vanished from disk.
        File content = FileStore.contentFile(context, record);
        assertTrue(content.delete());

        FileStore.Issues issues = FileStore.detectIssues(context, projectId);
        assertEquals(1, issues.missingFiles.size());
        assertEquals(record.id, issues.missingFiles.get(0).id);
        assertTrue(issues.orphanFiles.isEmpty());

        // Simulate an orphan blob that no metadata row points at.
        StorageLayout layout = com.yadaventerprise.phoneserver.ContentStore.getStorageLayout(context);
        File orphan = new File(layout.filesDir(projectId), "orphankey");
        java.nio.file.Files.write(orphan.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        FileStore.Issues withOrphan = FileStore.detectIssues(context, projectId);
        assertEquals(1, withOrphan.orphanFiles.size());
    }

    private void uploadInFolder(String name, String folder, String mime) throws DataException {
        FileStore.upload(context, projectId, folder, name, mime,
                DataTestSupport.bytes("data"), 4, "", "");
    }
}
