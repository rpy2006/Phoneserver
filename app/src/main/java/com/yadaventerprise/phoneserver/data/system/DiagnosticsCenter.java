package com.yadaventerprise.phoneserver.data.system;

import android.content.Context;
import android.os.StatFs;
import com.yadaventerprise.phoneserver.data.reliability.DiagnosticRedactor;
import com.yadaventerprise.phoneserver.data.reliability.ReliabilityCalculator;
import com.yadaventerprise.phoneserver.data.reliability.ReliabilityStatus;
import com.yadaventerprise.phoneserver.data.security.SecurityEvent;
import com.yadaventerprise.phoneserver.data.security.SecurityEventStore;
import com.yadaventerprise.phoneserver.data.stats.DeviceStats;
import com.yadaventerprise.phoneserver.data.stats.ServerStats;
import com.yadaventerprise.phoneserver.service.ServerService;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DiagnosticsCenter {

    private DiagnosticsCenter() {
    }

    public static ReliabilityStatus collectStatus(Context context) {
        Context app = context.getApplicationContext();
        ReliabilityStatus status = new ReliabilityStatus();
        status.generatedAt = System.currentTimeMillis();
        status.serverRunning = ServerService.isServerRunning();
        status.httpsRunning = ServerService.isHttpsRunning();
        status.desiredRunning = status.serverRunning;
        status.uptimeMillis = ServerService.getUptimeMillis();

        ServerStats stats = ServerStats.get();
        status.requestCount = stats.getRequestCount();
        status.errorCount = stats.getErrorCount();
        status.clientErrorCount = stats.getClientErrorCount();
        status.serverErrorCount = stats.getServerErrorCount();
        status.blockedCount = stats.getBlockedCount();
        status.averageLatencyMillis = stats.getAverageLatencyMillis();
        status.maxLatencyMillis = stats.getMaxLatencyMillis();

        status.ramUsedPercent = DeviceStats.getRamUsedPercent(app);
        status.appCpuPercent = DeviceStats.getAppCpuPercent();
        DeviceStats.NetworkReading network = DeviceStats.getNetworkReading();
        status.networkSupported = network.supported;
        status.networkRxBytes = network.rxBytes;
        status.networkTxBytes = network.txBytes;

        try {
            StatFs stat = new StatFs(app.getFilesDir().getPath());
            long total = stat.getTotalBytes();
            long available = stat.getAvailableBytes();
            status.storageTotalBytes = total;
            status.storageFreeBytes = Math.max(0, Math.min(available, total));
            status.storageUsedPercent = ReliabilityCalculator.percent(total - status.storageFreeBytes, total);
            status.storageFreePercent = ReliabilityCalculator.percent(status.storageFreeBytes, total);
        } catch (Exception ignored) {
            status.storageTotalBytes = 0;
            status.storageFreeBytes = 0;
            status.storageUsedPercent = 0;
            status.storageFreePercent = 100;
        }

        status.securityEventsLastHour = SecurityEventStore.get(app).countSince(
                System.currentTimeMillis() - 60L * 60L * 1000L);
        status.healthScore = ReliabilityCalculator.healthScore(status.serverRunning, true,
                status.ramUsedPercent, status.storageFreeBytes, status.storageTotalBytes,
                status.errorCount, status.requestCount);
        status.status = ReliabilityCalculator.status(status.healthScore, status.serverRunning);
        status.warnings.addAll(ReliabilityCalculator.warnings(status.serverRunning, true,
                status.ramUsedPercent, status.storageFreeBytes, status.storageTotalBytes,
                status.errorCount, status.requestCount));
        if (!status.networkSupported) status.warnings.add("Network usage is not available on this device");
        if (status.serverErrorCount > 0) status.warnings.add(status.serverErrorCount + " server-side request error(s)");
        if (status.blockedCount > 0) status.warnings.add(status.blockedCount + " blocked request(s)");
        return status;
    }

    public static JSONObject collect(Context context, int logLimit) throws Exception {
        Context app = context.getApplicationContext();
        ReliabilityStatus status = collectStatus(app);
        JSONObject json = new JSONObject();
        json.put("generatedAt", status.generatedAt);
        json.put("status", status.status);
        json.put("healthScore", status.healthScore);
        json.put("reliability", status.toJson());

        MaintenanceReport maintenance = SystemMaintenance.inspect(app);
        status.backupStatusAvailable = maintenance.backupsChecked > 0;
        status.validBackups = maintenance.validBackups;
        status.invalidBackups = maintenance.invalidBackups;
        json.put("maintenance", maintenance.toJson());

        JSONArray logs = new JSONArray();
        SystemLogCollector.Query query = new SystemLogCollector.Query();
        query.limit = logLimit;
        for (SystemLogCollector.SystemLogEntry entry : SystemLogCollector.collect(app, query)) {
            JSONObject entryJson = entry.toJson();
            entryJson.put("message", DiagnosticRedactor.sanitize(entryJson.optString("message", "")));
            logs.put(entryJson);
        }
        json.put("logs", logs);

        JSONArray security = new JSONArray();
        SecurityEventStore.Query securityQuery = new SecurityEventStore.Query();
        securityQuery.limit = 100;
        List<SecurityEvent> events = SecurityEventStore.get(app).read(securityQuery);
        for (SecurityEvent event : events) {
            JSONObject eventJson = event.toJson();
            eventJson.put("ip", DiagnosticRedactor.maskIp(event.ip));
            eventJson.put("detail", DiagnosticRedactor.sanitize(event.detail));
            eventJson.put("path", DiagnosticRedactor.sanitize(event.path));
            security.put(eventJson);
        }
        json.put("securityEvents", security);
        json.put("crashDiagnostics", CrashDiagnosticStore.get(app).toJson(50));
        return json;
    }
}
