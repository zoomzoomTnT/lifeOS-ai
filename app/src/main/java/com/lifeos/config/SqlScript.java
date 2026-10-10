package com.lifeos.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits a SQLite script into statements. Unlike {@code String.split(";")} it understands
 * {@code -- line} and {@code /* block *}{@code /} comments (comments are dropped, so a ';' inside a
 * comment does not end a statement), quoted strings/identifiers ({@code '..'}, {@code ".."},
 * {@code `..`}, {@code [..]}) and {@code CREATE TRIGGER ... BEGIN ...; ...; END} bodies.
 */
public final class SqlScript {
    private SqlScript() {}

    private static final Pattern TRIGGER = Pattern.compile("^CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER\\b");
    private static final Pattern ENDS_WITH_END = Pattern.compile("\\bEND$");

    public static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = script.length();
        int i = 0;
        while (i < n) {
            char ch = script.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                char close = ch == '[' ? ']' : ch;
                int j = i + 1;
                while (j < n) {
                    if (script.charAt(j) == close) {
                        // doubled quote = escaped quote (not for [..])
                        if (close != ']' && j + 1 < n && script.charAt(j + 1) == close) {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                int end = Math.min(j + 1, n);
                cur.append(script, i, end);
                i = end;
            } else if (ch == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                while (i < n && script.charAt(i) != '\n') i++;
                cur.append('\n');
            } else if (ch == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                cur.append(' ');
            } else if (ch == ';') {
                String stmt = cur.toString().strip();
                if (isOpenTrigger(stmt)) {
                    cur.append(';');
                } else {
                    if (!stmt.isEmpty()) out.add(stmt);
                    cur.setLength(0);
                }
                i++;
            } else {
                cur.append(ch);
                i++;
            }
        }
        String last = cur.toString().strip();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    /** Inside a trigger body until the statement text ends with END. */
    private static boolean isOpenTrigger(String stmt) {
        String u = stmt.toUpperCase(Locale.ROOT);
        return TRIGGER.matcher(u).find() && !ENDS_WITH_END.matcher(u).find();
    }
}
