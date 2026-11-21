package com.yadaventerprise.phoneserver.data.ai;

import java.util.regex.Pattern;

public final class AiRedactor {
    private AiRedactor() {}
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]+");
    private static final Pattern KV = Pattern.compile("(?i)(api[_-]?key|token|password|secret|authorization|cookie|session)\\s*[=:]\\s*([^\\s,;]+)");
    private static final Pattern LONG = Pattern.compile("\\b[A-Za-z0-9_\\-]{32,}\\b");

    public static String redact(String input) {
        if (input == null || input.isEmpty()) return "";
        String s = BEARER.matcher(input).replaceAll("$1[REDACTED]");
        s = KV.matcher(s).replaceAll("$1=[REDACTED]");
        s = LONG.matcher(s).replaceAll("[REDACTED]");
        return s;
    }
}
