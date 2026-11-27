package com.yadaventerprise.phoneserver.data.reliability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SecurityAlertPolicyTest {

    @Test
    public void enforcesSeverityThresholds() {
        SecurityAlertPolicy policy = new SecurityAlertPolicy();
        assertTrue(policy.shouldAlert(SecurityAlertPolicy.TYPE_AUTH_FAILURE, "high", 0));
        assertFalse(policy.shouldAlert(SecurityAlertPolicy.TYPE_AUTH_FAILURE, "low", 0));
    }

    @Test
    public void enforcesDisabledTypesAndRateLimits() {
        SecurityAlertPolicy policy = new SecurityAlertPolicy();
        policy.authFailureAlerts = false;
        policy.maxAlertsPerHour = 2;
        assertFalse(policy.shouldAlert(SecurityAlertPolicy.TYPE_AUTH_FAILURE, "high", 0));
        assertTrue(policy.shouldAlert(SecurityAlertPolicy.TYPE_SUSPICIOUS_PATH, "high", 1));
        assertFalse(policy.shouldAlert(SecurityAlertPolicy.TYPE_SUSPICIOUS_PATH, "high", 2));
    }

    @Test
    public void copyPreservesConfiguration() {
        SecurityAlertPolicy policy = new SecurityAlertPolicy();
        policy.minimumSeverity = "low";
        policy.maxAlertsPerHour = 7;
        SecurityAlertPolicy copy = policy.copy();
        assertEquals("low", copy.minimumSeverity);
        assertEquals(7, copy.maxAlertsPerHour);
    }
}
