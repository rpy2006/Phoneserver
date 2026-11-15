package com.yadaventerprise.phoneserver.network.tls;

import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class TlsContextFactory {

    private TlsContextFactory() {
    }

    public static SSLContext create(TlsIdentity identity) throws Exception {
        char[] password = new char[0];
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("phoneserver", identity.privateKey, password,
                new java.security.cert.Certificate[]{identity.certificate});
        KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, new SecureRandom());
        return context;
    }
}
