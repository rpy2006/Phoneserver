package com.yadaventerprise.phoneserver.data.system;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import com.yadaventerprise.phoneserver.data.storage.BackupManager;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.service.SystemNotifier;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public final class SystemMaintenance {

    private SystemMaintenance() {
    }

    public static MaintenanceReport inspect(Context context) {
        Context app = context.getApplicationContext();
        MaintenanceReport report = new MaintenanceReport();
        report.generatedAt = System.currentTimeMillis();
        scanTemp(app, report);
        checkJson(app, report);
        verifyBackups(app, report);
        report.logBytes = logBytes(app);
        return report;
    }

    public static MaintenanceReport cleanTempFiles(Context context) {
        Context app = context.getApplicationContext();
        MaintenanceReport report = new MaintenanceReport();
        report.generatedAt = System.currentTimeMillis();
        File dir = new File(app.getCacheDir(), "view_cache");
        deleteContents(dir, report);
        SystemEventStore.get(app).info("maintenance",
                "Removed " + report.removedFiles + " temporary file(s) ("
                        + com.yadaventerprise.phoneserver.data.stats.ServerStats.formatBytes(report.removedBytes)
                        + ")");
        MaintenanceReport after = inspect(app);
        report.issues.addAll(after.issues);
        report.jsonFilesChecked = after.jsonFilesChecked;
        report.backupsChecked = after.backupsChecked;
        report.validBackups = after.validBackups;
        report.invalidBackups = after.invalidBackups;
        report.logBytes = after.logBytes;
        JSONObject data = new JSONObject();
        try {
            data.put("removedFiles", report.removedFiles);
            data.put("removedBytes", report.removedBytes);
            data.put("issueCount", report.issueCount());
        } catch (Exception ignored) {
        }
        WebhookDispatcher.emit(app, WebhookDispatcher.EVENT_MAINTENANCE_COMPLETED, data);
        SystemNotifier.maintenanceFinished(app, report.issueCount());
        return report;
    }

    public static MaintenanceReport verifyBackups(Context context) {
        Context app = context.getApplicationContext();
        MaintenanceReport report = new MaintenanceReport();
        report.generatedAt = System.currentTimeMillis();
        verifyBackups(app, report);
        report.logBytes = logBytes(app);
        SystemEventStore.get(app).info("maintenance", "Verified " + report.backupsChecked + " backup(s); "
                + report.invalidBackups + " invalid");
        JSONObject data = new JSONObject();
        try {
            data.put("backupsChecked", report.backupsChecked);
            data.put("validBackups", report.validBackups);
            data.put("invalidBackups", report.invalidBackups);
        } catch (Exception ignored) {
        }
        WebhookDispatcher.emit(app, WebhookDispatcher.EVENT_MAINTENANCE_COMPLETED, data);
        SystemNotifier.maintenanceFinished(app, report.invalidBackups);
        return report;
    }

    private static void scanTemp(Context context, MaintenanceReport report) {
        File dir = new File(context.getCacheDir(), "view_cache");
        scan(dir, report);
    }

    private static void scan(File file, MaintenanceReport report) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            report.tempFilesCount++;
            report.tempBytes += file.length();
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) scan(child, report);
    }

    private static void deleteContents(File dir, MaintenanceReport report) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) deleteContents(child, report);
            if (child.isFile()) {
                long size = child.length();
                if (child.delete()) {
                    report.removedFiles++;
                    report.removedBytes += size;
                } else {
                    report.issues.add("Could not remove temporary file: " + child.getName());
                }
            }
            if (child.isDirectory() && child.delete()) {
                report.removedFiles++;
            }
        }
    }

    private static void checkJson(Context context, MaintenanceReport report) {
        File files = context.getFilesDir();
        checkFile(new File(files, "system/automation-settings.json"), false, report);
        checkFile(new File(files, "tasks/scheduled-tasks.json"), true, report);
        checkFile(new File(files, "tasks/task-history.json"), true, report);
        checkFile(new File(files, "webhooks/webhooks.json"), true, report);
        checkFile(new File(files, "webhooks/webhook-history.json"), true, report);
        checkFile(SecurityPaths.securitySettings(context), false, report);
    }

    private static void checkFile(File file, boolean array, MaintenanceReport report) {
        if (file == null || !file.exists()) return;
        report.jsonFilesChecked++;
        try {
            if (array) JsonStorage.readJsonArray(file);
            else JsonStorage.readJson(file);
        } catch (Exception e) {
            report.issues.add("Invalid JSON configuration: " + file.getName());
        }
    }

    private static void verifyBackups(Context context, MaintenanceReport report) {
        try {
            List<Records.ProjectRecord> projects = ProjectStore.list(context);
            for (Records.ProjectRecord project : projects) {
                List<Records.BackupRecord> backups = BackupManager.list(context, project.id);
                int limit = Math.min(backups.size(), 5);
                for (int i = 0; i < limit; i++) {
                    Records.BackupRecord backup = backups.get(i);
                    report.backupsChecked++;
                    try {
                        Map<String, String> manifest = BackupManager.validate(context, project.id, backup.id);
                        if (manifest == null || manifest.isEmpty()) {
                            report.invalidBackups++;
                            report.issues.add("Backup manifest is empty: " + backup.fileName);
                        } else {
                            report.validBackups++;
                        }
                    } catch (Exception e) {
                        String msg = e.getMessage() == null ? "" : e.getMessage();
                        if (backup.encrypted && msg.toLowerCase(java.util.Locale.US).contains("password")) {
                            report.issues.add("Backup verification requires password: " + backup.fileName);
                        } else {
                            report.invalidBackups++;
                            report.issues.add("Backup failed validation: " + backup.fileName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            report.issues.add("Could not enumerate backups for verification");
        }
    }

    private static long logBytes(Context context) {
        long total = SystemEventStore.get(context).fileBytes() + CrashDiagnosticStore.get(context).fileBytes();
        File access = SecurityPaths.accessLog(context);
        if (access.exists()) total += access.length();
        File accessRotated = new File(access.getPath() + ".1");
        if (accessRotated.exists()) total += accessRotated.length();
        File events = SecurityPaths.securityEvents(context);
        if (events.exists()) total += events.length();
        File eventsRotated = new File(events.getPath() + ".1");
        if (eventsRotated.exists()) total += eventsRotated.length();
        return total;
    }

    public static void pruneOldLogs(Context context) {
        Context app = context.getApplicationContext();
        int days = AutomationSettingsStore.get(app).get().logRetentionDays;
        long cutoff = System.currentTimeMillis() - Math.max(1, days) * 24L * 60L * 60L * 1000L;
        int removed = SystemEventStore.get(app).prune(cutoff);
        if (removed > 0) {
            SystemEventStore.get(app).info("maintenance", "Pruned " + removed + " old system event(s)");
        }
    }
}
