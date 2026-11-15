package com.yadaventerprise.phoneserver.network.tls;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public class TlsIdentity {
    public final PrivateKey privateKey;
    public final X509Certificate certificate;
    public final String fingerprint;
    public final String subject;
    public final long createdAt;

    public TlsIdentity(PrivateKey privateKey, X509Certificate certificate,
                       String fingerprint, String subject, long createdAt) {
        this.privateKey = privateKey;
        this.certificate = certificate;
        this.fingerprint = fingerprint;
        this.subject = subject;
        this.createdAt = createdAt;
    }

    /** True when the certificate signs itself, i.e. browsers will not trust it by default. */
    public boolean isSelfSigned() {
        if (certificate == null) return false;
        return certificate.getIssuerX500Principal().equals(
                certificate.getSubjectX500Principal());
    }
}
