package com.yadaventerprise.phoneserver.data.reliability;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReliabilityStatus {

    public long generatedAt;
    public String status = "offline";
    public int healthScore;
    public boolean serverRunning;
    public boolean httpsRunning;
    public boolean desiredRunning;
    public long uptimeMillis;
    public long requestCount;
    public long errorCount;
    public long clientErrorCount;
    public long serverErrorCount;
    public long blockedCount;
    public long averageLatencyMillis;
    public long maxLatencyMillis;
    public int ramUsedPercent;
    public int appCpuPercent;
    public long storageTotalBytes;
    public long storageFreeBytes;
    public int storageUsedPercent;
    public int storageFreePercent;
    public boolean networkSupported;
    public long networkRxBytes;
    public long networkTxBytes;
    public boolean backupStatusAvailable;
    public int validBackups;
    public int invalidBackups;
    public int securityEventsLastHour;
    public final List<String> warnings = new ArrayList<>();

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("generatedAt", generatedAt);
        json.put("status", status);
        json.put("healthScore", healthScore);
        json.put("serverRunning", serverRunning);
        json.put("httpsRunning", httpsRunning);
        json.put("desiredRunning", desiredRunning);
        json.put("uptimeMillis", uptimeMillis);

        JSONObject requests = new JSONObject();
        requests.put("total", requestCount);
        requests.put("errors", errorCount);
        requests.put("clientErrors", clientErrorCount);
        requests.put("serverErrors", serverErrorCount);
        requests.put("blocked", blockedCount);
        requests.put("averageLatencyMillis", averageLatencyMillis);
        requests.put("maxLatencyMillis", maxLatencyMillis);
        json.put("requests", requests);

        JSONObject resources = new JSONObject();
        resources.put("ramUsedPercent", ramUsedPercent);
        resources.put("appCpuPercent", appCpuPercent);
        resources.put("storageTotalBytes", storageTotalBytes);
        resources.put("storageFreeBytes", storageFreeBytes);
        resources.put("storageUsedPercent", storageUsedPercent);
        resources.put("storageFreePercent", storageFreePercent);
        resources.put("networkSupported", networkSupported);
        resources.put("networkRxBytes", networkRxBytes);
        resources.put("networkTxBytes", networkTxBytes);
        json.put("resources", resources);

        JSONObject backup = new JSONObject();
        backup.put("available", backupStatusAvailable);
        backup.put("valid", validBackups);
        backup.put("invalid", invalidBackups);
        json.put("backup", backup);

        JSONObject security = new JSONObject();
        security.put("eventsLastHour", securityEventsLastHour);
        json.put("security", security);

        JSONArray warningsJson = new JSONArray();
        for (String warning : warnings) warningsJson.put(warning);
        json.put("warnings", warningsJson);
        return json;
    }
}
