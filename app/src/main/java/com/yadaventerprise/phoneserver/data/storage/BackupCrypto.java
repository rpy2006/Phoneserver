package com.yadaventerprise.phoneserver.data.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class BackupCrypto {

    public static final String MAGIC = "PSBKENC1";
    public static final int VERSION = 2;
    private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 256;
    private static final int TAG_LENGTH = 128;
    private static final int DEFAULT_ITERATIONS = 210000;
    private static final int MIN_ITERATIONS = 120000;
    private static final int BUFFER = 65536;

    private BackupCrypto() {
    }

    public static final class Params {
        public final int iterations;
        public final byte[] salt;
        public final byte[] iv;

        public Params(int iterations, byte[] salt, byte[] iv) {
            this.iterations = iterations;
            this.salt = salt;
            this.iv = iv;
        }
    }

    public static boolean isEncrypted(File file) {
        if (file == null || !file.exists() || !file.isFile() || file.length() < MAGIC_BYTES.length + 4) {
            return false;
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] magic = new byte[MAGIC_BYTES.length];
            if (readFully(in, magic) != magic.length) return false;
            return Arrays.equals(MAGIC_BYTES, magic);
        } catch (IOException e) {
            return false;
        }
    }

    public static void encrypt(File plainZip, File encryptedOut, char[] password) throws IOException {
        if (password == null || password.length == 0) {
            throw new IOException("backup password is required");
        }
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(salt);
        random.nextBytes(iv);
        int iterations = DEFAULT_ITERATIONS;
        File tmp = new File(encryptedOut.getAbsolutePath() + ".tmp");
        try {
            SecretKey key = deriveKey(password, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                out.write(MAGIC_BYTES);
                writeInt(out, VERSION);
                writeInt(out, iterations);
                writeInt(out, salt.length);
                out.write(salt);
                writeInt(out, iv.length);
                out.write(iv);
                try (InputStream in = new BufferedInputStream(new FileInputStream(plainZip))) {
                    byte[] buffer = new byte[BUFFER];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        byte[] enc = cipher.update(buffer, 0, read);
                        if (enc != null && enc.length > 0) out.write(enc);
                    }
                    byte[] fin = cipher.doFinal();
                    if (fin != null && fin.length > 0) out.write(fin);
                }
                out.flush();
            }
            publish(tmp, encryptedOut);
        } catch (GeneralSecurityException e) {
            tmp.delete();
            throw new IOException("could not encrypt backup", e);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static void decrypt(File encryptedIn, File plainZipOut, char[] password) throws IOException {
        if (password == null || password.length == 0) {
            throw new IOException("backup password is required");
        }
        if (!isEncrypted(encryptedIn)) {
            throw new IOException("not an encrypted PhoneServer backup");
        }
        File tmp = new File(plainZipOut.getAbsolutePath() + ".tmp");
        try (InputStream in = new BufferedInputStream(new FileInputStream(encryptedIn))) {
            byte[] magic = new byte[MAGIC_BYTES.length];
            readFully(in, magic);
            int version = readInt(in);
            if (version != VERSION) {
                throw new IOException("unsupported encrypted backup version: " + version);
            }
            int iterations = readInt(in);
            if (iterations < MIN_ITERATIONS || iterations > 2_000_000) {
                throw new IOException("unsupported backup key derivation parameters");
            }
            int saltLen = readInt(in);
            if (saltLen < 8 || saltLen > 64) throw new IOException("invalid backup encryption header");
            byte[] salt = new byte[saltLen];
            if (readFully(in, salt) != saltLen) throw new IOException("corrupt backup encryption header");
            int ivLen = readInt(in);
            if (ivLen < 12 || ivLen > 32) throw new IOException("invalid backup encryption header");
            byte[] iv = new byte[ivLen];
            if (readFully(in, iv) != ivLen) throw new IOException("corrupt backup encryption header");

            try {
                SecretKey key = deriveKey(password, salt, iterations);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                    byte[] buffer = new byte[BUFFER];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        byte[] dec = cipher.update(buffer, 0, read);
                        if (dec != null && dec.length > 0) out.write(dec);
                    }
                    byte[] fin = cipher.doFinal();
                    if (fin != null && fin.length > 0) out.write(fin);
                    out.flush();
                }
                publish(tmp, plainZipOut);
            } catch (GeneralSecurityException e) {
                tmp.delete();
                throw new IOException("backup decryption failed; password or file is invalid", e);
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(iv, (byte) 0);
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(encoded, "AES");
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static void publish(File tmp, File target) throws IOException {
        if (!tmp.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                tmp.delete();
                throw new IOException("could not replace existing file");
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("could not finalise encrypted backup");
            }
        }
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static int readInt(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new IOException("unexpected end of backup file");
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }
}
