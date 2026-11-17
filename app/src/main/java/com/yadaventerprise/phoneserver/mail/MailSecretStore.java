package com.yadaventerprise.phoneserver.mail;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.Locale;

public final class MailSecretStore {

    private static final String PROVIDER = "AndroidKeyStore";
    private static final String PREFIX = "KS1:";

    private MailSecretStore() {
    }

    public static synchronized String encrypt(Context context, String accountId, String secret) throws MailException {
        if (context == null || accountId == null || accountId.trim().isEmpty()) {
            throw new MailException("secure account storage is unavailable", 500);
        }
        if (secret == null || secret.isEmpty()) return "";
        try {
            SecretKey key = key(accountId);
            byte[] encrypted = MailCrypto.encrypt(key, secret.getBytes(StandardCharsets.UTF_8));
            return PREFIX + MailBase64.encode(encrypted);
        } catch (GeneralSecurityException e) {
            throw new MailException("the Android Keystore could not protect this password", 500);
        }
    }

    public static synchronized String decrypt(Context context, String accountId, String cipher) throws MailException {
        if (cipher == null || cipher.isEmpty()) return "";
        if (!cipher.startsWith(PREFIX)) throw new MailException("stored password format is unsupported", 500);
        try {
            SecretKey key = key(accountId);
            byte[] combined = MailBase64.decode(cipher.substring(PREFIX.length()));
            return new String(MailCrypto.decrypt(key, combined), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new MailException("the stored password could not be decrypted", 500);
        }
    }

    public static synchronized void delete(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) return;
        try {
            KeyStore store = KeyStore.getInstance(PROVIDER);
            store.load(null);
            store.deleteEntry(alias(accountId));
        } catch (Exception ignored) {
        }
    }

    private static SecretKey key(String accountId) throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(PROVIDER);
        try {
            store.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException("keystore unavailable", e);
        }
        String alias = alias(accountId);
        if (store.containsAlias(alias)) {
            KeyStore.Entry entry = store.getEntry(alias, null);
            if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            store.deleteEntry(alias);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String alias(String accountId) {
        String cleaned = accountId.toLowerCase(Locale.US).replaceAll("[^a-z0-9_-]", "");
        if (cleaned.length() > 48) cleaned = cleaned.substring(0, 48);
        return "ps_mail_" + cleaned;
    }
}
