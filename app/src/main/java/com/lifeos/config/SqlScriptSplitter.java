package com.lifeos.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a SQLite script into statements on top-level {@code ;}.
 *
 * <p>A {@code ;} does not end a statement when it is inside:
 * <ul>
 *   <li>a single-quoted string ({@code 'a;b'}, {@code ''} is an escaped quote)</li>
 *   <li>a double-quoted or backtick identifier ({@code "a;b"}, doubled quote escapes)</li>
 *   <li>a bracket identifier ({@code [a;b]})</li>
 *   <li>a {@code --} line comment or a {@code /* ... *&#47;} block comment</li>
 *   <li>the body of {@code CREATE [TEMP] TRIGGER ... BEGIN ... END}; {@code CASE ... END}
 *       expressions inside the body nest and do not end it</li>
 * </ul>
 * An unterminated quote, {@code [} identifier, block comment or trigger body is an error
 * ({@link IllegalArgumentException}), never silently swallowed into the last statement.
 * Comments are dropped from the output (a line comment becomes a newline, a block comment a
 * space), so a statement that was only comments is not returned. Returned statements are
 * trimmed and have no trailing {@code ;}.
 */
public final class SqlScriptSplitter {

    private static final Pattern TRIGGER_START =
            Pattern.compile("(?is)^\\s*CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER\\b.*");

    private SqlScriptSplitter() {}

    public static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = script.length();
        int i = 0;
        while (i < n) {
            char ch = script.charAt(i);
            char next = i + 1 < n ? script.charAt(i + 1) : 0;
            if (ch == '\'' || ch == '"' || ch == '`') {
                // Quoted string/identifier; a doubled quote char is an escape, not the end.
                int start = i;
                cur.append(ch);
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = script.charAt(i);
                    cur.append(c);
                    i++;
                    if (c == ch) {
                        if (i < n && script.charAt(i) == ch) {
                            cur.append(ch);
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) throw unterminated(ch == '\'' ? "string literal" : "quoted identifier", script, start);
            } else if (ch == '[') {
                int close = script.indexOf(']', i + 1);
                if (close < 0) throw unterminated("[identifier]", script, i);
                int end = close + 1;
                cur.append(script, i, end);
                i = end;
            } else if (ch == '-' && next == '-') {
                int nl = script.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                cur.append('\n');
            } else if (ch == '/' && next == '*') {
                int close = script.indexOf("*/", i + 2);
                if (close < 0) throw unterminated("/* block comment", script, i);
                i = close + 2;
                cur.append(' ');
            } else if (ch == ';') {
                i++;
                String stmt = cur.toString();
                if (isOpenTrigger(stmt)) {
                    cur.append(';');   // inside the trigger body
                    continue;
                }
                add(out, stmt);
                cur.setLength(0);
            } else {
                cur.append(ch);
                i++;
            }
        }
        if (isOpenTrigger(cur.toString())) {
            throw new IllegalArgumentException("unterminated CREATE TRIGGER body (missing END): "
                    + abbreviate(cur.toString()));
        }
        add(out, cur.toString());
        return out;
    }

    /**
     * True while a CREATE TRIGGER statement's BEGIN ... END body is still open. CASE ... END
     * nests inside the body. Same logic as SqlScript on feat/turso-datasource.
     */
    static boolean isOpenTrigger(String stmt) {
        if (!TRIGGER_START.matcher(stmt).matches()) return false;
        int depth = 0;
        boolean inBody = false;
        for (String w : words(stmt.toUpperCase(java.util.Locale.ROOT))) {
            switch (w) {
                case "BEGIN" -> {
                    if (!inBody) {
                        inBody = true;
                        depth = 1;
                    }
                }
                case "CASE" -> {
                    if (inBody) depth++;
                }
                case "END" -> {
                    if (inBody && --depth == 0) return false;
                }
                default -> { }
            }
        }
        return true;
    }

    /** Bare words of a (comment-free) statement, skipping quoted strings and identifiers. */
    private static List<String> words(String u) {
        List<String> out = new ArrayList<>();
        int n = u.length();
        int i = 0;
        while (i < n) {
            char ch = u.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                char close = ch == '[' ? ']' : ch;
                int j = i + 1;
                while (j < n && u.charAt(j) != close) j++;
                i = j + 1;
            } else if (Character.isLetter(ch) || ch == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(u.charAt(j)) || u.charAt(j) == '_')) j++;
                out.add(u.substring(i, j));
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    private static IllegalArgumentException unterminated(String what, String script, int at) {
        int line = 1;
        for (int k = 0; k < at && k < script.length(); k++) if (script.charAt(k) == '\n') line++;
        return new IllegalArgumentException("unterminated " + what + " starting at line " + line + ": "
                + abbreviate(script.substring(at)));
    }

    private static String abbreviate(String s) {
        String t = s.strip().replace('\n', ' ');
        return t.length() <= 60 ? t : t.substring(0, 60) + "...";
    }

    private static void add(List<String> out, String stmt) {
        String s = stmt.strip();
        if (!s.isEmpty()) out.add(s);
    }
}
