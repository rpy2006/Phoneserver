package com.yadaventerprise.phoneserver;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Fixed-window in-memory rate limiter. Keys are like "login:1.2.3.4"; nothing here is written to disk. */
final class AdRateLimiter {
    private AdRateLimiter() {}

    // value = {windowStartMs, count, windowMs}
    private static final Map<String, long[]> WINDOWS = new HashMap<>();

    /** Returns true if this call is within the limit (and counts it). */
    static synchronized boolean allow(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        long[] w = WINDOWS.get(key);
        if (w == null || now - w[0] >= windowMs) {
            w = new long[]{now, 0, windowMs};
            WINDOWS.put(key, w);
        }
        w[1]++;
        return w[1] <= limit;
    }

    static synchronized void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, long[]>> it = WINDOWS.entrySet().iterator();
        while (it.hasNext()) {
            long[] w = it.next().getValue();
            if (now - w[0] >= w[2]) it.remove();
        }
    }
}
