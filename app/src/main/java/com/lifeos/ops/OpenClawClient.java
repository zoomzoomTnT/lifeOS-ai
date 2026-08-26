package com.lifeos.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wakes the OpenClaw agent via inbound hooks (no OpenClaw heartbeat).
 * POST {gateway}/hooks/agent
 */
@Component
public class OpenClawClient {

    private static final Logger log = LoggerFactory.getLogger(OpenClawClient.class);

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${life.openclaw.base-url:http://127.0.0.1:18789}")
    private String baseUrl;

    @Value("${life.openclaw.hook-token:}")
    private String hookToken;

    @Value("${life.openclaw.channel:openclaw-weixin}")
    private String channel;

    @Value("${life.openclaw.model:}")
    private String model;

    public OpenClawClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> wakeProactive(String message, String to) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (hookToken == null || hookToken.isBlank()) {
            out.put("ok", false);
            out.put("skipped", "OPENCLAW_HOOK_TOKEN unset");
            log.warn("skip OpenClaw wake: OPENCLAW_HOOK_TOKEN unset");
            return out;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", message);
            body.put("name", "life-os-proactive");
            body.put("sessionMode", "isolated");
            body.put("deliver", true);
            body.put("channel", channel);
            if (to != null && !to.isBlank()) body.put("to", to);
            if (model != null && !model.isBlank()) body.put("model", model);
            body.put("timeoutSeconds", 90);

            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/hooks/agent"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + hookToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            out.put("ok", res.statusCode() >= 200 && res.statusCode() < 300);
            out.put("status", res.statusCode());
            out.put("body", truncate(res.body()));
            log.info("OpenClaw /hooks/agent status={}", res.statusCode());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            log.warn("OpenClaw wake failed: {}", e.getMessage());
        }
        return out;
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
