package com.lifeos.ops;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST the Life OS custom webhook on the Gateway:
 *   {base}{hooks.path}/{hook-name}  →  default POST /hooks/life-os
 *
 * Mapping (hooks.mappings) owns action/name/sessionMode/deliver/channel.
 * This client sends message + to (+ optional model).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenClawClient {

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${life.openclaw.base-url:http://localhost:18789}")
    private String baseUrl;

    @Value("${life.openclaw.hook-token:}")
    private String hookToken;

    @Value("${life.openclaw.hooks-path:/hooks}")
    private String hooksPath;

    @Value("${life.openclaw.hook-name:life-os}")
    private String hookName;

    @Value("${life.openclaw.channel:openclaw-weixin}")
    private String channel;

    @Value("${life.openclaw.model:}")
    private String model;

    public String hookUrl() {
        return trimSlash(baseUrl) + normalizePath(hooksPath) + "/" + hookName;
    }

    /** Same secret as Gateway hooks.token. Empty configured token → reject. */
    public boolean hookTokenMatches(String authorization, String openclawTokenHeader) {
        String presented = presentedToken(authorization, openclawTokenHeader);
        if (hookToken == null || hookToken.isBlank() || presented == null) return false;
        byte[] a = hookToken.getBytes(StandardCharsets.UTF_8);
        byte[] b = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    static String presentedToken(String authorization, String openclawTokenHeader) {
        if (authorization != null) {
            String v = authorization.trim();
            if (v.length() >= 7 && v.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String tok = v.substring(7).trim();
                if (!tok.isEmpty()) return tok;
            }
        }
        if (openclawTokenHeader != null && !openclawTokenHeader.isBlank()) {
            return openclawTokenHeader.trim();
        }
        return null;
    }

    public Map<String, Object> wakeProactive(String message, String to) {
        Map<String, Object> out = new LinkedHashMap<>();
        String url = hookUrl();
        out.put("url", url);
        if (hookToken == null || hookToken.isBlank()) {
            out.put("ok", false);
            out.put("skipped", "OPENCLAW_HOOK_TOKEN unset");
            log.warn("skip OpenClaw webhook: OPENCLAW_HOOK_TOKEN unset");
            return out;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", message);
            if (to != null && !to.isBlank()) body.put("to", to);
            if (model != null && !model.isBlank()) body.put("model", model);
            body.put("timeoutSeconds", 90);

            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + hookToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            out.put("ok", res.statusCode() >= 200 && res.statusCode() < 300);
            out.put("status", res.statusCode());
            out.put("body", truncate(res.body()));
            log.info("OpenClaw {} status={} channel={}", url, res.statusCode(), channel);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            log.warn("OpenClaw webhook failed: {}", e.getMessage());
        }
        return out;
    }

    static String normalizePath(String raw) {
        if (raw == null || raw.isBlank() || "/".equals(raw.trim())) {
            return "/hooks";
        }
        String p = raw.trim();
        if (!p.startsWith("/")) p = "/" + p;
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
