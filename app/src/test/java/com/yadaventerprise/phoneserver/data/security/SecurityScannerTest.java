package com.yadaventerprise.phoneserver.data.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SecurityScannerTest {

    @Test
    public void missingPasswordIsCritical() {
        SecurityConfig config = new SecurityConfig();
        config.passwordSet = false;
        config.serverRunning = true;

        SecurityReport report = SecurityScanner.scan(config);

        assertEquals(1, report.count(SecurityFinding.CRITICAL));
        assertTrue(hasFinding(report, "no_password"));
        assertTrue(report.score < 100);
    }

    @Test
    public void wildcardCorsAndDisabledRateLimitAreReported() {
        SecurityConfig config = new SecurityConfig();
        config.passwordSet = true;
        config.passwordLength = 16;
        config.allowedOrigins = "*";
        config.rateLimitPerMinute = 0;
        config.backupScheduleEnabled = true;

        SecurityReport report = SecurityScanner.scan(config);

        assertTrue(hasFinding(report, "cors_wildcard"));
        assertTrue(hasFinding(report, "rate_limit_disabled"));
        assertFalse(hasFinding(report, "weak_password"));
    }

    @Test
    public void cleanConfigurationScoresFullGrade() {
        SecurityConfig config = new SecurityConfig();
        config.passwordSet = true;
        config.passwordLength = 20;
        config.allowedOrigins = "https://example.com";
        config.rateLimitPerMinute = 120;
        config.backupScheduleEnabled = true;

        SecurityReport report = SecurityScanner.scan(config);

        assertEquals(100, report.score);
        assertEquals("A", report.grade);
        assertTrue(hasFinding(report, "no_issues"));
    }

    private static boolean hasFinding(SecurityReport report, String id) {
        for (SecurityFinding finding : report.findings) {
            if (id.equals(finding.id)) return true;
        }
        return false;
    }
}
