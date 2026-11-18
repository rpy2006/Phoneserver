package com.yadaventerprise.phoneserver.data.system;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

public final class SystemEventStore {

    private static final String TAG = "SystemEventStore";
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_READ = 1000;

    private static volatile SystemEventStore instance;
    private final File file;
    private final File rotatedFile;
    private final Object lock = new Object();

    public SystemEventStore(File file) {
        this.file = file;
        this.rotatedFile = new File(file.getPath() + ".1");
    }

    public static SystemEventStore get(Context context) {
        if (instance == null) {
            synchronized (SystemEventStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "system");
                    if (!dir.exists()) dir.mkdirs();
                    com.yadaventerprise.phoneserver.data.security.SecurityPaths.restrict(dir);
                    instance = new SystemEventStore(new File(dir, "system-events.jsonl"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void append(SystemEvent event) {
        if (event == null) return;
        synchronized (lock) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (file.exists() && file.length() > MAX_FILE_BYTES) rotate();
                try (FileOutputStream fos = new FileOutputStream(file, true);
                     BufferedWriter writer = new BufferedWriter(
                             new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
                    writer.write(event.toJson().toString());
                    writer.newLine();
                    writer.flush();
                }
                com.yadaventerprise.phoneserver.data.security.SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.w(TAG, "could not append system event", e);
            }
        }
    }

    public void info(String source, String message) {
        append(new SystemEvent(SystemEvent.LEVEL_INFO, source, message));
    }

    public void warn(String source, String message) {
        append(new SystemEvent(SystemEvent.LEVEL_WARN, source, message));
    }

    public void error(String source, String message) {
        append(new SystemEvent(SystemEvent.LEVEL_ERROR, source, message));
    }

    public List<SystemEvent> read(Query query) {
        Query q = query == null ? new Query() : query;
        List<SystemEvent> result = new ArrayList<>();
        synchronized (lock) {
            readFile(rotatedFile, q, result);
            readFile(file, q, result);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.time, a.time));
        int limit = Math.max(1, Math.min(q.limit <= 0 ? 200 : q.limit, MAX_READ));
        if (result.size() > limit) return new ArrayList<>(result.subList(0, limit));
        return result;
    }

    public int prune(long cutoffMillis) {
        synchronized (lock) {
            int removed = pruneFile(file, cutoffMillis);
            removed += pruneFile(rotatedFile, cutoffMillis);
            return removed;
        }
    }

    private int pruneFile(File source, long cutoffMillis) {
        if (source == null || !source.exists()) return 0;
        List<String> lines = new ArrayList<>();
        int removed = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    long time = new JSONObject(line).optLong("time", 0);
                    if (time > 0 && time < cutoffMillis) {
                        removed++;
                        continue;
                    }
                } catch (Exception ignored) {
                }
                lines.add(line);
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read system events for pruning", e);
            return 0;
        }
        if (removed == 0) return 0;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(source, false), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            Log.w(TAG, "could not write pruned system events", e);
            return 0;
        }
        return removed;
    }

    public long fileBytes() {
        synchronized (lock) {
            long total = 0;
            if (file.exists()) total += file.length();
            if (rotatedFile.exists()) total += rotatedFile.length();
            return total;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (file.exists()) file.delete();
            if (rotatedFile.exists()) rotatedFile.delete();
        }
    }

    private void rotate() {
        if (rotatedFile.exists()) rotatedFile.delete();
        if (!file.renameTo(rotatedFile)) file.delete();
    }

    private void readFile(File source, Query query, List<SystemEvent> out) {
        if (source == null || !source.exists() || !source.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    SystemEvent event = SystemEvent.fromJson(new JSONObject(line));
                    if (query.matches(event)) out.add(event);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read system events", e);
        }
    }

    public static class Query {
        public String search = "";
        public String source = "";
        public String level = "";
        public int limit = 200;

        boolean matches(SystemEvent event) {
            if (event == null) return false;
            if (source != null && !source.trim().isEmpty()
                    && !source.equalsIgnoreCase(event.source)) return false;
            if (level != null && !level.trim().isEmpty()
                    && !level.equalsIgnoreCase(event.level)) return false;
            if (search != null && !search.trim().isEmpty()) {
                String needle = search.trim().toLowerCase();
                String haystack = (event.source + " " + event.level + " " + event.message).toLowerCase();
                if (!haystack.contains(needle)) return false;
            }
            return true;
        }
    }
}
