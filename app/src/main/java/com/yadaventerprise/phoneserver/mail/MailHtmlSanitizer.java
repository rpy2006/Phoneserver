package com.yadaventerprise.phoneserver.mail;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MailHtmlSanitizer {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern BLOCKED = Pattern.compile(
            "<\\s*(script|style|iframe|object|embed|form|svg|math|link|meta|base)[^>]*>.*?<\\s*/\\s*\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EMPTY_BLOCKED = Pattern.compile(
            "<\\s*(script|style|iframe|object|embed|form|svg|math|link|meta|base)\\b[^>]*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([A-Za-z0-9]+)([^>]*)>", Pattern.DOTALL);
    private static final Pattern EVENT_ATTR = Pattern.compile(
            "\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\"'\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile(
            "\\b(href|src)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\"'\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTR = Pattern.compile("\\sstyle\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\"'\\s>]+)",
            Pattern.CASE_INSENSITIVE);

    private MailHtmlSanitizer() {
    }

    public static String sanitize(String html) {
        if (html == null || html.trim().isEmpty()) return "";
        String value = COMMENT.matcher(html).replaceAll("");
        value = BLOCKED.matcher(value).replaceAll("");
        value = EMPTY_BLOCKED.matcher(value).replaceAll("");
        Matcher matcher = TAG.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        int cursor = 0;
        while (matcher.find()) {
            out.append(value, cursor, matcher.start());
            String closing = matcher.group(1);
            String name = matcher.group(2).toLowerCase(Locale.US);
            String attrs = matcher.group(3) == null ? "" : matcher.group(3);
            if (!allowed(name)) {
                cursor = matcher.end();
                continue;
            }
            attrs = EVENT_ATTR.matcher(attrs).replaceAll("");
            attrs = STYLE_ATTR.matcher(attrs).replaceAll("");
            attrs = safeHref(attrs, name.equals("a"));
            out.append('<').append(closing).append(name).append(attrs).append('>');
            cursor = matcher.end();
        }
        out.append(value.substring(cursor));
        return out.toString();
    }

    public static String stripTags(String html) {
        if (html == null) return "";
        String value = sanitize(html);
        value = value.replaceAll("<\\s*br\\s*/?>", "\n");
        value = value.replaceAll("<\\s*/(p|div|li|tr|h[1-6])\\s*>", "\n");
        Matcher matcher = TAG.matcher(value);
        StringBuilder sb = new StringBuilder(value.length());
        int cursor = 0;
        while (matcher.find()) {
            sb.append(value, cursor, matcher.start());
            cursor = matcher.end();
        }
        sb.append(value.substring(cursor));
        return decodeEntities(sb.toString());
    }

    private static boolean allowed(String name) {
        switch (name) {
            case "a":
            case "b":
            case "i":
            case "em":
            case "strong":
            case "u":
            case "p":
            case "br":
            case "div":
            case "span":
            case "ul":
            case "ol":
            case "li":
            case "blockquote":
            case "pre":
            case "code":
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
            case "table":
            case "thead":
            case "tbody":
            case "tr":
            case "th":
            case "td":
            case "hr":
                return true;
            default:
                return false;
        }
    }

    private static String safeHref(String attrs, boolean anchor) {
        Matcher matcher = HREF.matcher(attrs);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String attribute = matcher.group(1).toLowerCase(Locale.US);
            if (!anchor && attribute.equals("href")) {
                matcher.appendReplacement(sb, "");
                continue;
            }
            String raw = unquote(matcher.group(2));
            String lower = raw.trim().toLowerCase(Locale.US);
            boolean safe = lower.startsWith("http://") || lower.startsWith("https://")
                    || (anchor && lower.startsWith("mailto:"));
            if (!safe) {
                matcher.appendReplacement(sb, "");
                continue;
            }
            String quoted = "\"" + raw.replace("\"", "&quot;") + "\"";
            String replacement = attribute + "=" + quoted;
            if (anchor && attribute.equals("href")) {
                replacement += " rel=\"noopener noreferrer\"";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String decodeEntities(String value) {
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
