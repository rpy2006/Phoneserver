package com.yadaventerprise.phoneserver.data.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ScheduledTaskTest {

    @Test
    public void roundTripsTaskSettings() throws Exception {
        ScheduledTask task = new ScheduledTask();
        task.id = "task-1";
        task.name = "Nightly";
        task.action = ScheduledTask.ACTION_BACKUP;
        task.projectId = "project-1";
        task.intervalMinutes = 60;
        task.nextRunAt = 456;
        task.lastStatus = "success";

        ScheduledTask copy = ScheduledTask.fromJson(new JSONObject(task.toJson().toString()));
        assertEquals(task.id, copy.id);
        assertEquals(task.projectId, copy.projectId);
        assertEquals(60, copy.intervalMinutes);
        assertEquals(456, copy.nextRunAt);
        assertTrue(copy.isBackup());
    }

    @Test
    public void roundTripsOneTimeRetrySettings() throws Exception {
        ScheduledTask task = new ScheduledTask();
        task.name = "One shot";
        task.action = ScheduledTask.ACTION_WEBHOOK;
        task.webhookId = "hook-1";
        task.scheduleType = ScheduledTask.SCHEDULE_ONCE;
        task.runAt = 1_700_000_000_000L;
        task.maxAttempts = 4;
        task.retryDelayMinutes = 15;
        task.currentAttempt = 2;

        ScheduledTask copy = ScheduledTask.fromJson(new JSONObject(task.toJson().toString()));
        assertTrue(copy.isOneTime());
        assertEquals(task.runAt, copy.runAt);
        assertEquals(4, copy.maxAttempts);
        assertEquals(15, copy.retryDelayMinutes);
        assertEquals(task.runAt, copy.computeNextRunAt(1234));
    }

    @Test
    public void clampsInvalidIntervals() throws Exception {
        ScheduledTask task = ScheduledTask.fromJson(new JSONObject().put("intervalMinutes", 0));
        assertEquals(1, task.intervalMinutes);
        task = ScheduledTask.fromJson(new JSONObject());
        assertEquals(1440, task.intervalMinutes);
    }
}
