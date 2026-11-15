package com.yadaventerprise.phoneserver.data.security;

import android.content.Context;
import android.util.Log;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Keeps the most recent security scans in encrypted app-private JSON. */
public final class SecurityReportStore {

    private static final String TAG = "SecurityReportStore";
    private static final int MAX_REPORTS = 20;
    private static volatile SecurityReportStore instance;

    private final File file;
    private final Object lock = new Object();

    public SecurityReportStore(File file) {
        this.file = file;
    }

    public static SecurityReportStore get(Context context) {
        if (instance == null) {
            synchronized (SecurityReportStore.class) {
                if (instance == null) {
                    instance = new SecurityReportStore(SecurityPaths.securityReports(context));
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public void save(SecurityReport report) {
        if (report == null) return;
        synchronized (lock) {
            try {
                List<SecurityReport> reports = load();
                reports.add(0, report);
                while (reports.size() > MAX_REPORTS) reports.remove(reports.size() - 1);
                JSONArray array = new JSONArray();
                for (SecurityReport item : reports) array.put(item.toJson());
                JSONObject json = new JSONObject();
                json.put("reports", array);
                JsonStorage.writeJsonAtomic(file, json, true);
                SecurityPaths.restrict(file);
            } catch (Exception e) {
                Log.e(TAG, "could not save security report", e);
            }
        }
    }

    public SecurityReport latest() {
        List<SecurityReport> reports = list();
        return reports.isEmpty() ? null : reports.get(0);
    }

    public List<SecurityReport> list() {
        synchronized (lock) {
            return load();
        }
    }

    private List<SecurityReport> load() {
        List<SecurityReport> result = new ArrayList<>();
        try {
            JSONObject json = JsonStorage.readJson(file);
            JSONArray array = json.optJSONArray("reports");
            if (array == null) return result;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) result.add(SecurityReport.fromJson(item));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read security reports", e);
        }
        return result;
    }

    public void clear() {
        synchronized (lock) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
