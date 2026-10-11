package com.lifeos.security;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A cookie-keeping, non-redirecting HTTP client acting as the owner's browser. */
final class OwnerBrowser {

    private static final Pattern HIDDEN_CSRF = Pattern.compile("name=\"_csrf\" (?:type=\"hidden\" )?value=\"([^\"]+)\"");
    private static final Pattern JS_CSRF = Pattern.compile("\\{\"X-CSRF-TOKEN\":\"([^\"]+)\"\\}");

    final int port;
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http;
    String csrf;

    OwnerBrowser(int port) {
        this.port = port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    HttpResponse<String> get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = HIDDEN_CSRF.matcher(r.body());
        if (m.find()) csrf = m.group(1);
        m = JS_CSRF.matcher(r.body());
        if (m.find()) csrf = m.group(1);
        return r;
    }

    /** Refresh the CSRF token from the login page (tokens rotate when the session changes). */
    String refreshCsrf() throws Exception {
        get("/login");
        return csrf;
    }

    HttpResponse<String> postForm(String path, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(String method, String path, String json, boolean withCsrf) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) b.header("Content-Type", "application/json");
        if (withCsrf && csrf != null) b.header("X-CSRF-TOKEN", csrf);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postJson(String path, String json) throws Exception {
        return send("POST", path, json, true);
    }
}
