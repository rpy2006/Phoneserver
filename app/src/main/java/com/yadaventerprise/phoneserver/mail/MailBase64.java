package com.yadaventerprise.phoneserver.mail;

import java.io.ByteArrayOutputStream;

public final class MailBase64 {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private MailBase64() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int chunk = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            sb.append(ALPHABET[(chunk >>> 18) & 0x3f]);
            sb.append(ALPHABET[(chunk >>> 12) & 0x3f]);
            sb.append(ALPHABET[(chunk >>> 6) & 0x3f]);
            sb.append(ALPHABET[chunk & 0x3f]);
            i += 3;
        }
        int remaining = data.length - i;
        if (remaining == 1) {
            int chunk = (data[i] & 0xff) << 16;
            sb.append(ALPHABET[(chunk >>> 18) & 0x3f]);
            sb.append(ALPHABET[(chunk >>> 12) & 0x3f]);
            sb.append('=');
            sb.append('=');
        } else if (remaining == 2) {
            int chunk = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            sb.append(ALPHABET[(chunk >>> 18) & 0x3f]);
            sb.append(ALPHABET[(chunk >>> 12) & 0x3f]);
            sb.append(ALPHABET[(chunk >>> 6) & 0x3f]);
            sb.append('=');
        }
        return sb.toString();
    }

    public static String encodeWrapped(byte[] data, int lineLength) {
        String encoded = encode(data);
        if (lineLength <= 0 || encoded.length() <= lineLength) return encoded;
        StringBuilder sb = new StringBuilder(encoded.length() + encoded.length() / lineLength * 2);
        for (int i = 0; i < encoded.length(); i += lineLength) {
            if (i > 0) sb.append("\r\n");
            sb.append(encoded, i, Math.min(encoded.length(), i + lineLength));
        }
        return sb.toString();
    }

    public static byte[] decode(String text) {
        if (text == null) return new byte[0];
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || c == '+' || c == '/' || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                clean.append(c);
            }
        }
        String value = clean.toString();
        int padding = 0;
        while (padding < 2 && value.length() > padding && value.charAt(value.length() - 1 - padding) == '=') {
            padding++;
        }
        int usable = value.length() - padding;
        ByteArrayOutputStream out = new ByteArrayOutputStream(usable * 3 / 4 + 3);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < usable; i++) {
            int digit = decodeDigit(value.charAt(i));
            if (digit < 0) continue;
            buffer = (buffer << 6) | digit;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    private static int decodeDigit(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }
}
