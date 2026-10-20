package com.yadaventerprise.phoneserver.data;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny in-memory fixed-window rate limiter. Not distributed and not shared
 * across processes - which is exactly right for a single-device local server.
 */
public final class RateLimiter {

    private static final class Window {
        long windowStart;
        int count;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowMillis;

    public RateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = Math.max(1, maxRequests);
        this.windowMillis = Math.max(1000L, windowMillis);
    }

    /** @return true when the request is allowed, false when the limit is exceeded. */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        final boolean[] allowed = new boolean[1];
        windows.compute(sanitise(key), (k, w) -> {
            if (w == null || now - w.windowStart >= windowMillis) {
                Window fresh = new Window();
                fresh.windowStart = now;
                fresh.count = 1;
                allowed[0] = true;
                return fresh;
            }
            w.count++;
            allowed[0] = w.count <= maxRequests;
            return w;
        });
        if (windows.size() > 512) pruneStale(now);
        return allowed[0];
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    private void pruneStale(long now) {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().windowStart >= windowMillis) it.remove();
        }
    }

    private static String sanitise(String key) {
        return key == null ? "unknown" : key;
    }
}
