package com.yadaventerprise.phoneserver.data.developer;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ApiUsageStore {

    private static final int MAX_ENTRIES = 2000;
    private static volatile ApiUsageStore instance;
    private final File file;
    private final Object lock = new Object();
    private List<ApiUsageEntry> cached;

    private ApiUsageStore(File file) { this.file = file; }

    public static ApiUsageStore get(Context context) {
        if (instance == null) {
            synchronized (ApiUsageStore.class) {
                if (instance == null) {
                    File dir = DeveloperPaths.developerDir(context);
                    instance = new ApiUsageStore(new File(dir, "api-usage.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() { instance = null; }

    public void append(ApiUsageEntry entry) {
        if (entry == null) return;
        synchronized (lock) {
            ensureLoaded();
            cached.add(0, entry);
            while (cached.size() > MAX_ENTRIES) cached.remove(cached.size() - 1);
            persist();
        }
    }

    public List<ApiUsageEntry> list(int limit) {
        synchronized (lock) {
            ensureLoaded();
            int n = Math.max(1, Math.min(limit <= 0 ? 100 : limit, MAX_ENTRIES));
            if (cached.size() <= n) return new ArrayList<>(cached);
            return new ArrayList<>(cached.subList(0, n));
        }
    }

    public JSONObject summary(long sinceMs) {
        synchronized (lock) {
            ensureLoaded();
            long now = System.currentTimeMillis();
            long since = sinceMs > 0 ? sinceMs : now - 24L * 60L * 60L * 1000L;
            int total = 0, success = 0, failed = 0, rateLimited = 0;
            long duration = 0;
            Map<String, Integer> byKey = new HashMap<>();
            Map<String, Integer> byEndpoint = new HashMap<>();
            Map<String, Integer> byStatus = new HashMap<>();
            for (ApiUsageEntry e : cached) {
                if (e.time < since) continue;
                if ("/__quota__/reserve".equals(e.path)) continue;
                total++;
                if (e.status >= 200 && e.status < 400) success++; else failed++;
                if (e.rateLimited || e.status == 429) rateLimited++;
                duration += Math.max(0, e.durationMs);
                String key = e.tokenId == null || e.tokenId.isEmpty() ? (e.tokenName == null || e.tokenName.isEmpty() ? e.keyPrefix : e.tokenName) : e.tokenId;
                if (key == null || key.isEmpty()) key = "unknown";
                byKey.put(key, byKey.getOrDefault(key, 0) + 1);
                String endpoint = (e.method == null ? "" : e.method) + " " + normalizePath(e.path);
                byEndpoint.put(endpoint, byEndpoint.getOrDefault(endpoint, 0) + 1);
                String status = String.valueOf(e.status);
                byStatus.put(status, byStatus.getOrDefault(status, 0) + 1);
            }
            JSONObject json = new JSONObject();
            try {
                json.put("since", since);
                json.put("totalRequests", total);
                json.put("successRequests", success);
                json.put("failedRequests", failed);
                json.put("rateLimitViolations", rateLimited);
                json.put("averageResponseMs", total == 0 ? 0 : duration / total);
                json.put("usageByKey", toArray(byKey, "key", "count"));
                json.put("topEndpoints", toArray(byEndpoint, "endpoint", "count"));
                json.put("statusDistribution", toArray(byStatus, "status", "count"));
            } catch (Exception ignored) {}
            return json;
        }
    }

    public long countForTokenSince(String tokenId, long sinceMs) {
        if (tokenId == null || tokenId.isEmpty()) return 0;
        synchronized (lock) {
            ensureLoaded();
            long count = 0;
            for (ApiUsageEntry e : cached) {
                if (e.time >= sinceMs && tokenId.equals(e.tokenId)) count++;
            }
            return count;
        }
    }

    public long countForTokenInUtcDay(String tokenId, long nowMs) {
        return countForTokenSince(tokenId, utcDayStart(nowMs));
    }

    public long countForTokenInUtcMonth(String tokenId, long nowMs) {
        return countForTokenSince(tokenId, utcMonthStart(nowMs));
    }

    public boolean tryConsumeQuota(String tokenId, long nowMs, long dailyLimit, long monthlyLimit) {
        if (tokenId == null || tokenId.isEmpty()) return false;
        synchronized (lock) {
            ensureLoaded();
            cleanupStaleReservations(nowMs);
            long dayStart = utcDayStart(nowMs);
            long monthStart = utcMonthStart(nowMs);
            long day = 0, month = 0;
            for (ApiUsageEntry e : cached) {
                if (!tokenId.equals(e.tokenId)) continue;
                if (e.time >= dayStart) day++;
                if (e.time >= monthStart) month++;
            }
            if (day >= dailyLimit || month >= monthlyLimit) return false;
            ApiUsageEntry reservation = new ApiUsageEntry();
            reservation.time = nowMs;
            reservation.tokenId = tokenId;
            reservation.method = "QUOTA";
            reservation.path = "/__quota__/reserve";
            reservation.status = 0;
            reservation.rateLimited = false;
            cached.add(0, reservation);
            while (cached.size() > MAX_ENTRIES) cached.remove(cached.size() - 1);
            persist();
            return true;
        }
    }

    public void replaceReservation(String tokenId, ApiUsageEntry actual) {
        if (actual == null) return;
        synchronized (lock) {
            ensureLoaded();
            cleanupStaleReservations(actual.time > 0 ? actual.time : System.currentTimeMillis());
            boolean replaced = false;
            for (int i = 0; i < cached.size(); i++) {
                ApiUsageEntry e = cached.get(i);
                if (tokenId != null && tokenId.equals(e.tokenId) && "/__quota__/reserve".equals(e.path)) {
                    cached.set(i, actual);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                cached.add(0, actual);
                while (cached.size() > MAX_ENTRIES) cached.remove(cached.size() - 1);
            }
            persist();
        }
    }

    public int cleanupStaleReservations(long nowMs) {
        synchronized (lock) {
            ensureLoaded();
            long cutoff = nowMs - 5L * 60L * 1000L;
            int removed = 0;
            for (int i = cached.size() - 1; i >= 0; i--) {
                ApiUsageEntry e = cached.get(i);
                if ("/__quota__/reserve".equals(e.path) && e.time < cutoff) {
                    cached.remove(i);
                    removed++;
                }
            }
            if (removed > 0) persist();
            return removed;
        }
    }

    public static long utcDayStart(long nowMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(nowMs);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public static long utcMonthStart(long nowMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(nowMs);
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static JSONArray toArray(Map<String, Integer> map, String k, String v) throws Exception {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });
        JSONArray arr = new JSONArray();
        int max = Math.min(list.size(), 20);
        for (int i = 0; i < max; i++) {
            JSONObject o = new JSONObject();
            o.put(k, list.get(i).getKey());
            o.put(v, list.get(i).getValue());
            arr.put(o);
        }
        return arr;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append('/');
            if (part.matches("[0-9a-fA-F-]{8,}") || part.matches("\\d+")) sb.append("{id}");
            else sb.append(part);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private void ensureLoaded() {
        if (cached != null) return;
        cached = new ArrayList<>();
        try {
            JSONArray arr = JsonStorage.readJsonArray(file);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) cached.add(ApiUsageEntry.fromJson(o));
            }
        } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        long cutoff = now - 5L * 60L * 1000L;
        boolean changed = false;
        for (int i = cached.size() - 1; i >= 0; i--) {
            ApiUsageEntry e = cached.get(i);
            if ("/__quota__/reserve".equals(e.path) && e.time < cutoff) {
                cached.remove(i);
                changed = true;
            }
        }
        if (changed) {
            try { persist(); } catch (Exception ignored) {}
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (ApiUsageEntry e : cached) arr.put(e.toJson());
            JsonStorage.writeJsonArrayAtomic(file, arr, true);
            SecurityPaths.restrict(file);
        } catch (Exception ignored) {}
    }
}
