package com.yadaventerprise.phoneserver.data.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SystemLogSanitizerTest {

    @Test
    public void redactsCredentialsFromMessages() {
        String value = "GET /api/v1/system?token=abc123&pw=hunter2 "
                + "authorization: Bearer secret-token x-auth-password: letmein";
        String result = SystemLogSanitizer.sanitize(value);
        assertFalse(result.contains("abc123"));
        assertFalse(result.contains("hunter2"));
        assertFalse(result.contains("secret-token"));
        assertFalse(result.contains("letmein"));
        assertTrue(result.contains("[redacted]"));
    }

    @Test
    public void truncatesVeryLongMessages() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 2500; i++) builder.append('x');
        String result = SystemLogSanitizer.sanitize(builder.toString());
        assertEquals(2001, result.length());
        assertTrue(result.endsWith("…"));
    }

    @Test
    public void nullBecomesEmptyString() {
        assertEquals("", SystemLogSanitizer.sanitize(null));
    }
}
