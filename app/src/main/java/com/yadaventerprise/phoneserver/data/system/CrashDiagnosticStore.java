package com.yadaventerprise.phoneserver.data.system;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.reliability.DiagnosticRedactor;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CrashDiagnosticStore {

    private static final String TAG = "CrashDiagnosticStore";
    private static final long MAX_FILE_BYTES = 256L * 1024L;
    private static final int MAX_READ = 200;

    private static volatile CrashDiagnosticStore instance;

    private final File file;
    private final File rotatedFile;
    private final Object lock = new Object();

    public CrashDiagnosticStore(File file) {
        this.file = file;
        this.rotatedFile = new File(file.getPath() + ".1");
    }

    public static CrashDiagnosticStore get(Context context) {
        if (instance == null) {
            synchronized (CrashDiagnosticStore.class) {
                if (instance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), "system");
                    if (!dir.exists()) dir.mkdirs();
                    com.yadaventerprise.phoneserver.data.security.SecurityPaths.restrict(dir);
                    instance = new CrashDiagnosticStore(new File(dir, "crash-diagnostics.jsonl"));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void append(String kind, String message) {
        Record record = new Record();
        record.time = System.currentTimeMillis();
        record.kind = kind == null || kind.isEmpty() ? "crash" : kind;
        record.message = DiagnosticRedactor.sanitize(message);
        synchronized (lock) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (file.exists() && file.length() > MAX_FILE_BYTES) rotate();
                try (FileOutputStream fos = new FileOutputStream(file, true);
                     BufferedWriter writer = new BufferedWriter(
                             new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
                    writer.write(toJson(record).toString());
                    writer.newLine();
                    writer.flush();
                }
                com.yadaventerprise.phoneserver.data.security.SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.w(TAG, "could not append crash diagnostic", e);
            }
        }
    }

    public List<Record> read(int limit) {
        List<Record> result = new ArrayList<>();
        synchronized (lock) {
            readFile(rotatedFile, result);
            readFile(file, result);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.time, a.time));
        int max = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_READ));
        return result.size() > max ? new ArrayList<>(result.subList(0, max)) : result;
    }

    public long fileBytes() {
        synchronized (lock) {
            long total = 0;
            if (file.exists()) total += file.length();
            if (rotatedFile.exists()) total += rotatedFile.length();
            return total;
        }
    }

    public JSONArray toJson(int limit) {
        JSONArray array = new JSONArray();
        for (Record record : read(limit)) array.put(toJson(record));
        return array;
    }

    private void readFile(File source, List<Record> out) {
        if (source == null || !source.exists() || !source.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    JSONObject json = new JSONObject(line);
                    Record record = new Record();
                    record.time = json.optLong("time", 0);
                    record.kind = json.optString("kind", "crash");
                    record.message = json.optString("message", "");
                    out.add(record);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read crash diagnostics", e);
        }
    }

    private static JSONObject toJson(Record record) {
        JSONObject json = new JSONObject();
        try {
            json.put("time", record.time);
            json.put("kind", record.kind);
            json.put("message", record.message);
        } catch (Exception ignored) {
        }
        return json;
    }

    private void rotate() {
        if (rotatedFile.exists()) rotatedFile.delete();
        if (!file.renameTo(rotatedFile)) file.delete();
    }

    public static final class Record {
        public long time;
        public String kind = "crash";
        public String message = "";
    }
}
