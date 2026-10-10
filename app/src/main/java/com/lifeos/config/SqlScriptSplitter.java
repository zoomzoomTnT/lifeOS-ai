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
 *   <li>the body of {@code CREATE [TEMP] TRIGGER ... BEGIN ... END}</li>
 * </ul>
 * Comments are dropped from the output (a line comment becomes a newline, a block comment a
 * space), so a statement that was only comments is not returned. Returned statements are
 * trimmed and have no trailing {@code ;}.
 */
public final class SqlScriptSplitter {

    private static final Pattern TRIGGER_START =
            Pattern.compile("(?is)^\\s*CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER\\b.*");
    private static final Pattern ENDS_WITH_END = Pattern.compile("(?is).*\\bEND\\s*$");

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
                cur.append(ch);
                i++;
                while (i < n) {
                    char c = script.charAt(i);
                    cur.append(c);
                    i++;
                    if (c == ch) {
                        if (i < n && script.charAt(i) == ch) {
                            cur.append(ch);
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (ch == '[') {
                int close = script.indexOf(']', i + 1);
                int end = close < 0 ? n : close + 1;
                cur.append(script, i, end);
                i = end;
            } else if (ch == '-' && next == '-') {
                int nl = script.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                cur.append('\n');
            } else if (ch == '/' && next == '*') {
                int close = script.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
                cur.append(' ');
            } else if (ch == ';') {
                i++;
                String stmt = cur.toString();
                if (TRIGGER_START.matcher(stmt).matches() && !ENDS_WITH_END.matcher(stmt).matches()) {
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
        add(out, cur.toString());
        return out;
    }

    private static void add(List<String> out, String stmt) {
        String s = stmt.strip();
        if (!s.isEmpty()) out.add(s);
    }
}
