package com.yadaventerprise.phoneserver.network.tls;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class TlsCertificateFactoryTest {

    @Test
    public void generatesParseablePinnedCertificate() throws Exception {
        TlsIdentity identity = TlsCertificateFactory.generate(
                "phoneserver.local",
                Collections.singletonList("phoneserver.local"),
                Arrays.asList("192.168.1.20", "127.0.0.1"));
        assertNotNull(identity.privateKey);
        assertNotNull(identity.certificate);
        assertTrue(identity.certificate.getSubjectX500Principal().getName().contains("phoneserver.local"));
        assertTrue(identity.fingerprint.split(":").length == 32);
        assertTrue(TlsCertificateFactory.matchesFingerprint(identity.certificate, identity.fingerprint));
        assertTrue(TlsCertificateFactory.matchesFingerprint(identity.certificate,
                identity.fingerprint.replace(":", "").toLowerCase()));
        identity.certificate.verify(identity.certificate.getPublicKey());
        assertTrue(identity.isSelfSigned());
    }
}
