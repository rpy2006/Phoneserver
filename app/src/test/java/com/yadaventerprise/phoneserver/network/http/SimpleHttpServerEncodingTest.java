package com.yadaventerprise.phoneserver.network.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Pure-JVM tests for the escaping helpers used by the shared-files HTML page.
 * Keeping these free of Android lets them run on every host, including the
 * aarch64 build machines where Robolectric's native runtime is unavailable.
 */
public class SimpleHttpServerEncodingTest {

    @Test
    public void htmlEscapingNeutralisesInjectedMarkup() {
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;",
                SimpleHttpServer.escapeHtml("<script>alert(1)</script>"));
        assertEquals("&quot; onmouseover=&quot;x", SimpleHttpServer.escapeHtml("\" onmouseover=\"x"));
        assertEquals("&amp;&#39;", SimpleHttpServer.escapeHtml("&'"));
        assertFalse(SimpleHttpServer.escapeHtml("<img src=x onerror=alert(1)>").contains("<img"));
    }

    @Test
    public void urlEncodingKeepsNamesInsideTheFilesPath() {
        assertEquals("a%20b.txt", SimpleHttpServer.urlEncodePath("a b.txt"));
        assertEquals("..%2F..%2Fetc%2Fpasswd", SimpleHttpServer.urlEncodePath("../../etc/passwd"));
        assertFalse(SimpleHttpServer.urlEncodePath("../x").contains("/"));
    }
}
