package com.yadaventerprise.phoneserver.data.tasks;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public final class TaskHistoryStore {

    private static final int MAX_ENTRIES = 200;
    private static volatile TaskHistoryStore instance;
    private final File file;
    private final Object lock = new Object();
    private List<TaskHistoryEntry> cached;

    private TaskHistoryStore(File file) {
        this.file = file;
    }

    public static TaskHistoryStore get(Context context) {
        if (instance == null) {
            synchronized (TaskHistoryStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "tasks");
                    if (!dir.exists()) dir.mkdirs();
                    SecurityPaths.restrict(dir);
                    instance = new TaskHistoryStore(new File(dir, "task-history.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void append(TaskHistoryEntry entry) {
        if (entry == null) return;
        synchronized (lock) {
            ensureLoaded();
            cached.add(0, entry);
            while (cached.size() > MAX_ENTRIES) cached.remove(cached.size() - 1);
            persist();
        }
    }

    public List<TaskHistoryEntry> list() {
        synchronized (lock) {
            ensureLoaded();
            return new ArrayList<>(cached);
        }
    }

    public List<TaskHistoryEntry> list(String taskId, int limit) {
        List<TaskHistoryEntry> result = new ArrayList<>();
        for (TaskHistoryEntry entry : list()) {
            if (taskId != null && !taskId.isEmpty() && !taskId.equals(entry.taskId)) continue;
            result.add(entry);
            if (limit > 0 && result.size() >= limit) break;
        }
        return result;
    }

    public void clear() {
        synchronized (lock) {
            ensureLoaded();
            cached.clear();
            persist();
        }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        cached = new ArrayList<>();
        try {
            JSONArray array = JsonStorage.readJsonArray(file);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject json = array.optJSONObject(i);
                if (json != null) cached.add(TaskHistoryEntry.fromJson(json));
            }
        } catch (Exception e) {
            Log.w("TaskHistoryStore", "could not read task history", e);
        }
    }

    private void persist() {
        try {
            JSONArray array = new JSONArray();
            for (TaskHistoryEntry entry : cached) array.put(entry.toJson());
            JsonStorage.writeJsonArrayAtomic(file, array, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("TaskHistoryStore", "could not save task history", e);
        }
    }
}
