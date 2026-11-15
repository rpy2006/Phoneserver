package com.yadaventerprise.phoneserver.data.sharing;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
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

public final class ShareAccessLogStore {

    private static final String TAG = "ShareAccessLogStore";
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_READ = 2000;

    private static volatile ShareAccessLogStore instance;

    private final File file;
    private final File rotatedFile;
    private final Object lock = new Object();

    public ShareAccessLogStore(File file) {
        this.file = file;
        this.rotatedFile = new File(file.getPath() + ".1");
    }

    public static ShareAccessLogStore get(Context context) {
        if (instance == null) {
            synchronized (ShareAccessLogStore.class) {
                if (instance == null) instance = new ShareAccessLogStore(SharePaths.accessLog(context));
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void append(ShareAccessEvent event) {
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
                SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.w(TAG, "could not append share access log", e);
            }
        }
    }

    public void record(String event, ShareLink link, int status, String detail, String ip,
                       String path, String userAgent) {
        ShareAccessEvent entry = new ShareAccessEvent();
        entry.time = System.currentTimeMillis();
        entry.linkId = link == null ? "" : link.id;
        entry.linkName = link == null ? "" : link.name;
        entry.event = event == null ? "" : event;
        entry.status = status;
        entry.detail = detail == null ? "" : detail;
        entry.ip = ip == null ? "" : ip;
        entry.path = path == null ? "" : path;
        entry.userAgent = userAgent == null ? "" : userAgent;
        append(entry);
    }

    public List<ShareAccessEvent> read(Query query) {
        Query q = query == null ? new Query() : query;
        List<ShareAccessEvent> result = new ArrayList<>();
        synchronized (lock) {
            readFile(rotatedFile, q, result);
            readFile(file, q, result);
        }
        Collections.sort(result, new Comparator<ShareAccessEvent>() {
            @Override
            public int compare(ShareAccessEvent a, ShareAccessEvent b) {
                return Long.compare(b.time, a.time);
            }
        });
        int limit = Math.max(1, Math.min(q.limit <= 0 ? 300 : q.limit, MAX_READ));
        if (result.size() > limit) return new ArrayList<>(result.subList(0, limit));
        return result;
    }

    private void readFile(File source, Query query, List<ShareAccessEvent> out) {
        if (source == null || !source.exists() || !source.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    ShareAccessEvent event = ShareAccessEvent.fromJson(new JSONObject(line));
                    if (query.matches(event)) out.add(event);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read share access log", e);
        }
    }

    public void clear() {
        synchronized (lock) {
            file.delete();
            rotatedFile.delete();
        }
    }

    private void rotate() {
        if (rotatedFile.exists()) rotatedFile.delete();
        if (!file.renameTo(rotatedFile)) file.delete();
    }

    public static class Query {
        public String search = "";
        public String linkId = "";
        public String event = "";
        public int limit = 300;

        boolean matches(ShareAccessEvent entry) {
            if (entry == null) return false;
            if (linkId != null && !linkId.trim().isEmpty() && !entry.linkId.equals(linkId.trim())) {
                return false;
            }
            if (event != null && !event.trim().isEmpty() && !event.equals(entry.event)) return false;
            if (search != null && !search.trim().isEmpty()) {
                String needle = search.trim().toLowerCase();
                String haystack = (entry.linkName + " " + entry.linkId + " " + entry.event + " "
                        + entry.ip + " " + entry.path + " " + entry.detail).toLowerCase();
                if (!haystack.contains(needle)) return false;
            }
            return true;
        }
    }
}
