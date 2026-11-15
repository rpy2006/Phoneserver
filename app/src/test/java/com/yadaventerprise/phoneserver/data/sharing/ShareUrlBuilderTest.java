package com.yadaventerprise.phoneserver.data.sharing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShareUrlBuilderTest {

    @Test
    public void selfSignedLocalCertificateFallsBackToHttp() {
        ShareUrlBuilder.Result result = ShareUrlBuilder.build(
                "", "192.168.1.5", 8443, 8080,
                true, true, false, true, "pss_token");
        assertEquals("http://192.168.1.5:8080/s/pss_token", result.url);
        assertFalse(result.secure);
        assertTrue(result.warning.contains("self-signed"));
    }

    @Test
    public void configuredPublicOriginUsesHttps() {
        ShareUrlBuilder.Result result = ShareUrlBuilder.build(
                "https://share.example.com", "192.168.1.5", 8443, 8080,
                true, true, false, true, "pss_token");
        assertEquals("https://share.example.com/s/pss_token", result.url);
        assertTrue(result.secure);
        assertEquals("", result.warning);
    }

    @Test
    public void trustedCertificateUsesHttpsOnLanAddress() {
        ShareUrlBuilder.Result result = ShareUrlBuilder.build(
                "", "192.168.1.5", 8443, 8080,
                true, true, true, true, "pss_token");
        assertEquals("https://192.168.1.5:8443/s/pss_token", result.url);
        assertTrue(result.secure);
        assertEquals("", result.warning);
    }

    @Test
    public void stoppedHttpsServerFallsBackToHttp() {
        ShareUrlBuilder.Result result = ShareUrlBuilder.build(
                "https://share.example.com", "192.168.1.5", 8443, 8080,
                true, false, true, true, "pss_token");
        assertEquals("http://192.168.1.5:8080/s/pss_token", result.url);
        assertFalse(result.secure);
        assertTrue(result.warning.contains("secure server is not running"));
    }

    @Test
    public void disabledHttpsUsesHttp() {
        ShareUrlBuilder.Result result = ShareUrlBuilder.build(
                "", "192.168.1.5", 8443, 8080,
                false, false, false, true, "pss_token");
        assertEquals("http://192.168.1.5:8080/s/pss_token", result.url);
        assertFalse(result.secure);
        assertTrue(result.warning.contains("not encrypted"));
    }
}
