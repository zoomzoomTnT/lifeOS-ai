package com.lifeos.security;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON error bodies + RFC 6750 WWW-Authenticate challenges for the API chain. */
public final class ApiErrors {
    private ApiErrors() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String REALM = "lifeos";

    public static void write(HttpServletResponse res, int status, Map<String, ?> body) throws IOException {
        if (res.isCommitted()) return;
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setHeader("Cache-Control", "no-store");
        res.getWriter().write(JSON.writeValueAsString(body));
    }

    public static Map<String, Object> body(String error, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("message", message);
        return m;
    }

    /** {@code Bearer realm="lifeos"[, error="...", error_description="..."][, scope="..."]}. */
    public static String challenge(String error, String description, String scope) {
        StringBuilder sb = new StringBuilder("Bearer realm=\"" + REALM + "\"");
        if (error != null) sb.append(", error=\"").append(error).append('"');
        if (description != null) sb.append(", error_description=\"").append(description.replace("\"", "'")).append('"');
        if (scope != null) sb.append(", scope=\"").append(scope).append('"');
        return sb.toString();
    }
}
