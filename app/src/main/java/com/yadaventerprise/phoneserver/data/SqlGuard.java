package com.yadaventerprise.phoneserver.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Static analysis of SQL before it reaches SQLite.
 *
 * This is defence in depth, not a substitute for parameterised queries: normal
 * data operations use bound parameters, and this guard additionally blocks
 * statements that could reach outside the project's own database file or
 * corrupt the connection (ATTACH, DETACH, load_extension, writable_schema, ...)
 * as well as multiple statements smuggled into one request.
 */
public final class SqlGuard {

    public enum Verdict {
        /** Statement is allowed. */
        OK,
        /** Nothing to run. */
        EMPTY,
        /** More than one statement was supplied. */
        MULTIPLE_STATEMENTS,
        /** A forbidden statement/keyword was found. */
        FORBIDDEN,
        /** A write statement was supplied on a read-only connection. */
        WRITE_DISALLOWED
    }

    public static final class Result {
        public final Verdict verdict;
        public final String reason;
        public final String keyword;

        Result(Verdict verdict, String reason, String keyword) {
            this.verdict = verdict;
            this.reason = reason;
            this.keyword = keyword;
        }

        public boolean isOk() {
            return verdict == Verdict.OK;
        }
    }

    private SqlGuard() {
    }

    public static Result inspect(String sql) {
        return inspect(sql, true);
    }

    public static Result inspect(String sql, boolean allowWrite) {
        if (sql == null || sql.trim().isEmpty()) {
            return new Result(Verdict.EMPTY, "no SQL supplied", null);
        }

        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) {
            return new Result(Verdict.EMPTY, "no SQL supplied", null);
        }
        if (statements.size() > 1) {
            return new Result(Verdict.MULTIPLE_STATEMENTS,
                    "only one SQL statement may be executed per request", ";");
        }

        String text = statements.get(0);
        String padded = " " + text + " ";

        String[] forbidden = {
                " attach ", " detach ", "load_extension", "writable_schema",
                "pragma temp_store_directory", "pragma data_store_directory",
                "pragma lock_status", "pragma database_list"
        };
        for (String bad : forbidden) {
            if (padded.contains(bad)) {
                String kw = bad.trim();
                return new Result(Verdict.FORBIDDEN, "statement '" + kw + "' is not allowed", kw);
            }
        }

        String first = text.split("\\s+")[0];
        switch (first) {
            case "select":
            case "with":
            case "explain":
            case "pragma":
                return new Result(Verdict.OK, "read", first);
            case "insert":
            case "update":
            case "delete":
            case "replace":
            case "create":
            case "drop":
            case "alter":
                if (!allowWrite) {
                    return new Result(Verdict.WRITE_DISALLOWED,
                            "this credential is read-only", first);
                }
                return new Result(Verdict.OK, "write", first);
            case "begin":
            case "commit":
            case "rollback":
            case "savepoint":
            case "release":
                return new Result(Verdict.FORBIDDEN,
                        "explicit transactions are not allowed; use the transaction API instead", first);
            default:
                return new Result(Verdict.FORBIDDEN, "unsupported SQL statement: " + first, first);
        }
    }

    public static boolean isReadOnlyKeyword(String keyword) {
        if (keyword == null) return false;
        String k = keyword.toLowerCase(Locale.US);
        return k.equals("select") || k.equals("with") || k.equals("explain") || k.equals("pragma");
    }

    /**
     * Splits SQL into top-level statements, ignoring separators inside string
     * literals, quoted identifiers and comments. Each returned value is a
     * lower-cased, whitespace-collapsed sequence of word characters; the first
     * token is the statement keyword.
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false, inDouble = false, inBacktick = false, inBracket = false,
                inLineComment = false, inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') { inBlockComment = false; i++; }
                continue;
            }
            if (inSingle) {
                if (c == '\'') {
                    if (next == '\'') i++; else inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    if (next == '"') i++; else inDouble = false;
                }
                continue;
            }
            if (inBacktick) {
                if (c == '`') inBacktick = false;
                continue;
            }
            if (inBracket) {
                if (c == ']') inBracket = false;
                continue;
            }

            if (c == '-' && next == '-') { inLineComment = true; i++; continue; }
            if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"') { inDouble = true; continue; }
            if (c == '`') { inBacktick = true; continue; }
            if (c == '[') { inBracket = true; continue; }

            if (c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) statements.add(stmt);
                current.setLength(0);
                continue;
            }

            // Skip bound-parameter names so ":name" does not look like a keyword.
            if ((c == ':' || c == '@' || c == '$') && Character.isLetter(next)) {
                i++;
                while (i + 1 < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                    i++;
                }
                current.append(' ');
                continue;
            }

            if (c == '?' || c == ':' || c == '@' || c == '$') {
                current.append(' ');
                continue;
            }

            if (Character.isLetterOrDigit(c) || c == '_') {
                current.append(Character.toLowerCase(c));
            } else {
                current.append(' ');
            }
        }

        String stmt = current.toString().trim();
        if (!stmt.isEmpty()) statements.add(stmt);
        // collapse repeated whitespace for stable matching
        List<String> cleaned = new ArrayList<>(statements.size());
        for (String s : statements) cleaned.add(s.replaceAll("\\s+", " "));
        return cleaned;
    }
}
