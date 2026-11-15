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

/**
 * Bounded append-only JSON-lines access log. Rotation keeps at most one old
 * file, so request history cannot grow without limit on a phone.
 */
public final class AccessLogStore {

    private static final String TAG = "AccessLogStore";
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_READ = 1000;

    private static volatile AccessLogStore instance;

    private final File file;
    private final File rotatedFile;
    private final Object lock = new Object();

    public AccessLogStore(File file) {
        this.file = file;
        this.rotatedFile = new File(file.getPath() + ".1");
    }

    public static AccessLogStore get(Context context) {
        if (instance == null) {
            synchronized (AccessLogStore.class) {
                if (instance == null) instance = new AccessLogStore(SecurityPaths.accessLog(context));
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void append(AccessLogEntry entry) {
        if (entry == null) return;
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
                    writer.write(entry.toJson().toString());
                    writer.newLine();
                    writer.flush();
                }
                SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.w(TAG, "could not append access log entry", e);
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

    public List<AccessLogEntry> read(Query query) {
        Query q = query == null ? new Query() : query;
        List<AccessLogEntry> result = new ArrayList<>();
        synchronized (lock) {
            readFile(rotatedFile, q, result);
            readFile(file, q, result);
        }
        Collections.sort(result, new Comparator<AccessLogEntry>() {
            @Override
            public int compare(AccessLogEntry a, AccessLogEntry b) {
                return Long.compare(b.time, a.time);
            }
        });
        int limit = Math.max(1, Math.min(q.limit <= 0 ? 200 : q.limit, MAX_READ));
        if (result.size() > limit) return new ArrayList<>(result.subList(0, limit));
        return result;
    }

    private void readFile(File source, Query query, List<AccessLogEntry> out) {
        if (source == null || !source.exists() || !source.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    AccessLogEntry entry = AccessLogEntry.fromJson(new JSONObject(line));
                    if (query.matches(entry)) out.add(entry);
                } catch (Exception ignored) {
                    // Skip a corrupt line rather than losing the rest of the log.
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read access log", e);
        }
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
        public String ip = "";
        /** 0 = all, otherwise 2, 3, 4 or 5 for the status class. */
        public int statusClass;
        public int limit = 200;

        boolean matches(AccessLogEntry entry) {
            if (entry == null) return false;
            if (statusClass > 0 && entry.status / 100 != statusClass) return false;
            if (ip != null && !ip.trim().isEmpty()
                    && !entry.ip.toLowerCase().contains(ip.trim().toLowerCase())) return false;
            if (search != null && !search.trim().isEmpty()) {
                String needle = search.trim().toLowerCase();
                String haystack = (entry.path + " " + entry.ip + " " + entry.actor + " "
                        + entry.method + " " + entry.userAgent).toLowerCase();
                if (!haystack.contains(needle)) return false;
            }
            return true;
        }
    }
}
