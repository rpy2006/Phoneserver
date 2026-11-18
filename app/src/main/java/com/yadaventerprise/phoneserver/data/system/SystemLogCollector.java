package com.yadaventerprise.phoneserver.data.system;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.AccessLogEntry;
import com.yadaventerprise.phoneserver.data.security.AccessLogStore;
import com.yadaventerprise.phoneserver.data.security.SecurityEvent;
import com.yadaventerprise.phoneserver.data.security.SecurityEventStore;
import com.yadaventerprise.phoneserver.data.stats.ServerStats;
import com.yadaventerprise.phoneserver.data.tasks.TaskHistoryEntry;
import com.yadaventerprise.phoneserver.data.tasks.TaskHistoryStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryEntry;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

public final class SystemLogCollector {

    private SystemLogCollector() {
    }

    public static List<SystemLogEntry> collect(Context context, Query query) {
        Context app = context.getApplicationContext();
        Query q = query == null ? new Query() : query;
        List<SystemLogEntry> entries = new ArrayList<>();
        if (q.matchesSource("system")) {
            SystemEventStore.Query systemQuery = new SystemEventStore.Query();
            systemQuery.limit = q.limit <= 0 ? 200 : q.limit;
            for (SystemEvent event : SystemEventStore.get(app).read(systemQuery)) {
                entries.add(new SystemLogEntry(event.time, "system", event.level, event.message));
            }
        }
        if (q.matchesSource("access")) {
            AccessLogStore.Query accessQuery = new AccessLogStore.Query();
            accessQuery.limit = q.limit <= 0 ? 200 : q.limit;
            for (AccessLogEntry entry : AccessLogStore.get(app).read(accessQuery)) {
                String level = entry.status >= 500 ? SystemEvent.LEVEL_ERROR
                        : entry.status >= 400 ? SystemEvent.LEVEL_WARN : SystemEvent.LEVEL_INFO;
                String message = entry.method + " " + entry.path + " -> " + entry.status
                        + " from " + entry.ip + " as " + entry.actor
                        + " (" + ServerStats.formatBytes(entry.bytesIn) + " in, "
                        + ServerStats.formatBytes(entry.bytesOut) + " out, "
                        + entry.durationMs + " ms)";
                if (entry.blocked) message += " [blocked]";
                entries.add(new SystemLogEntry(entry.time, "access", level, message));
            }
        }
        if (q.matchesSource("security")) {
            SecurityEventStore.Query securityQuery = new SecurityEventStore.Query();
            securityQuery.limit = q.limit <= 0 ? 200 : q.limit;
            for (SecurityEvent event : SecurityEventStore.get(app).read(securityQuery)) {
                String level = SecurityEvent.SEVERITY_HIGH.equals(event.severity)
                        ? SystemEvent.LEVEL_ERROR
                        : SecurityEvent.SEVERITY_MEDIUM.equals(event.severity)
                                ? SystemEvent.LEVEL_WARN : SystemEvent.LEVEL_INFO;
                String message = event.type + (event.detail.isEmpty() ? "" : " · " + event.detail)
                        + (event.ip.isEmpty() ? "" : " · " + event.ip)
                        + (event.path.isEmpty() ? "" : " · " + event.method + " " + event.path);
                entries.add(new SystemLogEntry(event.time, "security", level, message));
            }
        }
        if (q.matchesSource("webhooks")) {
            for (WebhookHistoryEntry entry : WebhookHistoryStore.get(app).list()) {
                String message = entry.event + " · " + entry.webhookName
                        + (entry.success ? " delivered" : " failed")
                        + (entry.responseCode > 0 ? " · HTTP " + entry.responseCode : "")
                        + (entry.error.isEmpty() ? "" : " · " + entry.error);
                entries.add(new SystemLogEntry(entry.createdAt, "webhooks",
                        entry.success ? SystemEvent.LEVEL_INFO : SystemEvent.LEVEL_WARN, message));
            }
        }
        if (q.matchesSource("tasks")) {
            for (TaskHistoryEntry entry : TaskHistoryStore.get(app).list()) {
                String level = entry.success ? SystemEvent.LEVEL_INFO
                        : entry.retryScheduled ? SystemEvent.LEVEL_WARN : SystemEvent.LEVEL_ERROR;
                String message = entry.taskName + " · attempt " + entry.attempt + "/" + entry.maxAttempts
                        + (entry.retryScheduled ? " · retry scheduled" : "")
                        + (entry.error.isEmpty() ? "" : " · " + entry.error);
                entries.add(new SystemLogEntry(entry.time, "tasks", level, message));
            }
        }

        Collections.sort(entries, (a, b) -> Long.compare(b.time, a.time));
        List<SystemLogEntry> result = new ArrayList<>();
        for (SystemLogEntry entry : entries) {
            if (!q.matches(entry)) continue;
            result.add(entry);
            if (q.limit > 0 && result.size() >= q.limit) break;
        }
        return result;
    }

    public static class SystemLogEntry {
        public final long time;
        public final String source;
        public final String level;
        public final String message;

        SystemLogEntry(long time, String source, String level, String message) {
            this.time = time;
            this.source = source;
            this.level = SystemEvent.normalizeLevel(level);
            this.message = SystemLogSanitizer.sanitize(message);
        }

        public JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("time", time);
            json.put("source", source);
            json.put("level", level);
            json.put("message", message);
            return json;
        }
    }

    public static class Query {
        public String source = "";
        public String level = "";
        public String search = "";
        public int limit = 200;

        boolean matchesSource(String candidate) {
            return source == null || source.trim().isEmpty() || source.equalsIgnoreCase(candidate);
        }

        boolean matches(SystemLogEntry entry) {
            if (entry == null) return false;
            if (level != null && !level.trim().isEmpty() && !level.equalsIgnoreCase(entry.level)) return false;
            if (search != null && !search.trim().isEmpty()) {
                String needle = search.trim().toLowerCase();
                String haystack = (entry.source + " " + entry.level + " " + entry.message).toLowerCase();
                if (!haystack.contains(needle)) return false;
            }
            return true;
        }
    }
}
