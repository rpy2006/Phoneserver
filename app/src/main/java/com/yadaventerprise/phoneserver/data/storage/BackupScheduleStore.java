package com.yadaventerprise.phoneserver.data.storage;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import org.json.JSONObject;

/** Encrypted app-private JSON store for the automatic backup schedule. */
public final class BackupScheduleStore {

    private static final String TAG = "BackupScheduleStore";
    private static volatile BackupScheduleStore instance;

    private final File file;
    private final Object lock = new Object();
    private BackupSchedule cached;

    public BackupScheduleStore(File file) {
        this.file = file;
    }

    public static BackupScheduleStore get(Context context) {
        if (instance == null) {
            synchronized (BackupScheduleStore.class) {
                if (instance == null) {
                    instance = new BackupScheduleStore(SecurityPaths.backupSchedule(context));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public BackupSchedule get() {
        synchronized (lock) {
            if (cached == null) {
                try {
                    cached = BackupSchedule.fromJson(JsonStorage.readJson(file));
                } catch (Exception e) {
                    Log.w(TAG, "could not read backup schedule; using defaults", e);
                    cached = new BackupSchedule();
                }
            }
            return copy(cached);
        }
    }

    public void save(BackupSchedule schedule) {
        if (schedule == null) return;
        synchronized (lock) {
            try {
                JsonStorage.writeJsonAtomic(file, schedule.toJson(), true);
                SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.e(TAG, "could not save backup schedule", e);
            }
            cached = copy(schedule);
        }
    }

    private static BackupSchedule copy(BackupSchedule source) {
        BackupSchedule copy = new BackupSchedule();
        copy.enabled = source.enabled;
        copy.intervalHours = source.intervalHours;
        copy.includeFiles = source.includeFiles;
        copy.retainPerProject = source.retainPerProject;
        copy.lastRunAt = source.lastRunAt;
        copy.nextRunAt = source.nextRunAt;
        copy.lastSuccessCount = source.lastSuccessCount;
        copy.lastFailureCount = source.lastFailureCount;
        copy.lastResult = source.lastResult;
        copy.lastError = source.lastError;
        return copy;
    }
}
