package com.yadaventerprise.phoneserver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks real usage of this device's server: bytes transferred, requests per
 * endpoint, which clients have connected, and a short recent-activity log.
 * Everything here is genuinely measured from live requests - nothing is
 * simulated or randomly generated.
 */
public class ServerStats {

    private static final ServerStats INSTANCE = new ServerStats();
    public static ServerStats get() {
        return INSTANCE;
    }

    public static class ClientInfo {
        public String ip;
        public String os;
        public String browser;
        public long lastSeenMillis;
    }

    public static class ActivityEvent {
        public long timeMillis;
        public String type;    // "start", "client", "upload", "api", "delete"
        public String message;

        public ActivityEvent(String type, String message) {
            this.timeMillis = System.currentTimeMillis();
            this.type = type;
            this.message = message;
        }
    }

    private final AtomicLong bytesUp = new AtomicLong(0);   // received from clients (uploads, request bodies)
    private final AtomicLong bytesDown = new AtomicLong(0); // sent to clients (downloads, response bodies)
    private final AtomicLong requestCount = new AtomicLong(0);

    private final Map<String, Integer> pathCounts = new ConcurrentHashMap<>();
    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private final Deque<ActivityEvent> recentActivity = new ArrayDeque<>();
    private static final int MAX_ACTIVITY = 30;
    private static final long CLIENT_ACTIVE_WINDOW_MILLIS = 5 * 60 * 1000; // 5 minutes

    private ServerStats() {
    }

    public void reset() {
        bytesUp.set(0);
        bytesDown.set(0);
        requestCount.set(0);
        pathCounts.clear();
        clients.clear();
        synchronized (recentActivity) {
            recentActivity.clear();
        }
    }

    public void recordRequest(String ip, String userAgent, String path, int requestBodyBytes, int responseBodyBytes) {
        bytesUp.addAndGet(requestBodyBytes);
        bytesDown.addAndGet(responseBodyBytes);
        requestCount.incrementAndGet();

        String bucket = bucketPath(path);
        pathCounts.merge(bucket, 1, Integer::sum);

        boolean isNewClient = !clients.containsKey(ip);
        ClientInfo info = clients.computeIfAbsent(ip, k -> {
            ClientInfo c = new ClientInfo();
            c.ip = ip;
            String[] parsed = parseUserAgent(userAgent);
            c.os = parsed[0];
            c.browser = parsed[1];
            return c;
        });
        info.lastSeenMillis = System.currentTimeMillis();

        if (isNewClient) {
            logActivity("client", "New client connected: " + ip);
        }
    }

    public void logUpload(String filename, int sizeBytes) {
        logActivity("upload", "File uploaded: " + filename + " (" + formatBytes(sizeBytes) + ")");
    }

    public void logServerStarted() {
        logActivity("start", "Server started");
    }

    public void logActivity(String type, String message) {
        ActivityEvent event = new ActivityEvent(type, message);
        synchronized (recentActivity) {
            recentActivity.addFirst(event);
            while (recentActivity.size() > MAX_ACTIVITY) {
                recentActivity.removeLast();
            }
        }
    }

    private String bucketPath(String path) {
        if (path == null || path.isEmpty()) return "/";
        // group /files/<anything> together, otherwise use the path as-is
        if (path.startsWith("/files/")) return "/files/*";
        return path;
    }

    /** Very lightweight User-Agent parsing - just enough to show a friendly OS/browser guess. */
    private String[] parseUserAgent(String ua) {
        String os = "Unknown";
        String browser = "Unknown";
        if (ua != null) {
            String lower = ua.toLowerCase(Locale.US);
            if (lower.contains("android")) os = "Android";
            else if (lower.contains("windows")) os = "Windows";
            else if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) os = "iOS";
            else if (lower.contains("mac os")) os = "macOS";
            else if (lower.contains("linux")) os = "Linux";
            else if (lower.contains("phoneserver")) os = "PhoneServer App";

            if (lower.contains("edg")) browser = "Edge";
            else if (lower.contains("chrome")) browser = "Chrome";
            else if (lower.contains("firefox")) browser = "Firefox";
            else if (lower.contains("safari")) browser = "Safari";
            else if (lower.contains("phoneserver")) browser = "App";
        }
        return new String[]{os, browser};
    }

    public long getBytesUp() {
        return bytesUp.get();
    }

    public long getBytesDown() {
        return bytesDown.get();
    }

    public long getRequestCount() {
        return requestCount.get();
    }

    public int getActiveClientCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (ClientInfo c : clients.values()) {
            if (now - c.lastSeenMillis <= CLIENT_ACTIVE_WINDOW_MILLIS) count++;
        }
        return count;
    }

    public List<ClientInfo> getClients() {
        List<ClientInfo> list = new ArrayList<>(clients.values());
        list.sort((a, b) -> Long.compare(b.lastSeenMillis, a.lastSeenMillis));
        return list;
    }

    public List<Map.Entry<String, Integer>> getTopEndpoints(int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(pathCounts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (entries.size() > limit) entries = entries.subList(0, limit);
        return entries;
    }

    public List<ActivityEvent> getRecentActivity() {
        synchronized (recentActivity) {
            return new ArrayList<>(recentActivity);
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
