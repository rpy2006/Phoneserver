package com.yadaventerprise.phoneserver.data.system;

import org.json.JSONException;
import org.json.JSONObject;

public class SystemEvent {
    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_ERROR = "error";

    public long time;
    public String level = LEVEL_INFO;
    public String source = "system";
    public String message = "";

    public SystemEvent() {
    }

    public SystemEvent(String level, String source, String message) {
        this.time = System.currentTimeMillis();
        this.level = normalizeLevel(level);
        this.source = source == null || source.trim().isEmpty() ? "system" : source.trim();
        this.message = SystemLogSanitizer.sanitize(message);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("time", time);
        json.put("level", level);
        json.put("source", source);
        json.put("message", message);
        return json;
    }

    public static SystemEvent fromJson(JSONObject json) {
        SystemEvent event = new SystemEvent();
        if (json == null) return event;
        event.time = json.optLong("time", 0);
        event.level = normalizeLevel(json.optString("level", LEVEL_INFO));
        event.source = json.optString("source", "system");
        event.message = SystemLogSanitizer.sanitize(json.optString("message", ""));
        return event;
    }

    public static String normalizeLevel(String value) {
        if (LEVEL_WARN.equalsIgnoreCase(value)) return LEVEL_WARN;
        if (LEVEL_ERROR.equalsIgnoreCase(value)) return LEVEL_ERROR;
        return LEVEL_INFO;
    }
}
