package com.yadaventerprise.phoneserver.data.security;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import java.net.URLDecoder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * Lightweight, in-memory intrusion detection. Counters reset with the process,
 * while events and blocks are persisted by their stores.
 */
public final class IntrusionDetector {

    private static final long RATE_LIMIT_EVENT_INTERVAL_MS = 60_000L;
    private static final Map<String, Deque<Long>> authFailures = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Long>> suspiciousHits = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastEventAt = new ConcurrentHashMap<>();

    private IntrusionDetector() {
    }

    /** @return true when this failure caused the IP to be blocked. */
    public static boolean recordAuthFailure(Context context, String ip, String method, String path,
                                            String detail) {
        if (ip == null || IpBlockStore.isLoopback(ip)) return false;
        SecuritySettings settings = SecuritySettingsStore.get(context).get();
        int count = countWithin(authFailures, ip, settings.failedLoginWindowMinutes * 60_000L);
        if (count < settings.failedLoginThreshold) return false;

        boolean autoBlocked = settings.autoBlockEnabled;
        if (autoBlocked) {
            IpBlockStore.get(context).block(ip, "Repeated failed authentication", 
                    settings.blockDurationMinutes * 60_000L, "auto");
        }
        SecurityEvent event = event(SecurityEvent.TYPE_AUTH_FAILURE, SecurityEvent.SEVERITY_HIGH,
                ip, method, path, (detail == null ? "failed authentication" : detail)
                        + " (" + count + " failures in " + settings.failedLoginWindowMinutes + " min)");
        event.autoBlocked = autoBlocked;
        SecurityEventStore.get(context).append(event);
        JSONObject hookData = new JSONObject();
        try {
            hookData.put("ip", ip);
            hookData.put("method", method);
            hookData.put("path", path);
            hookData.put("detail", detail);
            hookData.put("count", count);
            hookData.put("blocked", autoBlocked);
        } catch (Exception ignored) {
        }
        WebhookDispatcher.emit(context, WebhookDispatcher.EVENT_AUTH_FAILURE, hookData);
        authFailures.remove(ip);
        return autoBlocked;
    }

    public static void recordSuspicious(Context context, String ip, String method, String path,
                                        String detail) {
        if (ip == null || IpBlockStore.isLoopback(ip)) return;
        SecuritySettings settings = SecuritySettingsStore.get(context).get();
        int count = countWithin(suspiciousHits, ip, settings.failedLoginWindowMinutes * 60_000L);
        boolean autoBlocked = false;
        if (count >= settings.suspiciousPathThreshold && settings.autoBlockEnabled) {
            IpBlockStore.get(context).block(ip, "Repeated suspicious requests",
                    settings.blockDurationMinutes * 60_000L, "auto");
            autoBlocked = true;
            suspiciousHits.remove(ip);
        }
        SecurityEvent event = event(SecurityEvent.TYPE_SUSPICIOUS_PATH,
                autoBlocked ? SecurityEvent.SEVERITY_HIGH : SecurityEvent.SEVERITY_MEDIUM,
                ip, method, path, detail == null ? "suspicious request path" : detail);
        event.autoBlocked = autoBlocked;
        SecurityEventStore.get(context).append(event);
        JSONObject hookData = new JSONObject();
        try {
            hookData.put("ip", ip);
            hookData.put("method", method);
            hookData.put("path", path);
            hookData.put("detail", detail);
            hookData.put("blocked", autoBlocked);
        } catch (Exception ignored) {
        }
        WebhookDispatcher.emit(context, WebhookDispatcher.EVENT_SUSPICIOUS, hookData);
    }

    public static void recordRateLimit(Context context, String ip, String method, String path) {
        if (ip == null || IpBlockStore.isLoopback(ip)) return;
        if (!allowEvent(ip)) return;
        SecurityEventStore.get(context).append(event(SecurityEvent.TYPE_RATE_LIMIT,
                SecurityEvent.SEVERITY_LOW, ip, method, path, "rate limit exceeded"));
    }

    public static void recordBlockedRequest(Context context, String ip, String method, String path) {
        if (ip == null || IpBlockStore.isLoopback(ip)) return;
        if (!allowEvent(ip)) return;
        SecurityEventStore.get(context).append(event(SecurityEvent.TYPE_BLOCKED_REQUEST,
                SecurityEvent.SEVERITY_LOW, ip, method, path, "request from blocked IP"));
    }

    private static boolean allowEvent(String ip) {
        long now = System.currentTimeMillis();
        Long previous = lastEventAt.get(ip);
        if (previous != null && now - previous < RATE_LIMIT_EVENT_INTERVAL_MS) return false;
        lastEventAt.put(ip, now);
        return true;
    }

    private static int countWithin(Map<String, Deque<Long>> map, String ip, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> times = map.computeIfAbsent(ip, key -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > windowMs) times.removeFirst();
            times.addLast(now);
            return times.size();
        }
    }

    private static SecurityEvent event(String type, String severity, String ip, String method,
                                       String path, String detail) {
        SecurityEvent event = new SecurityEvent();
        event.time = System.currentTimeMillis();
        event.type = type;
        event.severity = severity;
        event.ip = ip == null ? "" : ip;
        event.method = method == null ? "" : method;
        event.path = path == null ? "" : path;
        event.detail = detail == null ? "" : detail;
        return event;
    }

    /** True for paths that are never used by this server's real UI/API and indicate probing. */
    public static boolean isSuspiciousPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return false;
        if (rawPath.length() > 2048) return true;
        String lower = rawPath.toLowerCase(Locale.US);
        String decoded;
        try {
            decoded = URLDecoder.decode(lower, "UTF-8");
        } catch (Exception e) {
            decoded = lower;
        }
        String value = decoded.replace('\\', '/');
        if (value.contains("\0") || value.contains("%00")) return true;
        if (value.contains("/../") || value.startsWith("../") || value.endsWith("/..")
                || value.equals("..")) return true;
        for (String segment : value.split("/")) {
            if (segment.equals("..")) return true;
        }
        String[] probes = {
                "/.env", "/.git", "/etc/passwd", "/proc/self", "/wp-admin", "/wp-login",
                "/phpmyadmin", "/.ssh", "/config.php", "union select", "union%20select",
                "<script", "javascript:", "cmd.exe", "%2e%2e", "..%2f"
        };
        for (String probe : probes) {
            if (value.contains(probe)) return true;
        }
        return false;
    }

    public static synchronized void resetForTests() {
        authFailures.clear();
        suspiciousHits.clear();
        lastEventAt.clear();
    }
}
