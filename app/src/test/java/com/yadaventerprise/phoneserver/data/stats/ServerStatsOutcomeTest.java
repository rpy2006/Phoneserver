package com.yadaventerprise.phoneserver.data.stats;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class ServerStatsOutcomeTest {

    @Before
    public void resetStats() {
        ServerStats.get().reset();
    }

    @Test
    public void recordsRealRequestOutcomesAndLatency() {
        ServerStats stats = ServerStats.get();
        stats.recordRequest("10.0.0.1", "JUnit", "/api/files/list", 1, 2);
        stats.recordRequest("10.0.0.2", "JUnit", "/api/files/missing", 1, 2);
        stats.recordRequest("10.0.0.3", "JUnit", "/api/files/blocked", 1, 2);

        stats.recordOutcome(200, false, 20);
        stats.recordOutcome(404, false, 40);
        stats.recordOutcome(500, false, 60);
        stats.recordOutcome(403, true, 10);

        assertEquals(3, stats.getErrorCount());
        assertEquals(2, stats.getClientErrorCount());
        assertEquals(1, stats.getServerErrorCount());
        assertEquals(1, stats.getBlockedCount());
        assertEquals(43, stats.getAverageLatencyMillis());
        assertEquals(60, stats.getMaxLatencyMillis());
    }
}
