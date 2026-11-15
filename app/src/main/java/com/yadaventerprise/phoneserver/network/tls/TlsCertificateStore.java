package com.yadaventerprise.phoneserver.network.tls;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import com.yadaventerprise.phoneserver.util.NetworkUtils;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class TlsCertificateStore {

    private static final String TAG = "TlsCertificateStore";
    private static volatile TlsCertificateStore instance;

    private final File file;
    private final Object lock = new Object();
    private TlsIdentity cached;

    private TlsCertificateStore(File file) {
        this.file = file;
    }

    public static TlsCertificateStore get(Context context) {
        if (instance == null) {
            synchronized (TlsCertificateStore.class) {
                if (instance == null) instance = new TlsCertificateStore(NetworkPaths.tlsIdentity(context));
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public TlsIdentity load() {
        synchronized (lock) {
            if (cached != null) return cached;
            try {
                JSONObject json = JsonStorage.readJson(file);
                String keyText = json.optString("privateKey", "");
                String certText = json.optString("certificate", "");
                if (keyText.isEmpty() || certText.isEmpty()) return null;
                byte[] keyBytes = Base64.decode(keyText, Base64.DEFAULT);
                byte[] certBytes = Base64.decode(certText, Base64.DEFAULT);
                java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
                PrivateKey key = keyFactory.generatePrivate(
                        new java.security.spec.PKCS8EncodedKeySpec(keyBytes));
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(certBytes));
                cached = new TlsIdentity(key, cert,
                        TlsCertificateFactory.fingerprint(cert),
                        cert.getSubjectX500Principal().getName(),
                        json.optLong("createdAt", 0));
                return cached;
            } catch (Exception e) {
                Log.w(TAG, "could not read TLS identity", e);
                return null;
            }
        }
    }

    public TlsIdentity getOrCreate(Context context, HttpsConfig config) throws Exception {
        TlsIdentity existing = load();
        if (existing != null) return existing;
        return generate(context, config);
    }

    public TlsIdentity generate(Context context, HttpsConfig config) throws Exception {
        List<String> hosts = new ArrayList<>();
        if (config != null) hosts.add(config.hostname);
        List<String> ips = new ArrayList<>();
        // The LAN IP is needed for HTTPS share links even when the pairing QR uses
        // the friendly hostname; without it the certificate is rejected when the
        // phone is opened by address.
        String ip = NetworkUtils.getLocalIpAddress();
        if (ip != null && !ip.isEmpty()) ips.add(ip);
        TlsIdentity identity = TlsCertificateFactory.generate(
                config == null ? "PhoneServer" : config.hostname, hosts, ips);
        save(identity);
        return identity;
    }

    public TlsIdentity importPkcs12(Context context, InputStream input, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(input, password);
        String alias = null;
        java.util.Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String candidate = aliases.nextElement();
            if (keyStore.isKeyEntry(candidate)) {
                alias = candidate;
                break;
            }
        }
        if (alias == null) throw new IllegalArgumentException("keystore contains no private key");
        Key key = keyStore.getKey(alias, password);
        if (!(key instanceof PrivateKey)) throw new IllegalArgumentException("unsupported private key");
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            throw new IllegalArgumentException("keystore contains no certificate");
        }
        X509Certificate certificate = (X509Certificate) chain[0];
        TlsIdentity identity = new TlsIdentity((PrivateKey) key, certificate,
                TlsCertificateFactory.fingerprint(certificate),
                certificate.getSubjectX500Principal().getName(), System.currentTimeMillis());
        save(identity);
        return identity;
    }

    public void save(TlsIdentity identity) throws Exception {
        if (identity == null) return;
        synchronized (lock) {
            JSONObject json = new JSONObject();
            json.put("privateKey", Base64.encodeToString(identity.privateKey.getEncoded(), Base64.NO_WRAP));
            json.put("certificate", Base64.encodeToString(identity.certificate.getEncoded(), Base64.NO_WRAP));
            json.put("fingerprint", identity.fingerprint);
            json.put("subject", identity.subject);
            json.put("createdAt", identity.createdAt);
            JsonStorage.writeJsonAtomic(file, json, true);
            SecurityPaths.restrict(file);
            cached = identity;
        }
    }

    public void delete() {
        synchronized (lock) {
            cached = null;
            if (file.exists()) file.delete();
        }
    }
}
