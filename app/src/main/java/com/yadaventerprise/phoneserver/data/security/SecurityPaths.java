package com.yadaventerprise.phoneserver.data.security;

import android.content.Context;
import java.io.File;

/** App-private locations for security logs, policies and schedules. */
public final class SecurityPaths {

    private SecurityPaths() {
    }

    public static File securityDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "security");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        restrict(dir);
        return dir;
    }

    public static File accessLog(Context context) {
        return new File(securityDir(context), "access-log.jsonl");
    }

    public static File securityEvents(Context context) {
        return new File(securityDir(context), "security-events.jsonl");
    }

    public static File blockedIps(Context context) {
        return new File(securityDir(context), "blocked-ips.json");
    }

    public static File securitySettings(Context context) {
        return new File(securityDir(context), "security-settings.json");
    }

    public static File tokenPolicies(Context context) {
        return new File(securityDir(context), "token-policies.json");
    }

    public static File securityReports(Context context) {
        return new File(securityDir(context), "security-reports.json");
    }

    public static File backupSchedule(Context context) {
        return new File(securityDir(context), "backup-schedule.json");
    }

    /** Best-effort owner-only permissions; internal storage is already app-private. */
    public static void restrict(File file) {
        if (file == null) return;
        try {
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            if (file.isDirectory()) file.setExecutable(true, true);
        } catch (SecurityException ignored) {
        }
    }
}
