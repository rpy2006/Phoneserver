package com.yadaventerprise.phoneserver.data.security;

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

/** Bounded JSON-lines log of security events (auth failures, blocks, rate limits). */
public final class SecurityEventStore {

    private static final String TAG = "SecurityEventStore";
    private static final long MAX_FILE_BYTES = 1024L * 1024;
    private static final int MAX_READ = 1000;

    private static volatile SecurityEventStore instance;

    private final File file;
    private final File rotatedFile;
    private final Object lock = new Object();

    public SecurityEventStore(File file) {
        this.file = file;
        this.rotatedFile = new File(file.getPath() + ".1");
    }

    public static SecurityEventStore get(Context context) {
        if (instance == null) {
            synchronized (SecurityEventStore.class) {
                if (instance == null) instance = new SecurityEventStore(SecurityPaths.securityEvents(context));
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void append(SecurityEvent event) {
        if (event == null) return;
        synchronized (lock) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                if (file.exists() && file.length() > MAX_FILE_BYTES) rotate();
                try (FileOutputStream fos = new FileOutputStream(file, true);
                     BufferedWriter writer = new BufferedWriter(
                             new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
                    writer.write(event.toJson().toString());
                    writer.newLine();
                    writer.flush();
                }
                SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.w(TAG, "could not append security event", e);
            }
        }
    }

    private void rotate() {
        if (rotatedFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            rotatedFile.delete();
        }
        if (!file.renameTo(rotatedFile)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public List<SecurityEvent> read(Query query) {
        Query q = query == null ? new Query() : query;
        List<SecurityEvent> result = new ArrayList<>();
        synchronized (lock) {
            readFile(rotatedFile, q, result);
            readFile(file, q, result);
        }
        Collections.sort(result, new Comparator<SecurityEvent>() {
            @Override
            public int compare(SecurityEvent a, SecurityEvent b) {
                return Long.compare(b.time, a.time);
            }
        });
        int limit = Math.max(1, Math.min(q.limit <= 0 ? 200 : q.limit, MAX_READ));
        if (result.size() > limit) return new ArrayList<>(result.subList(0, limit));
        return result;
    }

    private void readFile(File source, Query query, List<SecurityEvent> out) {
        if (source == null || !source.exists() || !source.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    SecurityEvent event = SecurityEvent.fromJson(new JSONObject(line));
                    if (query.matches(event)) out.add(event);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read security events", e);
        }
    }

    public int countSince(long sinceMillis) {
        Query query = new Query();
        query.limit = MAX_READ;
        int count = 0;
        for (SecurityEvent event : read(query)) {
            if (event.time >= sinceMillis) count++;
        }
        return count;
    }

    public void clear() {
        synchronized (lock) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            rotatedFile.delete();
        }
    }

    public static class Query {
        public String search = "";
        public String type = "";
        public String severity = "";
        public int limit = 200;

        boolean matches(SecurityEvent event) {
            if (event == null) return false;
            if (type != null && !type.trim().isEmpty() && !event.type.equals(type)) return false;
            if (severity != null && !severity.trim().isEmpty() && !event.severity.equals(severity)) return false;
            if (search != null && !search.trim().isEmpty()) {
                String needle = search.trim().toLowerCase();
                String haystack = (event.ip + " " + event.path + " " + event.detail + " "
                        + event.type).toLowerCase();
                if (!haystack.contains(needle)) return false;
            }
            return true;
        }
    }
}
