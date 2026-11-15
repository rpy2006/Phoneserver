package com.yadaventerprise.phoneserver.data.security;

import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Checks the real current configuration and returns concrete findings.
 * It never invents vulnerabilities or statistics.
 */
public final class SecurityScanner {

    private SecurityScanner() {
    }

    public static SecurityReport scan(SecurityConfig config) {
        SecurityReport report = new SecurityReport();
        report.generatedAt = System.currentTimeMillis();
        if (config == null) config = new SecurityConfig();

        if (!config.passwordSet) {
            report.findings.add(finding("no_password",
                    config.serverRunning ? SecurityFinding.CRITICAL : SecurityFinding.HIGH,
                    "No access password is set",
                    "File-management requests are not authenticated when no password is configured.",
                    "Set a strong password in Server Settings > Authentication."));
        } else if (config.passwordLength < 8) {
            report.findings.add(finding("weak_password", SecurityFinding.HIGH,
                    "Access password is weak",
                    "The configured password has " + config.passwordLength + " characters.",
                    "Use a password with at least 12 characters, ideally a passphrase."));
        } else if (config.passwordLength < 12) {
            report.findings.add(finding("short_password", SecurityFinding.MEDIUM,
                    "Access password is short",
                    "The configured password has " + config.passwordLength + " characters.",
                    "Use a password with at least 12 characters."));
        }

        if (config.allowRemoteAccess && !config.passwordSet) {
            report.findings.add(finding("remote_without_password", SecurityFinding.CRITICAL,
                    "Remote file access without a password",
                    "Remote file access is enabled while no access password is set.",
                    "Set a password or disable Allow remote access."));
        }

        if ("*".equals(config.allowedOrigins.trim())) {
            report.findings.add(finding("cors_wildcard", SecurityFinding.MEDIUM,
                    "CORS allows every origin",
                    "The API sends Access-Control-Allow-Origin: * to any origin.",
                    "Restrict allowed origins to the sites that need API access."));
        }

        if (config.rateLimitPerMinute <= 0) {
            report.findings.add(finding("rate_limit_disabled", SecurityFinding.MEDIUM,
                    "API rate limit is disabled",
                    "The per-IP API rate limit is set to " + config.rateLimitPerMinute + " requests/minute.",
                    "Set a positive per-minute limit in Advanced Settings."));
        } else if (config.rateLimitPerMinute > 1200) {
            report.findings.add(finding("rate_limit_high", SecurityFinding.MEDIUM,
                    "API rate limit is very high",
                    "The per-IP API rate limit is " + config.rateLimitPerMinute + " requests/minute.",
                    "Lower the limit unless a trusted client genuinely needs this volume."));
        } else if (config.rateLimitPerMinute > 600) {
            report.findings.add(finding("rate_limit_elevated", SecurityFinding.LOW,
                    "API rate limit is elevated",
                    "The per-IP API rate limit is " + config.rateLimitPerMinute + " requests/minute.",
                    "Review whether this volume is required."));
        }

        if (config.allowBackup) {
            report.findings.add(finding("allow_backup", SecurityFinding.MEDIUM,
                    "Android app backup is enabled",
                    "android:allowBackup=\"true\" lets app-private data be included in device backups.",
                    "Set android:allowBackup=\"false\" if the data must stay on this device."));
        }

        if (config.usesCleartextTraffic) {
            report.findings.add(finding("cleartext_http", SecurityFinding.MEDIUM,
                    "HTTP is cleartext",
                    "The server is intentionally plain HTTP; traffic on the network is not encrypted.",
                    "Use it on a trusted LAN or put a TLS reverse proxy/VPN in front of it."));
        }

        if (config.showHiddenFiles) {
            report.findings.add(finding("hidden_files", SecurityFinding.LOW,
                    "Hidden files are shown",
                    "The file browser and legacy listing include dot-files.",
                    "Hide hidden files unless you need to manage them."));
        }

        if (config.debuggable) {
            report.findings.add(finding("debug_build", SecurityFinding.INFO,
                    "Debug build",
                    "This APK is debuggable, which is expected for development builds.",
                    "Use a release build for daily use."));
        }

        if (config.fullStorageAccess) {
            report.findings.add(finding("full_storage", SecurityFinding.INFO,
                    "Full storage access granted",
                    "MANAGE_EXTERNAL_STORAGE is granted so uploads can reach public folders.",
                    "Revoke it in Android settings if you only need app-private storage."));
        }

        if (config.unscopedWriteTokenCount > 0) {
            report.findings.add(finding("unscoped_write_tokens", SecurityFinding.MEDIUM,
                    "Unscoped write token(s)",
                    config.unscopedWriteTokenCount + " active token(s) can write to every project.",
                    "Scope write tokens to a single project, or use viewer/editor roles with a scope."));
        }

        if (config.adminTokenCount > 0) {
            report.findings.add(finding("admin_tokens", SecurityFinding.INFO,
                    "Admin-role token(s) exist",
                    config.adminTokenCount + " active token(s) have the admin role.",
                    "Use admin tokens only for trusted automation and rotate them regularly."));
        }

        if (config.tokensWithoutRateLimit > 0) {
            report.findings.add(finding("tokens_global_limit", SecurityFinding.INFO,
                    "Token-specific limits are not set",
                    config.tokensWithoutRateLimit + " active token(s) use only the global per-IP limit.",
                    "Set a per-token rate limit for unattended clients."));
        }

        if (config.recentIntrusionCount > 0) {
            report.findings.add(finding("recent_intrusions",
                    config.recentIntrusionCount >= 5 ? SecurityFinding.HIGH : SecurityFinding.MEDIUM,
                    "Recent intrusion events",
                    config.recentIntrusionCount + " security event(s) were recorded in the last 7 days.",
                    "Review Security Events and unblock only IPs you recognise."));
        }

        if (config.blockedIpCount > 0) {
            report.findings.add(finding("blocked_ips", SecurityFinding.INFO,
                    "Blocked IP addresses",
                    config.blockedIpCount + " IP address(es) are currently blocked.",
                    "Review the block list and remove stale entries."));
        }

        if (!config.backupScheduleEnabled) {
            report.findings.add(finding("backup_schedule_off", SecurityFinding.LOW,
                    "Scheduled backups are disabled",
                    "Projects are only backed up when you create a backup manually.",
                    "Enable a backup schedule in Security Center."));
        }

        if (config.lastBackupError != null && !config.lastBackupError.isEmpty()) {
            report.findings.add(finding("backup_error", SecurityFinding.MEDIUM,
                    "Last scheduled backup failed",
                    config.lastBackupError,
                    "Open Scheduled Backups, fix the reported error, and run a backup."));
        }

        if (report.findings.isEmpty()) {
            report.findings.add(finding("no_issues", SecurityFinding.INFO,
                    "No issues detected",
                    "All checked settings are in a safe state for the current configuration.",
                    "Re-scan after changing settings or creating tokens."));
        }

        Collections.sort(report.findings, new Comparator<SecurityFinding>() {
            @Override
            public int compare(SecurityFinding a, SecurityFinding b) {
                return Integer.compare(SecurityFinding.weight(b.severity),
                        SecurityFinding.weight(a.severity));
            }
        });

        int score = 100;
        for (SecurityFinding finding : report.findings) score -= SecurityFinding.weight(finding.severity);
        report.score = Math.max(0, Math.min(100, score));
        report.grade = grade(report.score);
        report.summary = String.format(Locale.US, "%d finding(s) from the current configuration",
                report.findings.size());
        return report;
    }

    private static SecurityFinding finding(String id, String severity, String title, String detail,
                                           String remediation) {
        SecurityFinding finding = new SecurityFinding();
        finding.id = id;
        finding.severity = severity;
        finding.title = title;
        finding.detail = detail;
        finding.remediation = remediation;
        return finding;
    }

    private static String grade(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }
}
