package com.yadaventerprise.phoneserver.data;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Secure JSON/file storage with atomic writes and encryption for sensitive data.
 */
public final class JsonStorage {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 256;
    
    private static final ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();
    
    private JsonStorage() {
    }

    public static JSONObject readJson(File file) throws IOException, JSONException {
        if (file == null || !file.exists()) {
            return new JSONObject();
        }
        globalLock.readLock().lock();
        try {
            byte[] data = readFile(file);
            if (data.length == 0) {
                return new JSONObject();
            }
            String content = new String(data, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                return new JSONObject();
            }
            // Check if encrypted
            if (content.startsWith("ENC:")) {
                content = decrypt(content.substring(4), file.getParentFile());
            }
            return new JSONObject(content);
        } finally {
            globalLock.readLock().unlock();
        }
    }

    public static JSONArray readJsonArray(File file) throws IOException, JSONException {
        if (file == null || !file.exists()) {
            return new JSONArray();
        }
        globalLock.readLock().lock();
        try {
            byte[] data = readFile(file);
            if (data.length == 0) {
                return new JSONArray();
            }
            String content = new String(data, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                return new JSONArray();
            }
            if (content.startsWith("ENC:")) {
                content = decrypt(content.substring(4), file.getParentFile());
            }
            return new JSONArray(content);
        } finally {
            globalLock.readLock().unlock();
        }
    }

    public static void writeJsonAtomic(File file, JSONObject json) throws IOException {
        writeJsonAtomic(file, json, false);
    }

    public static void writeJsonAtomic(File file, JSONObject json, boolean encrypt) throws IOException {
        if (file == null) throw new IOException("null file");
        globalLock.writeLock().lock();
        try {
            StorageLayout.ensure(file.getParentFile());
            String content = json.toString();
            if (encrypt) {
                content = "ENC:" + encrypt(content, file.getParentFile());
            }
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp." + System.nanoTime());
            try {
                FileOutputStream fos = new FileOutputStream(tmp);
                try {
                    fos.write(data);
                    fos.flush();
                    fos.getFD().sync();
                } finally {
                    fos.close();
                }
                if (file.exists() && !file.delete()) {
                    // try to continue
                }
                if (!tmp.renameTo(file)) {
                    // fallback copy
                    copyFile(tmp, file);
                    tmp.delete();
                }
            } catch (IOException e) {
                if (tmp.exists()) tmp.delete();
                throw e;
            }
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    public static void writeJsonArrayAtomic(File file, JSONArray array) throws IOException {
        writeJsonArrayAtomic(file, array, false);
    }

    public static void writeJsonArrayAtomic(File file, JSONArray array, boolean encrypt) throws IOException {
        if (file == null) throw new IOException("null file");
        globalLock.writeLock().lock();
        try {
            StorageLayout.ensure(file.getParentFile());
            String content = array.toString();
            if (encrypt) {
                content = "ENC:" + encrypt(content, file.getParentFile());
            }
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp." + System.nanoTime());
            try {
                FileOutputStream fos = new FileOutputStream(tmp);
                try {
                    fos.write(data);
                    fos.flush();
                    fos.getFD().sync();
                } finally {
                    fos.close();
                }
                if (file.exists() && !file.delete()) {
                    // try
                }
                if (!tmp.renameTo(file)) {
                    copyFile(tmp, file);
                    tmp.delete();
                }
            } catch (IOException e) {
                if (tmp.exists()) tmp.delete();
                throw e;
            }
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private static byte[] readFile(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return data;
        } finally {
            fis.close();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
            out.getFD().sync();
        } finally {
            in.close();
            out.close();
        }
    }

    private static String getKeyFile(File dir) {
        if (dir == null) return null;
        File kf = new File(dir, ".key");
        return kf.getAbsolutePath();
    }

    private static SecretKey getOrCreateKey(File dir) throws Exception {
        File kf = new File(dir, ".key");
        StorageLayout.ensure(dir);
        if (kf.exists()) {
            FileInputStream fis = new FileInputStream(kf);
            try {
                byte[] keyBytes = new byte[(int) kf.length()];
                fis.read(keyBytes);
                return new SecretKeySpec(keyBytes, "AES");
            } finally {
                fis.close();
            }
        } else {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(KEY_LENGTH, new SecureRandom());
            SecretKey key = keyGen.generateKey();
            byte[] keyBytes = key.getEncoded();
            // atomic write
            File tmp = new File(dir, ".key.tmp." + System.nanoTime());
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(keyBytes);
                fos.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            if (kf.exists()) kf.delete();
            if (!tmp.renameTo(kf)) {
                copyFile(tmp, kf);
                tmp.delete();
            }
            // try to make private if possible
            try { kf.setReadable(false, false); kf.setReadable(true, true); } catch (Exception ignored) {}
            return key;
        }
    }

    private static String encrypt(String plaintext, File dir) throws IOException {
        try {
            SecretKey key = getOrCreateKey(dir);
            Cipher cipher = Cipher.getInstance(ALGO);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException("encryption failed", e);
        }
    }

    private static String decrypt(String encoded, File dir) throws IOException {
        try {
            SecretKey key = getOrCreateKey(dir);
            byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
            byte[] iv = new byte[IV_LENGTH];
            byte[] cipherText = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("decryption failed", e);
        }
    }
}
