package com.yadaventerprise.phoneserver.data.reliability;

import java.util.ArrayList;
import java.util.List;

public final class ReliabilityCalculator {

    private static final int MEMORY_WARNING_PERCENT = 85;
    private static final int LOW_STORAGE_WARNING_PERCENT = 10;
    private static final double ERROR_RATE_WARNING_PERCENT = 5.0;

    private ReliabilityCalculator() {
    }

    public static int percent(long part, long total) {
        if (total <= 0) return 0;
        int value = (int) ((Math.max(0, Math.min(part, total)) * 100L) / total);
        return Math.max(0, Math.min(100, value));
    }

    public static double errorRate(long errors, long requests) {
        if (requests <= 0) return 0;
        return (Math.max(0, errors) * 100.0) / requests;
    }

    public static int healthScore(boolean serverRunning, boolean storageHealthy, int ramPercent,
                                  long storageFreeBytes, long storageTotalBytes,
                                  long errorCount, long requestCount) {
        int score = 100;
        if (!serverRunning) score -= 35;
        if (!storageHealthy) score -= 25;
        if (ramPercent >= MEMORY_WARNING_PERCENT) score -= 15;
        if (storageFreePercent(storageFreeBytes, storageTotalBytes) < LOW_STORAGE_WARNING_PERCENT) score -= 15;
        if (errorRate(errorCount, requestCount) >= ERROR_RATE_WARNING_PERCENT) score -= 10;
        return Math.max(0, Math.min(100, score));
    }

    public static List<String> warnings(boolean serverRunning, boolean storageHealthy, int ramPercent,
                                        long storageFreeBytes, long storageTotalBytes,
                                        long errorCount, long requestCount) {
        List<String> warnings = new ArrayList<>();
        if (!serverRunning) warnings.add("Server listener is not running");
        if (!storageHealthy) warnings.add("Storage validation reported a problem");
        if (ramPercent >= MEMORY_WARNING_PERCENT) warnings.add("Memory use is " + ramPercent + "%");
        int freePercent = storageFreePercent(storageFreeBytes, storageTotalBytes);
        if (freePercent < LOW_STORAGE_WARNING_PERCENT) warnings.add("Free storage is " + freePercent + "%");
        double rate = errorRate(errorCount, requestCount);
        if (rate >= ERROR_RATE_WARNING_PERCENT) {
            warnings.add("HTTP error rate is " + String.format(java.util.Locale.US, "%.1f%%", rate));
        }
        return warnings;
    }

    public static String status(int score, boolean serverRunning) {
        if (!serverRunning) return "offline";
        if (score >= 85) return "healthy";
        if (score >= 60) return "degraded";
        return "critical";
    }

    private static int storageFreePercent(long freeBytes, long totalBytes) {
        if (totalBytes <= 0) return 100;
        return percent(freeBytes, totalBytes);
    }
}
