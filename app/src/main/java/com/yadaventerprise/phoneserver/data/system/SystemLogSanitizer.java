package com.yadaventerprise.phoneserver.data.system;

public final class SystemLogSanitizer {

    private SystemLogSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null) return "";
        String result = value.replaceAll("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?\\S+", "$1[redacted]")
                .replaceAll("(?i)(bearer\\s+)\\S+", "$1[redacted]")
                .replaceAll("(?i)([?&]?\\b(?:pw|password|token|api[_-]?key|secret|signature)\\b\\s*[:=]\\s*)[^&\\s]+", "$1[redacted]");
        return result.length() > 2000 ? result.substring(0, 2000) + "…" : result;
    }
}
