package com.yadaventerprise.phoneserver.network.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public final class TlsCertificateFactory {

    private static final String SHA256_RSA = "1.2.840.113549.1.1.11";
    private static final String RSA = "1.2.840.113549.1.1.1";
    private static final String COMMON_NAME = "2.5.4.3";
    private static final String BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String KEY_USAGE = "2.5.29.15";
    private static final String EXT_KEY_USAGE = "2.5.29.37";
    private static final String SUBJECT_ALT_NAME = "2.5.29.17";
    private static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    private TlsCertificateFactory() {
    }

    public static TlsIdentity generate(String commonName, List<String> hosts, List<String> ips) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        String cn = cleanCommonName(commonName);
        byte[] name = name(cn);
        byte[] algorithm = algorithm(SHA256_RSA);
        byte[] serial = new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE).toByteArray();
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 5L * 60L * 1000L)),
                utcTime(new Date(now + 10L * 365L * 24L * 60L * 60L * 1000L)));

        Set<String> dnsNames = new LinkedHashSet<>();
        dnsNames.add(cn);
        dnsNames.add("localhost");
        dnsNames.add("phoneserver.local");
        if (hosts != null) {
            for (String host : hosts) {
                String value = cleanHost(host);
                if (!value.isEmpty()) dnsNames.add(value);
            }
        }

        Set<String> ipValues = new LinkedHashSet<>();
        ipValues.add("127.0.0.1");
        if (ips != null) {
            for (String ip : ips) {
                String value = cleanIp(ip);
                if (!value.isEmpty()) ipValues.add(value);
            }
        }

        byte[] extensions = explicit(3, seq(
                extension(BASIC_CONSTRAINTS, true, seq()),
                extension(KEY_USAGE, true, bitString(new byte[]{(byte) 0xA0}, 5)),
                extension(EXT_KEY_USAGE, false, seq(oid(SERVER_AUTH))),
                extension(SUBJECT_ALT_NAME, false, subjectAltNames(dnsNames, ipValues))
        ));

        byte[] tbs = seq(
                explicit(0, integer(BigInteger.valueOf(2))),
                integer(new BigInteger(1, serial)),
                algorithm,
                name,
                validity,
                name,
                subjectPublicKeyInfo(keyPair),
                extensions
        );

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();

        byte[] certDer = seq(tbs, algorithm, bitString(signature, 0));
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(certDer));
        certificate.verify(keyPair.getPublic());

        return new TlsIdentity(keyPair.getPrivate(), certificate, fingerprint(certDer),
                certificate.getSubjectX500Principal().getName(), now);
    }

    public static String fingerprint(byte[] encoded) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(encoded);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) out.append(':');
            out.append(String.format(Locale.US, "%02X", hash[i] & 0xff));
        }
        return out.toString();
    }

    public static String fingerprint(X509Certificate certificate) {
        try {
            return fingerprint(certificate.getEncoded());
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean matchesFingerprint(X509Certificate certificate, String expected) {
        if (certificate == null || expected == null) return false;
        String normalized = expected.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) return false;
        return fingerprint(certificate).replace(":", "").toLowerCase(Locale.US).equals(normalized);
    }

    private static byte[] subjectAltNames(Set<String> dnsNames, Set<String> ips) {
        List<byte[]> values = new ArrayList<>();
        for (String host : dnsNames) {
            values.add(tlv(0x82, host.getBytes(StandardCharsets.US_ASCII)));
        }
        for (String ip : ips) {
            try {
                byte[] raw = InetAddress.getByName(ip).getAddress();
                if (raw.length == 4 || raw.length == 16) values.add(tlv(0x87, raw));
            } catch (Exception ignored) {
            }
        }
        return seq(values.toArray(new byte[0][]));
    }

    private static byte[] name(String commonName) {
        return seq(set(seq(oid(COMMON_NAME), utf8(commonName))));
    }

    private static byte[] subjectPublicKeyInfo(KeyPair pair) {
        byte[] rsaPublicKey = seq(integer(((java.security.interfaces.RSAPublicKey) pair.getPublic()).getModulus()),
                integer(((java.security.interfaces.RSAPublicKey) pair.getPublic()).getPublicExponent()));
        return seq(algorithm(RSA), bitString(rsaPublicKey, 0));
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        if (critical) {
            return seq(oid(oid), bool(true), octetString(value));
        }
        return seq(oid(oid), octetString(value));
    }

    private static byte[] algorithm(String oid) {
        return seq(oid(oid), tlv(0x05, new byte[0]));
    }

    private static byte[] oid(String value) {
        String[] parts = value.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long number = Long.parseLong(parts[i]);
            if (number == 0) {
                out.write(0);
                continue;
            }
            int count = 0;
            long copy = number;
            while (copy > 0) {
                count++;
                copy >>= 7;
            }
            for (int j = count - 1; j >= 0; j--) {
                int b = (int) ((number >> (7 * j)) & 0x7f);
                if (j != 0) b |= 0x80;
                out.write(b);
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    private static byte[] bool(boolean value) {
        return tlv(0x01, new byte[]{value ? (byte) 0xff : 0});
    }

    private static byte[] octetString(byte[] value) {
        return tlv(0x04, value);
    }

    private static byte[] utf8(String value) {
        return tlv(0x0c, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] utcTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] bitString(byte[] value, int unusedBits) {
        byte[] content = new byte[value.length + 1];
        content[0] = (byte) unusedBits;
        System.arraycopy(value, 0, content, 1, value.length);
        return tlv(0x03, content);
    }

    private static byte[] seq(byte[]... values) {
        return tlv(0x30, concat(values));
    }

    private static byte[] set(byte[]... values) {
        return tlv(0x31, concat(values));
    }

    private static byte[] explicit(int tag, byte[] value) {
        return tlv(0xa0 | tag, value);
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else if (length < 65536) {
            out.write(0x82);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        } else {
            out.write(0x83);
            out.write((length >> 16) & 0xff);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) {
            if (value != null) out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    private static String cleanCommonName(String value) {
        String cleaned = cleanHost(value);
        return cleaned.isEmpty() ? "PhoneServer" : cleaned;
    }

    private static String cleanHost(String value) {
        if (value == null) return "";
        String host = value.trim().toLowerCase(Locale.US);
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) host = host.substring(8);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (!host.matches("[a-z0-9][a-z0-9.\\-]{0,252}")) return "";
        return host;
    }

    private static String cleanIp(String value) {
        if (value == null) return "";
        String ip = value.trim();
        if (!ip.matches("[0-9a-fA-F:.]+")) return "";
        return ip;
    }
}
