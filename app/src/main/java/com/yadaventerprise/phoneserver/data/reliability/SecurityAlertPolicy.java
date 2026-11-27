package com.yadaventerprise.phoneserver.data.reliability;

public final class SecurityAlertPolicy {

    public static final String TYPE_AUTH_FAILURE = "auth_failure";
    public static final String TYPE_SUSPICIOUS_PATH = "suspicious_path";
    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";

    public boolean authFailureAlerts = true;
    public boolean suspiciousActivityAlerts = true;
    public String minimumSeverity = MEDIUM;
    public int maxAlertsPerHour = 20;

    public boolean shouldAlert(String type, String severity, int alertsAlreadySent) {
        if (alertsAlreadySent < 0 || alertsAlreadySent >= Math.max(1, maxAlertsPerHour)) return false;
        if (TYPE_AUTH_FAILURE.equals(type) && !authFailureAlerts) return false;
        if (TYPE_SUSPICIOUS_PATH.equals(type) && !suspiciousActivityAlerts) return false;
        return rank(severity) >= rank(minimumSeverity);
    }

    public SecurityAlertPolicy copy() {
        SecurityAlertPolicy copy = new SecurityAlertPolicy();
        copy.authFailureAlerts = authFailureAlerts;
        copy.suspiciousActivityAlerts = suspiciousActivityAlerts;
        copy.minimumSeverity = minimumSeverity;
        copy.maxAlertsPerHour = maxAlertsPerHour;
        return copy;
    }

    private static int rank(String severity) {
        if (HIGH.equalsIgnoreCase(severity)) return 3;
        if (MEDIUM.equalsIgnoreCase(severity)) return 2;
        if (LOW.equalsIgnoreCase(severity)) return 1;
        return 0;
    }
}
