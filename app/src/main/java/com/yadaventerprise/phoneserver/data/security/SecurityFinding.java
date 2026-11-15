package com.yadaventerprise.phoneserver.data.security;

import org.json.JSONObject;

/** One configuration finding produced by {@link SecurityScanner}. */
public class SecurityFinding {
    public static final String CRITICAL = "critical";
    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";
    public static final String INFO = "info";

    public String id = "";
    public String severity = INFO;
    public String title = "";
    public String detail = "";
    public String remediation = "";

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("severity", severity);
        json.put("title", title);
        json.put("detail", detail);
        json.put("remediation", remediation);
        return json;
    }

    public static SecurityFinding fromJson(JSONObject json) {
        SecurityFinding finding = new SecurityFinding();
        finding.id = json.optString("id", "");
        finding.severity = json.optString("severity", INFO);
        finding.title = json.optString("title", "");
        finding.detail = json.optString("detail", "");
        finding.remediation = json.optString("remediation", "");
        return finding;
    }

    public static int weight(String severity) {
        switch (severity) {
            case CRITICAL: return 35;
            case HIGH: return 20;
            case MEDIUM: return 10;
            case LOW: return 5;
            default: return 0;
        }
    }
}
