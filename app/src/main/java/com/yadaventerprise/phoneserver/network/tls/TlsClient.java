package com.yadaventerprise.phoneserver.network.tls;

import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class TlsClient {

    private TlsClient() {
    }

    public static void configure(HttpsURLConnection connection, String fingerprint) throws Exception {
        if (connection == null || fingerprint == null || fingerprint.trim().isEmpty()) return;
        final String expected = fingerprint;
        TrustManager[] trustManagers = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("client certificates are not accepted");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("no server certificate");
                for (X509Certificate certificate : chain) {
                    if (TlsCertificateFactory.matchesFingerprint(certificate, expected)) return;
                }
                throw new CertificateException("server certificate fingerprint does not match");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> {
            try {
                java.security.cert.Certificate[] certificates = session.getPeerCertificates();
                for (java.security.cert.Certificate certificate : certificates) {
                    if (certificate instanceof X509Certificate
                            && TlsCertificateFactory.matchesFingerprint((X509Certificate) certificate, expected)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
            return false;
        });
    }
}
