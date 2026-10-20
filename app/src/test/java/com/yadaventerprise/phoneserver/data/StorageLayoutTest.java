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

public class StorageLayoutTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void pathsAreDerivedFromTheRoot() {
        StorageLayout layout = new StorageLayout(new File("/data/root"));
        assertEquals(new File("/data/root/system/system.db"), layout.systemDb());
        assertEquals(new File("/data/root/projects/abc/databases"), layout.databasesDir("abc"));
        assertEquals(new File("/data/root/projects/abc/files"), layout.filesDir("abc"));
        assertEquals(new File("/data/root/projects/abc/backups"), layout.backupsDir("abc"));
    }

    @Test
    public void validateId_rejectsUnsafeProjectIds() {
        assertEquals("abc-123_X", StorageLayout.validateId("abc-123_X"));
        assertThrows(IllegalArgumentException.class, () -> StorageLayout.validateId(null));
        assertThrows(IllegalArgumentException.class, () -> StorageLayout.validateId("a/b"));
        assertThrows(IllegalArgumentException.class, () -> StorageLayout.validateId(".."));
        assertThrows(IllegalArgumentException.class, () -> StorageLayout.validateId("a b"));
    }

    @Test
    public void contentFile_rejectsInvalidStorageKeys() {
        StorageLayout layout = new StorageLayout(tmp.getRoot());
        File ok = layout.contentFile("abc", "AbC-123_x");
        assertNotNull(ok);
        assertThrows(IllegalArgumentException.class, () -> layout.contentFile("abc", "../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> layout.contentFile("abc", null));
        assertThrows(IllegalArgumentException.class, () -> layout.contentFile("abc", ""));
    }

    @Test
    public void newTempFile_livesUnderTmpDir() {
        StorageLayout layout = new StorageLayout(tmp.getRoot());
        File temp = layout.newTempFile("upload");
        assertTrue(temp.getParentFile().getAbsolutePath()
                .equals(layout.tmpDir().getAbsolutePath()));
        assertTrue(temp.getName().startsWith("upload-"));
        assertTrue(layout.tmpDir().isDirectory());
    }

    @Test
    public void ensure_createsDirectories() {
        File dir = new File(tmp.getRoot(), "a/b/c");
        assertFalse(dir.exists());
        StorageLayout.ensure(dir);
        assertTrue(dir.isDirectory());
    }
}
