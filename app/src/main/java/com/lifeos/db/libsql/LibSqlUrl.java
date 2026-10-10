package com.lifeos.db.libsql;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Parsed libSQL connection URL. Accepted forms (all mean the same server):
 * <ul>
 *   <li>{@code jdbc:libsql:https://<db>.turso.io} (canonical)</li>
 *   <li>{@code jdbc:dbeaver:libsql:https://<db>.turso.io} (old DBeaver-driver form, still accepted)</li>
 *   <li>{@code libsql://<db>.turso.io} / {@code jdbc:libsql://<db>.turso.io} (Turso CLI form, means https)</li>
 *   <li>{@code jdbc:libsql:http://localhost:8080} (local sqld)</li>
 * </ul>
 * An optional {@code ?authToken=...} query parameter is accepted; the pool password wins over it.
 */
public record LibSqlUrl(String httpBase, String authTokenFromUrl) {

    private static final String[] PREFIXES = {"jdbc:dbeaver:libsql:", "jdbc:libsql:", ""};

    public static boolean accepts(String url) {
        if (url == null) return false;
        String u = url.strip();
        return u.startsWith("jdbc:libsql:") || u.startsWith("jdbc:dbeaver:libsql:") || u.startsWith("libsql://");
    }

    public static LibSqlUrl parse(String url) {
        if (!accepts(url)) {
            throw new IllegalArgumentException("Not a libSQL URL: " + url);
        }
        String u = url.strip();
        for (String p : PREFIXES) {
            if (!p.isEmpty() && u.startsWith(p)) {
                u = u.substring(p.length());
                break;
            }
        }
        if (u.startsWith("libsql://")) u = "https://" + u.substring("libsql://".length());
        if (u.startsWith("//")) u = "https:" + u;
        String token = null;
        int q = u.indexOf('?');
        if (q >= 0) {
            for (String kv : u.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).equals("authToken")) {
                    token = URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            u = u.substring(0, q);
        }
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        URI uri = URI.create(u);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("libSQL URL must use http(s) or libsql://: " + url);
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("libSQL URL has no host: " + url);
        }
        return new LibSqlUrl(u, token);
    }

    /**
     * Remote (TLS) servers such as Turso always need an auth token. Plain-http URLs are for a local
     * or CI sqld, which usually runs without auth.
     */
    public boolean requiresAuthToken() {
        return httpBase.startsWith("https://");
    }

    /** URL without credentials, safe to log. */
    public String display() {
        return httpBase;
    }
}
