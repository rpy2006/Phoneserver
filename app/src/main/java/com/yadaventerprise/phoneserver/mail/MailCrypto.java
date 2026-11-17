package com.yadaventerprise.phoneserver.mail;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class MailCrypto {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private MailCrypto() {
    }

    public static byte[] encrypt(javax.crypto.SecretKey key, byte[] plaintext) throws GeneralSecurityException {
        if (plaintext == null) plaintext = new byte[0];
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return combined;
    }

    public static byte[] decrypt(javax.crypto.SecretKey key, byte[] combined) throws GeneralSecurityException {
        if (combined == null || combined.length <= IV_LENGTH) throw new GeneralSecurityException("invalid ciphertext");
        byte[] iv = new byte[IV_LENGTH];
        byte[] encrypted = new byte[combined.length - IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(encrypted);
    }
}
