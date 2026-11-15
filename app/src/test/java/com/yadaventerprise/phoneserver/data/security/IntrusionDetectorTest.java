package com.yadaventerprise.phoneserver.data.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IntrusionDetectorTest {

    @Test
    public void detectsCommonProbePaths() {
        assertTrue(IntrusionDetector.isSuspiciousPath("/.env"));
        assertTrue(IntrusionDetector.isSuspiciousPath("/.git/config"));
        assertTrue(IntrusionDetector.isSuspiciousPath("/wp-admin/install.php"));
        assertTrue(IntrusionDetector.isSuspiciousPath("/phpmyadmin/index.php"));
        assertTrue(IntrusionDetector.isSuspiciousPath("/etc/passwd"));
        assertTrue(IntrusionDetector.isSuspiciousPath("/files/../../secret"));
        assertTrue(IntrusionDetector.isSuspiciousPath("/api/%2e%2e/secret"));
        assertTrue(IntrusionDetector.isSuspiciousPath("/api?q=union%20select"));
    }

    @Test
    public void allowsRealApplicationPaths() {
        assertFalse(IntrusionDetector.isSuspiciousPath("/api/projects"));
        assertFalse(IntrusionDetector.isSuspiciousPath("/api/projects/demo/files"));
        assertFalse(IntrusionDetector.isSuspiciousPath("/"));
        assertFalse(IntrusionDetector.isSuspiciousPath("/files/Documents/report.txt"));
        assertFalse(IntrusionDetector.isSuspiciousPath("/api/projects?search=union"));
    }

    @Test
    public void rejectsNullBytesAndOverlongPaths() {
        assertTrue(IntrusionDetector.isSuspiciousPath("/api/files/a%00.txt"));
        StringBuilder longPath = new StringBuilder("/api/files/");
        for (int i = 0; i < 3000; i++) longPath.append("a");
        assertTrue(IntrusionDetector.isSuspiciousPath(longPath.toString()));
    }
}
