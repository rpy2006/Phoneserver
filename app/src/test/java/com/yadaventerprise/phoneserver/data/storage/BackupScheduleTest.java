package com.yadaventerprise.phoneserver.data.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackupScheduleTest {

    @Test
    public void firstRunSchedulesAfterOneInterval() {
        long now = 1_000_000L;
        long next = BackupSchedule.computeNextRun(0, 24, now);
        assertEquals(now + 24L * 60L * 60L * 1000L, next);
    }

    @Test
    public void nextRunUsesLastRunPlusInterval() {
        long now = 1_000_000L;
        long lastRun = now - 60_000L;
        long next = BackupSchedule.computeNextRun(lastRun, 6, now);
        assertEquals(lastRun + 6L * 60L * 60L * 1000L, next);
    }

    @Test
    public void overdueRunIsScheduledSoonInsteadOfInThePast() {
        long now = 10_000_000L;
        long next = BackupSchedule.computeNextRun(1L, 1, now);
        assertTrue(next > now);
        assertEquals(now + 60_000L, next);
    }

    @Test
    public void intervalIsClampedToSafeRange() {
        long now = 1_000_000L;
        assertEquals(now + 60L * 60L * 1000L,
                BackupSchedule.computeNextRun(0, 0, now));
        assertEquals(now + 168L * 60L * 60L * 1000L,
                BackupSchedule.computeNextRun(0, 999, now));
    }
}
