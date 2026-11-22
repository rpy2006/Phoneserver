package com.yadaventerprise.phoneserver.data.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BackupCryptoTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void encryptDecryptRoundTrip() throws Exception {
        File plain = tmp.newFile("plain.zip");
        byte[] bytes = new byte[4096];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        try (FileOutputStream out = new FileOutputStream(plain)) {
            out.write(bytes);
        }
        File enc = new File(tmp.getRoot(), "backup.zip.enc");
        File dec = new File(tmp.getRoot(), "dec.zip");

        BackupCrypto.encrypt(plain, enc, "correct horse battery staple".toCharArray());
        assertTrue(BackupCrypto.isEncrypted(enc));
        assertFalse(BackupCrypto.isEncrypted(plain));

        BackupCrypto.decrypt(enc, dec, "correct horse battery staple".toCharArray());
        assertArrayEquals(bytes, Files.readAllBytes(dec.toPath()));
    }

    @Test
    public void decryptRejectsWrongPassword() throws Exception {
        File plain = tmp.newFile("plain.zip");
        Files.write(plain.toPath(), "secret-data".getBytes());
        File enc = new File(tmp.getRoot(), "backup.zip.enc");
        BackupCrypto.encrypt(plain, enc, "password-one".toCharArray());
        File dec = new File(tmp.getRoot(), "dec.zip");

        assertThrows(IOException.class,
                () -> BackupCrypto.decrypt(enc, dec, "password-two".toCharArray()));
        assertFalse(dec.exists());
    }

    @Test
    public void encryptRequiresPassword() throws Exception {
        File plain = tmp.newFile("plain.zip");
        File enc = new File(tmp.getRoot(), "backup.zip.enc");
        assertThrows(IOException.class, () -> BackupCrypto.encrypt(plain, enc, null));
        assertThrows(IOException.class, () -> BackupCrypto.encrypt(plain, enc, new char[0]));
    }
}
