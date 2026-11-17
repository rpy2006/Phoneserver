package com.yadaventerprise.phoneserver.mail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MailMime {

    public static final class Parsed {
        public String from = "";
        public String to = "";
        public String cc = "";
        public String subject = "";
        public String date = "";
        public String messageId = "";
        public String text = "";
        public String html = "";
        public final List<Attachment> attachments = new ArrayList<>();
    }

    public static final class Attachment {
        public final String name;
        public final String contentType;
        public final String contentId;
        public final byte[] bytes;

        Attachment(String name, String contentType, String contentId, byte[] bytes) {
            this.name = name;
            this.contentType = contentType;
            this.contentId = contentId == null ? "" : contentId;
            this.bytes = bytes;
        }
    }

    private static final Pattern PARAMETER = Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*(\"[^\"]*\"|[^;\\s]+)");
    private static final Pattern ENCODED_WORD = Pattern.compile(
            "=\\?([A-Za-z0-9_-]+)\\?([BQbq])\\?([^?]*)\\?=", Pattern.DOTALL);

    private MailMime() {
    }

    public static Parsed parse(byte[] raw) {
        Parsed parsed = new Parsed();
        if (raw == null || raw.length == 0) return parsed;
        String all = new String(raw, StandardCharsets.ISO_8859_1);
        int split = indexOfHeaderEnd(all);
        String headerBlock = split < 0 ? all : all.substring(0, split);
        String body = split < 0 ? "" : all.substring(split + 2);
        Map<String, String> headers = parseHeaders(headerBlock);
        parsed.from = decodeHeader(headers.get("from"));
        parsed.to = decodeHeader(headers.get("to"));
        parsed.cc = decodeHeader(headers.get("cc"));
        parsed.subject = decodeHeader(headers.get("subject"));
        parsed.date = MailValidation.cleanHeaderValue(headers.get("date"));
        parsed.messageId = MailValidation.cleanHeaderValue(headers.get("message-id"));
        parseBody(headers, body, parsed);
        return parsed;
    }

    public static byte[] build(MailAccount account, MailCompose compose) throws MailException {
        compose.validate();
        String from = account.email;
        if (!MailValidation.isEmail(from)) throw new MailException("mail account email is invalid");
        String date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(new Date());
        String messageId = "<" + UUID.randomUUID().toString().replace("-", "")
                + "@" + hostOf(account.email) + ">";
        StringBuilder sb = new StringBuilder(4096);
        sb.append("From: ").append(formatAddress(account.fromName, account.email)).append("\r\n");
        sb.append("To: ").append(MailValidation.cleanHeaderValue(compose.to)).append("\r\n");
        if (!compose.cc.trim().isEmpty()) sb.append("Cc: ").append(MailValidation.cleanHeaderValue(compose.cc)).append("\r\n");
        sb.append("Subject: ").append(encodeHeader(compose.subject)).append("\r\n");
        sb.append("Date: ").append(date).append("\r\n");
        sb.append("Message-ID: ").append(messageId).append("\r\n");
        if (!compose.inReplyTo.trim().isEmpty()) {
            sb.append("In-Reply-To: ").append(MailValidation.cleanHeaderValue(compose.inReplyTo)).append("\r\n");
        }
        if (!compose.references.trim().isEmpty()) {
            sb.append("References: ").append(MailValidation.cleanHeaderValue(compose.references)).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");

        boolean hasHtml = !compose.html.trim().isEmpty();
        boolean hasAttachments = !compose.attachments.isEmpty();
        if (hasAttachments) {
            String boundary = "ps_mixed_" + UUID.randomUUID().toString().replace("-", "");
            sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
            sb.append("--").append(boundary).append("\r\n");
            if (hasHtml) {
                appendAlternative(sb, compose.text, compose.html);
            } else {
                appendTextPart(sb, compose.text);
            }
            for (MailCompose.OutgoingAttachment attachment : compose.attachments) {
                String name = MailValidation.cleanHeaderValue(
                        com.yadaventerprise.phoneserver.security.PathGuard.sanitizeFileName(attachment.name));
                String type = safeContentType(attachment.contentType);
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Type: ").append(type).append("; name=\"").append(name).append("\"\r\n");
                sb.append("Content-Transfer-Encoding: base64\r\n");
                sb.append("Content-Disposition: attachment; filename=\"").append(name).append("\"\r\n\r\n");
                sb.append(MailBase64.encodeWrapped(attachment.bytes, 76)).append("\r\n");
            }
            sb.append("--").append(boundary).append("--\r\n");
        } else if (hasHtml) {
            appendAlternative(sb, compose.text, compose.html);
        } else {
            appendTextPart(sb, compose.text);
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void appendAlternative(StringBuilder sb, String text, String html) {
        String boundary = "ps_alt_" + UUID.randomUUID().toString().replace("-", "");
        sb.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
        sb.append("--").append(boundary).append("\r\n");
        appendTextPart(sb, text);
        sb.append("--").append(boundary).append("\r\n");
        appendHtmlPart(sb, html);
        sb.append("--").append(boundary).append("--\r\n");
    }

    private static void appendTextPart(StringBuilder sb, String text) {
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(MailBase64.encodeWrapped(text.getBytes(StandardCharsets.UTF_8), 76)).append("\r\n");
    }

    private static void appendHtmlPart(StringBuilder sb, String html) {
        String sanitized = MailHtmlSanitizer.sanitize(html);
        sb.append("Content-Type: text/html; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(MailBase64.encodeWrapped(sanitized.getBytes(StandardCharsets.UTF_8), 76)).append("\r\n");
    }

    private static void parseBody(Map<String, String> headers, String body, Parsed parsed) {
        String contentType = headers.get("content-type") == null
                ? "text/plain; charset=UTF-8" : headers.get("content-type");
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.US);
        if (type.startsWith("multipart/") && !body.trim().isEmpty()) {
            String boundary = parameter(contentType, "boundary");
            if (!boundary.isEmpty()) {
                parseMultipart(body, boundary, parsed);
                return;
            }
        }
        byte[] decoded = decodeTransfer(headers.get("content-transfer-encoding"),
                body.getBytes(StandardCharsets.ISO_8859_1));
        String charset = parameter(contentType, "charset");
        if (charset.isEmpty()) charset = "UTF-8";
        String text = decodeText(decoded, charset);
        String disposition = headers.get("content-disposition") == null
                ? "" : headers.get("content-disposition").toLowerCase(Locale.US);
        String filename = parameter(headers.get("content-disposition"), "filename");
        if (filename.isEmpty()) filename = parameter(contentType, "name");
        if (disposition.startsWith("attachment") || (!filename.isEmpty() && !type.startsWith("text/"))) {
            parsed.attachments.add(new Attachment(
                    com.yadaventerprise.phoneserver.security.PathGuard.sanitizeFileName(filename),
                    safeContentType(type), parameter(headers.get("content-id"), "content-id"), decoded));
        } else if (type.equals("text/html")) {
            parsed.html = text;
        } else {
            parsed.text = text;
        }
    }

    private static void parseMultipart(String body, String boundary, Parsed parsed) {
        String normalized = body.replace("\r\n", "\n");
        String[] chunks = normalized.split(Pattern.quote("\n--" + boundary), -1);
        for (String chunk : chunks) {
            String part = chunk;
            if (part.startsWith("--")) break;
            if (part.startsWith("\r")) part = part.substring(1);
            if (part.startsWith("\n")) part = part.substring(1);
            if (part.endsWith("\n")) part = part.substring(0, part.length() - 1);
            if (part.endsWith("\r")) part = part.substring(0, part.length() - 1);
            if (part.trim().isEmpty()) continue;
            int end = indexOfHeaderEnd(part);
            if (end < 0) continue;
            Map<String, String> headers = parseHeaders(part.substring(0, end));
            String content = part.substring(end + 2);
            parseBody(headers, content, parsed);
        }
    }

    private static byte[] decodeTransfer(String encoding, byte[] input) {
        String value = encoding == null ? "" : encoding.trim().toLowerCase(Locale.US);
        if (value.equals("base64")) return MailBase64.decode(new String(input, StandardCharsets.ISO_8859_1));
        if (value.equals("quoted-printable")) return decodeQuotedPrintable(new String(input, StandardCharsets.ISO_8859_1));
        return input;
    }

    private static byte[] decodeQuotedPrintable(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=' && i + 2 < value.length()) {
                String hex = value.substring(i + 1, i + 3);
                if (hex.matches("[0-9A-Fa-f]{2}")) {
                    out.write(Integer.parseInt(hex, 16));
                    i += 2;
                    continue;
                }
                if (value.charAt(i + 1) == '\r' && i + 3 < value.length() && value.charAt(i + 2) == '\n') {
                    i += 2;
                    continue;
                }
                if (value.charAt(i + 1) == '\n') {
                    i += 1;
                    continue;
                }
            }
            out.write((byte) c);
        }
        return out.toByteArray();
    }

    private static String decodeText(byte[] bytes, String charset) {
        String name = charset.replaceAll("^\"|\"$", "").trim();
        try {
            return new String(bytes, java.nio.charset.Charset.forName(name));
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseHeaders(String block) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = block.replace("\r\n", "\n").split("\n");
        String current = null;
        StringBuilder value = new StringBuilder();
        for (String line : lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && current != null) {
                value.append(' ').append(line.trim());
                continue;
            }
            if (current != null) headers.put(current, value.toString().trim());
            int colon = line.indexOf(':');
            if (colon <= 0) {
                current = null;
                value.setLength(0);
                continue;
            }
            current = line.substring(0, colon).trim().toLowerCase(Locale.US);
            value.setLength(0);
            value.append(line.substring(colon + 1).trim());
        }
        if (current != null) headers.put(current, value.toString().trim());
        return headers;
    }

    private static int indexOfHeaderEnd(String value) {
        int crlf = value.indexOf("\r\n\r\n");
        int lf = value.indexOf("\n\n");
        if (crlf < 0) return lf;
        if (lf < 0) return crlf;
        return Math.min(crlf, lf);
    }

    private static String parameter(String header, String name) {
        if (header == null) return "";
        Matcher matcher = PARAMETER.matcher(header);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(name)) {
                String value = matcher.group(2);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return decodeHeader(value);
            }
        }
        return "";
    }

    private static String decodeHeader(String value) {
        if (value == null) return "";
        Matcher matcher = ENCODED_WORD.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String charset = matcher.group(1);
            boolean base64 = matcher.group(2).equalsIgnoreCase("B");
            String encoded = matcher.group(3);
            String decoded;
            if (base64) {
                decoded = decodeText(MailBase64.decode(encoded), charset);
            } else {
                byte[] quoted = decodeQuotedPrintable(encoded.replace('_', ' '));
                decoded = decodeText(quoted, charset);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(decoded));
        }
        matcher.appendTail(sb);
        return MailValidation.cleanHeaderValue(sb.toString());
    }

    private static String encodeHeader(String value) {
        String clean = MailValidation.cleanHeaderValue(value);
        boolean ascii = true;
        for (int i = 0; i < clean.length(); i++) {
            if (clean.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (ascii) return clean;
        return "=?UTF-8?B?" + MailBase64.encode(clean.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String formatAddress(String name, String email) {
        String clean = MailValidation.cleanHeaderValue(name);
        if (clean.isEmpty()) return email;
        return encodeHeader(clean) + " <" + email + ">";
    }

    private static String safeContentType(String value) {
        String clean = MailValidation.cleanHeaderValue(value);
        if (clean.isEmpty() || !clean.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) {
            return "application/octet-stream";
        }
        return clean.toLowerCase(Locale.US);
    }

    private static String hostOf(String email) {
        int at = email.lastIndexOf('@');
        return at < 0 || at == email.length() - 1 ? "localhost" : email.substring(at + 1);
    }
}
