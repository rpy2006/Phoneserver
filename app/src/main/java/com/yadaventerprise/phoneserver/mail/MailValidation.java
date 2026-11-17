package com.yadaventerprise.phoneserver.mail;

import java.util.regex.Pattern;

public final class MailValidation {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.:\\[\\]-]{1,255}$");
    private static final Pattern FOLDER = Pattern.compile("^[A-Za-z0-9 ._\\-/]{1,80}$");

    private MailValidation() {
    }

    public static boolean isEmail(String value) {
        return value != null && value.length() <= 254 && EMAIL.matcher(value.trim()).matches();
    }

    public static boolean isHost(String value) {
        return value != null && HOST.matcher(value.trim()).matches();
    }

    public static boolean isPort(int port) {
        return port > 0 && port <= 65535;
    }

    public static String security(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        if (normalized.equals("tls") || normalized.equals("ssl")) return "tls";
        if (normalized.equals("starttls")) return "starttls";
        return "";
    }

    public static boolean isFolderName(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        return !trimmed.startsWith("/") && !trimmed.contains("..") && FOLDER.matcher(trimmed).matches();
    }

    public static void validate(MailAccount account) throws MailException {
        if (account == null) throw new MailException("mail account is required");
        if (account.name == null || account.name.trim().isEmpty() || account.name.length() > 120) {
            throw new MailException("account name is required");
        }
        if (!isEmail(account.email)) throw new MailException("a valid email address is required");
        if (!isEmail(account.username)) throw new MailException("a valid username is required");
        if (!isHost(account.smtpHost)) throw new MailException("a valid SMTP host is required");
        if (!isHost(account.imapHost)) throw new MailException("a valid IMAP host is required");
        if (!isPort(account.smtpPort)) throw new MailException("SMTP port must be between 1 and 65535");
        if (!isPort(account.imapPort)) throw new MailException("IMAP port must be between 1 and 65535");
        if (security(account.smtpSecurity).isEmpty()) throw new MailException("SMTP security must be tls or starttls");
        if (security(account.imapSecurity).isEmpty()) throw new MailException("IMAP security must be tls or starttls");
        if (!isFolderName(account.sentFolder)) throw new MailException("sent folder name is invalid");
        if (!isFolderName(account.draftsFolder)) throw new MailException("drafts folder name is invalid");
    }

    public static String cleanHeaderValue(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') continue;
            sb.append(c);
        }
        String cleaned = sb.toString().trim();
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }

    public static String cleanError(String value) {
        if (value == null) return "mail operation failed";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty()) cleaned = "mail operation failed";
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }
}
