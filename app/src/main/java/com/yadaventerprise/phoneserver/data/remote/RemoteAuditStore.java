package com.yadaventerprise.phoneserver.data.remote;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.json.JSONObject;

public final class RemoteAuditStore {
    public static final int MAX_ENTRIES = 5000;

    private static volatile RemoteAuditStore instance;

    private final File file;
    private final Object lock = new Object();

    private RemoteAuditStore(File file) {
        this.file = file;
    }

    public static RemoteAuditStore get(Context context) {
        if (instance == null) {
            synchronized (RemoteAuditStore.class) {
                if (instance == null) {
                    instance = new RemoteAuditStore(RemotePaths.file(context, "audit-log.jsonl"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void append(String event, String actor, String subject, String ip, String details, boolean allowed) {
        append(event, actor, subject, ip, details, allowed, System.currentTimeMillis());
    }

    public void append(String event, String actor, String subject, String ip, String details,
                       boolean allowed, long timestamp) {
        RemoteAuditEntry entry = new RemoteAuditEntry();
        entry.timestamp = timestamp;
        entry.event = RemoteAuditEntry.clean(event);
        entry.actor = RemoteAuditEntry.clean(actor);
        entry.subject = RemoteAuditEntry.clean(subject);
        entry.ip = RemoteAuditEntry.clean(ip);
        entry.details = RemoteAuditEntry.clean(details);
        entry.allowed = allowed;
        synchronized (lock) {
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                SecurityPaths.restrict(dir);
                trim();
                BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
                try {
                    writer.write(entry.toJson().toString());
                    writer.newLine();
                } finally {
                    writer.close();
                }
                SecurityPaths.restrict(file);
            } catch (IOException e) {
                Log.w("RemoteAuditStore", "could not append audit entry", e);
            }
        }
    }

    public List<RemoteAuditEntry> all() {
        return all(200);
    }

    public List<RemoteAuditEntry> all(int limit) {
        synchronized (lock) {
            List<RemoteAuditEntry> result = new ArrayList<>();
            if (!file.exists()) return result;
            try {
                Deque<RemoteAuditEntry> tail = new ArrayDeque<>();
                BufferedReader reader = new BufferedReader(new FileReader(file));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        try {
                            tail.addLast(RemoteAuditEntry.fromJson(new JSONObject(line)));
                        } catch (Exception ignored) {
                        }
                        while (tail.size() > Math.max(1, limit)) tail.removeFirst();
                    }
                } finally {
                    reader.close();
                }
                result.addAll(tail);
            } catch (IOException e) {
                Log.w("RemoteAuditStore", "could not read audit log", e);
            }
            return result;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (file.exists() && !file.delete()) {
                try {
                    BufferedWriter writer = new BufferedWriter(new FileWriter(file, false));
                    writer.close();
                } catch (IOException e) {
                    Log.w("RemoteAuditStore", "could not clear audit log", e);
                }
            }
        }
    }

    private void trim() {
        List<RemoteAuditEntry> entries = allRaw(MAX_ENTRIES);
        if (entries.size() < MAX_ENTRIES) return;
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(file, false));
            try {
                for (int i = entries.size() - 200; i < entries.size(); i++) {
                    writer.write(entries.get(i).toJson().toString());
                    writer.newLine();
                }
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            Log.w("RemoteAuditStore", "could not rotate audit log", e);
        }
    }

    private List<RemoteAuditEntry> allRaw(int limit) {
        List<RemoteAuditEntry> result = new ArrayList<>();
        if (!file.exists()) return result;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        result.add(RemoteAuditEntry.fromJson(new JSONObject(line)));
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            Log.w("RemoteAuditStore", "could not read audit log", e);
        }
        if (result.size() > limit) return new ArrayList<>(result.subList(result.size() - limit, result.size()));
        return result;
    }
}
