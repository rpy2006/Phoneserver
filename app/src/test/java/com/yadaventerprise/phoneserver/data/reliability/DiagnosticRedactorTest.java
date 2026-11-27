package com.yadaventerprise.phoneserver.data.reliability;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticRedactorTest {

    @Test
    public void redactsAuthorizationAndCredentialFields() {
        String result = DiagnosticRedactor.sanitize(
                "Authorization: Bearer super-secret token=abc123 api_key: xyz789");
        assertFalse(result.contains("super-secret"));
        assertFalse(result.contains("abc123"));
        assertFalse(result.contains("xyz789"));
        assertTrue(result.contains("[redacted]"));
    }

    @Test
    public void redactsQuerySecretsEmailsAndIpAddresses() {
        String result = DiagnosticRedactor.sanitize(
                "GET /download?token=abc&pw=hunter2 from alice@example.com at 192.168.1.44");
        assertFalse(result.contains("abc"));
        assertFalse(result.contains("hunter2"));
        assertFalse(result.contains("alice@example.com"));
        assertFalse(result.contains("192.168.1.44"));
        assertTrue(result.contains("[redacted]"));
        assertTrue(result.contains("192.168.*.*"));
    }

    @Test
    public void truncatesLongMessages() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 2500; i++) builder.append('a');
        String result = DiagnosticRedactor.sanitize(builder.toString());
        assertTrue(result.endsWith("…"));
        assertTrue(result.length() <= 2001);
    }
}
