package com.yadaventerprise.phoneserver.data.reliability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BackupIntegrityVerifierTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void verifiesMatchingChecksum() throws Exception {
        File file = folder.newFile("backup.zip");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("backup bytes".getBytes(StandardCharsets.UTF_8));
        }
        String hash = BackupIntegrityVerifier.sha256(file);
        BackupIntegrityVerifier.Result result = BackupIntegrityVerifier.verify(file, hash.toUpperCase());
        assertTrue(result.valid);
        assertEquals(hash, result.actualHash);
    }

    @Test
    public void rejectsMismatchedOrMissingChecksum() throws Exception {
        File file = folder.newFile("backup.zip");
        assertFalse(BackupIntegrityVerifier.verify(file, "deadbeef").valid);
        assertFalse(BackupIntegrityVerifier.verify(file, "").valid);
        assertFalse(BackupIntegrityVerifier.verify(new File(folder.getRoot(), "missing.zip"), "deadbeef").valid);
    }
}
