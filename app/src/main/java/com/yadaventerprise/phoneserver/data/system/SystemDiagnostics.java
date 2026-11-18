package com.yadaventerprise.phoneserver.data.system;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.stats.DeviceStats;
import com.yadaventerprise.phoneserver.data.stats.ServerStats;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.util.NetworkUtils;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SystemDiagnostics {

    private SystemDiagnostics() {
    }

    public static JSONObject collect(Context context) throws Exception {
        Context app = context.getApplicationContext();
        AutomationSettings automation = AutomationSettingsStore.get(app).get();
        JSONObject json = new JSONObject();
        json.put("generatedAt", System.currentTimeMillis());
        json.put("apiLevel", android.os.Build.VERSION.SDK_INT);

        JSONObject server = new JSONObject();
        boolean running = ServerService.isServerRunning();
        server.put("running", running);
        server.put("httpsRunning", ServerService.isHttpsRunning());
        server.put("port", ServerService.PORT);
        server.put("uptimeMillis", Math.max(0, ServerService.getUptimeMillis()));
        server.put("desiredRunning", ContentStore.getServerDesiredRunning(app));
        server.put("requestCount", ServerStats.get().getRequestCount());
        server.put("activeClients", ServerStats.get().getActiveClientCount());
        server.put("bytesUp", ServerStats.get().getBytesUp());
        server.put("bytesDown", ServerStats.get().getBytesDown());
        String ip = NetworkUtils.getLocalIpAddress();
        server.put("localIp", ip == null ? JSONObject.NULL : ip);
        json.put("server", server);

        HttpsConfig https = HttpsConfigStore.get(app).get();
        JSONObject security = new JSONObject();
        security.put("passwordConfigured", !ContentStore.getPassword(app).isEmpty());
        security.put("httpsEnabled", https.enabled);
        security.put("httpsPort", https.port);
        security.put("remoteAccessEnabled", ContentStore.getAllowRemoteAccess(app));
        json.put("security", security);

        JSONObject automationJson = automation.toJson();
        automationJson.put("startOnBoot", ContentStore.getStartOnBoot(app));
        json.put("automation", automationJson);

        JSONObject resources = new JSONObject();
        resources.put("ramUsedPercent", DeviceStats.getRamUsedPercent(app));
        resources.put("appCpuPercent", DeviceStats.getAppCpuPercent());
        File root = ContentStore.getDataRoot(app);
        long total = root.getTotalSpace();
        long free = root.getUsableSpace();
        long used = Math.max(0, total - free);
        resources.put("storageTotalBytes", total);
        resources.put("storageFreeBytes", free);
        resources.put("storageUsedBytes", used);
        resources.put("storageUsedPercent", total > 0 ? (int) ((used * 100L) / total) : 0);
        resources.put("storageWarningThresholdBytes",
                Math.max(10, automation.storageWarningMb) * 1024L * 1024L);
        json.put("resources", resources);

        JSONObject content = new JSONObject();
        try {
            com.yadaventerprise.phoneserver.data.storage.ProjectStore.GlobalStats stats =
                    com.yadaventerprise.phoneserver.data.storage.ProjectStore.globalStats(app);
            content.put("available", true);
            content.put("projects", stats.projectCount);
            content.put("databases", stats.databaseCount);
            content.put("files", stats.fileCount);
        } catch (Exception e) {
            content.put("available", false);
        }
        int taskCount = 0;
        int enabledTasks = 0;
        int failedTasks = 0;
        for (ScheduledTask task : ScheduledTaskStore.get(app).all()) {
            taskCount++;
            if (task.enabled) enabledTasks++;
            if ("failed".equals(task.lastStatus)) failedTasks++;
        }
        content.put("tasks", taskCount);
        content.put("enabledTasks", enabledTasks);
        content.put("failedTasks", failedTasks);
        int hookCount = 0;
        int enabledHooks = 0;
        for (WebhookConfig hook : WebhookStore.get(app).all()) {
            hookCount++;
            if (hook.enabled) enabledHooks++;
        }
        content.put("webhooks", hookCount);
        content.put("enabledWebhooks", enabledHooks);
        json.put("content", content);

        JSONArray checks = new JSONArray();
        checks.put(check("Server running", running,
                running ? "The local HTTP or HTTPS listener is active." : "No listener is active."));
        checks.put(check("Access credential", !ContentStore.getPassword(app).isEmpty(),
                ContentStore.getPassword(app).isEmpty()
                        ? "Set an access password or use API tokens."
                        : "An access password is configured."));
        checks.put(check("Local network address", ip != null,
                ip == null ? "No local IP address was detected." : "Detected " + ip + "."));
        checks.put(check("Storage available", free > 0,
                ServerStats.formatBytes(free) + " usable on the data volume."));
        checks.put(check("No failed scheduled tasks", failedTasks == 0,
                failedTasks == 0 ? "No task is currently marked failed."
                        : failedTasks + " task(s) are marked failed."));
        json.put("checks", checks);
        return json;
    }

    private static JSONObject check(String name, boolean ok, String detail) throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("ok", ok);
        json.put("detail", detail);
        return json;
    }
}
