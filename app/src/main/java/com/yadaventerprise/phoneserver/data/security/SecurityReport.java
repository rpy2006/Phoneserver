package com.yadaventerprise.phoneserver.data.security;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Result of one security scan, persisted by {@link SecurityReportStore}. */
public class SecurityReport {
    public long generatedAt;
    public int score = 100;
    public String grade = "A";
    public String summary = "";
    public final List<SecurityFinding> findings = new ArrayList<>();

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("generatedAt", generatedAt);
        json.put("score", score);
        json.put("grade", grade);
        json.put("summary", summary);
        JSONArray array = new JSONArray();
        for (SecurityFinding finding : findings) array.put(finding.toJson());
        json.put("findings", array);
        return json;
    }

    public static SecurityReport fromJson(JSONObject json) {
        SecurityReport report = new SecurityReport();
        report.generatedAt = json.optLong("generatedAt", 0);
        report.score = json.optInt("score", 100);
        report.grade = json.optString("grade", "A");
        report.summary = json.optString("summary", "");
        JSONArray array = json.optJSONArray("findings");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) report.findings.add(SecurityFinding.fromJson(item));
            }
        }
        return report;
    }

    public int count(String severity) {
        int count = 0;
        for (SecurityFinding finding : findings) {
            if (severity.equals(finding.severity)) count++;
        }
        return count;
    }
}
