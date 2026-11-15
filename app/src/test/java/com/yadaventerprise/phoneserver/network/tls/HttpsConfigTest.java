package com.yadaventerprise.phoneserver.network.tls;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HttpsConfigTest {

    @Test
    public void acceptsOriginAndNormalizesIt() {
        assertEquals("https://example.com", HttpsConfig.cleanUrl("https://example.com/"));
        assertEquals("https://example.com", HttpsConfig.cleanUrl("  https://Example.COM  "));
        assertEquals("https://example.com:8443",
                HttpsConfig.cleanUrl("https://example.com:8443"));
    }

    @Test
    public void shareLinksUseLanAddressEvenWhenQrHostnameIsPreferred() {
        HttpsConfig config = new HttpsConfig();
        config.includeLocalIps = false;
        config.port = 8443;
        assertEquals("https://192.168.1.5:8443", config.shareBaseUrl("192.168.1.5"));
    }

    @Test
    public void shareLinksPreferConfiguredPublicOrigin() {
        HttpsConfig config = new HttpsConfig();
        config.publicUrl = "https://example.com/base";
        config.port = 8443;
        assertEquals("https://192.168.1.5:8443", config.shareBaseUrl("192.168.1.5"));
    }

    @Test
    public void rejectsPathsThatWouldBreakShareLinks() {
        assertEquals("", HttpsConfig.cleanUrl("https://example.com/base"));
        assertEquals("", HttpsConfig.cleanUrl("https://example.com/base/"));
        assertEquals("", HttpsConfig.cleanUrl("https://example.com/?x=1"));
        assertEquals("", HttpsConfig.cleanUrl("https://example.com/#fragment"));
    }

    @Test
    public void rejectsUnsupportedSchemesAndMalformedValues() {
        assertEquals("", HttpsConfig.cleanUrl("http://example.com"));
        assertEquals("", HttpsConfig.cleanUrl("https://"));
        assertEquals("", HttpsConfig.cleanUrl("https://user:pass@example.com"));
        assertEquals("", HttpsConfig.cleanUrl("not a url"));
        assertEquals("", HttpsConfig.cleanUrl(null));
    }
}
