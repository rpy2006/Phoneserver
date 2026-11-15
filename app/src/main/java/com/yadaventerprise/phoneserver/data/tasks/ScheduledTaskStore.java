package com.yadaventerprise.phoneserver.data.tasks;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public final class ScheduledTaskStore {

    private static volatile ScheduledTaskStore instance;
    private final File file;
    private final Object lock = new Object();
    private List<ScheduledTask> cached;

    private ScheduledTaskStore(File file) {
        this.file = file;
    }

    public static ScheduledTaskStore get(Context context) {
        if (instance == null) {
            synchronized (ScheduledTaskStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "tasks");
                    if (!dir.exists()) dir.mkdirs();
                    SecurityPaths.restrict(dir);
                    instance = new ScheduledTaskStore(new File(dir, "scheduled-tasks.json"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public List<ScheduledTask> all() {
        synchronized (lock) {
            ensureLoaded();
            List<ScheduledTask> result = new ArrayList<>();
            for (ScheduledTask task : cached) result.add(ScheduledTask.copy(task));
            return result;
        }
    }

    public List<ScheduledTask> enabled() {
        synchronized (lock) {
            ensureLoaded();
            List<ScheduledTask> result = new ArrayList<>();
            for (ScheduledTask task : cached) {
                if (task.enabled) result.add(ScheduledTask.copy(task));
            }
            return result;
        }
    }

    public ScheduledTask get(String id) {
        synchronized (lock) {
            ensureLoaded();
            for (ScheduledTask task : cached) {
                if (task.id.equals(id)) return ScheduledTask.copy(task);
            }
            return null;
        }
    }

    public void save(ScheduledTask task) {
        if (task == null) return;
        synchronized (lock) {
            ensureLoaded();
            boolean found = false;
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(task.id)) {
                    cached.set(i, ScheduledTask.copy(task));
                    found = true;
                    break;
                }
            }
            if (!found) cached.add(ScheduledTask.copy(task));
            persist();
        }
    }

    public void delete(String id) {
        synchronized (lock) {
            ensureLoaded();
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(id)) {
                    cached.remove(i);
                    persist();
                    return;
                }
            }
        }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        cached = new ArrayList<>();
        try {
            JSONArray array = JsonStorage.readJsonArray(file);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject json = array.optJSONObject(i);
                if (json != null) cached.add(ScheduledTask.fromJson(json));
            }
        } catch (Exception e) {
            Log.w("ScheduledTaskStore", "could not read scheduled tasks", e);
        }
    }

    private void persist() {
        try {
            JSONArray array = new JSONArray();
            for (ScheduledTask task : cached) array.put(task.toJson());
            JsonStorage.writeJsonArrayAtomic(file, array, true);
            SecurityPaths.restrict(file);
        } catch (Exception e) {
            Log.e("ScheduledTaskStore", "could not save scheduled tasks", e);
        }
    }
}
