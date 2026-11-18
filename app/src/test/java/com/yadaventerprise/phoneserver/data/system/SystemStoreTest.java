package com.yadaventerprise.phoneserver.data.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.tasks.TaskHistoryEntry;
import com.yadaventerprise.phoneserver.data.tasks.TaskHistoryStore;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SystemStoreTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        AutomationSettingsStore.reset();
        SystemEventStore.reset();
        TaskHistoryStore.reset();
        AutomationSettingsStore.get(context).save(new AutomationSettings());
        SystemEventStore.get(context).clear();
        TaskHistoryStore.get(context).clear();
    }

    @Test
    public void automationSettingsRoundTripThroughStore() {
        AutomationSettings settings = AutomationSettingsStore.get(context).get();
        settings.autoRestartEnabled = false;
        settings.watchdogIntervalMinutes = 17;
        settings.notifyTaskResults = false;
        settings.storageWarningMb = 2048;
        settings.logRetentionDays = 7;
        AutomationSettingsStore.get(context).save(settings);

        AutomationSettingsStore.reset();
        AutomationSettings copy = AutomationSettingsStore.get(context).get();
        assertFalse(copy.autoRestartEnabled);
        assertEquals(17, copy.watchdogIntervalMinutes);
        assertFalse(copy.notifyTaskResults);
        assertEquals(2048, copy.storageWarningMb);
        assertEquals(7, copy.logRetentionDays);
    }

    @Test
    public void systemEventsAreSanitizedOrderedAndPrunable() {
        SystemEventStore store = SystemEventStore.get(context);
        store.info("server", "started");
        store.warn("task", "retry with token=abc123");
        store.error("server", "failed");

        List<SystemEvent> events = store.read(new SystemEventStore.Query());
        assertEquals(3, events.size());
        assertEquals("failed", events.get(0).message);
        assertFalse(events.get(1).message.contains("abc123"));
        assertTrue(events.get(1).message.contains("[redacted]"));

        int removed = store.prune(System.currentTimeMillis() + 1000);
        assertEquals(3, removed);
        assertTrue(store.read(new SystemEventStore.Query()).isEmpty());
    }

    @Test
    public void taskHistoryIsBounded() {
        TaskHistoryStore store = TaskHistoryStore.get(context);
        for (int i = 0; i < 205; i++) {
            TaskHistoryEntry entry = new TaskHistoryEntry();
            entry.taskId = "task-" + i;
            entry.taskName = "Task " + i;
            entry.time = i;
            store.append(entry);
        }
        assertEquals(200, store.list().size());
        assertEquals("Task 204", store.list().get(0).taskName);
        assertEquals(1, store.list("task-204", 5).size());
    }
}
