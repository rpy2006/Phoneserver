package com.yadaventerprise.phoneserver.data.reliability;

import java.util.regex.Pattern;

public final class DiagnosticRedactor {

    private static final int MAX_LENGTH = 2000;
    private static final String REDACTED = "[redacted]";

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+");
    private static final Pattern CREDENTIAL_FIELD = Pattern.compile(
            "(?i)(password|passwd|pwd|token|api[_-]?key|secret|signature|authorization)(\\s*[:=]\\s*)(\"?)([^\"\\s,;&]+)(\"?)");
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:pw|password|token|api_key|secret|signature)=)([^&\\s]+)");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    private DiagnosticRedactor() {
    }

    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) return "";
        String value = AUTHORIZATION.matcher(input).replaceAll("$1" + REDACTED);
        value = CREDENTIAL_FIELD.matcher(value).replaceAll("$1$2$3" + REDACTED + "$5");
        value = QUERY_SECRET.matcher(value).replaceAll("$1" + REDACTED);
        value = EMAIL.matcher(value).replaceAll(REDACTED);
        value = maskIps(value);
        if (value.length() > MAX_LENGTH) return value.substring(0, MAX_LENGTH) + "…";
        return value;
    }

    public static String maskIp(String input) {
        if (input == null || input.isEmpty()) return "";
        return maskIps(input);
    }

    private static String maskIps(String input) {
        return IPV4.matcher(input).replaceAll("$1.$2.*.*");
    }
}
