package com.lifeos.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Lenient parser for OpenClaw session / trajectory / gateway JSONL lines. */
@Component
@RequiredArgsConstructor
public final class JsonlParser {

    public static final int CONTENT_CAP = 32_768;

    public record Parsed(
            String occurredAt,
            String eventType,
            String role,
            String content,
            String sessionId,
            String agentId,
            String sessionKey,
            String usageJson,
            boolean conversation
    ) {}

    private final ObjectMapper mapper;

    public Parsed parse(String line) {
        if (line == null || line.isBlank()) return null;
        JsonNode n;
        try {
            n = mapper.readTree(line);
        } catch (Exception e) {
            return null;
        }
        if (n == null || !n.isObject()) return null;

        String eventType = firstText(n, "type", "event", "kind");
        String occurred = firstText(n, "timestamp", "time", "ts", "created_at", "at");
        JsonNode msg = n.get("message");
        String role = firstText(n, "role");
        if (role == null && msg != null) role = firstText(msg, "role");
        String content = extractContent(n, msg);
        String sessionId = firstText(n, "session_id", "sessionId", "id");
        String agentId = firstText(n, "agent_id", "agentId");
        String sessionKey = firstText(n, "session_key", "sessionKey", "key");
        String usage = usage(n, msg);
        boolean conversation = role != null
                || (eventType != null && (eventType.equals("message")
                || eventType.startsWith("prompt.")
                || eventType.contains("transcript")));
        if (occurred == null && eventType == null && content == null) return null;
        return new Parsed(occurred, eventType, role, content, sessionId, agentId, sessionKey, usage, conversation);
    }

    private static String extractContent(JsonNode n, JsonNode msg) {
        if (msg != null) {
            JsonNode c = msg.get("content");
            String fromMsg = flattenContent(c);
            if (fromMsg == null) fromMsg = firstText(msg, "text", "message");
            if (fromMsg != null) return cap(fromMsg);
        }
        String direct = flattenContent(n.get("content"));
        if (direct == null) direct = firstText(n, "text", "message");
        return cap(direct);
    }

    private static String flattenContent(JsonNode c) {
        if (c == null || c.isNull()) return null;
        if (c.isTextual()) return c.asText();
        if (c.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode el : c) {
                if (el == null) continue;
                if (el.isTextual()) parts.add(el.asText());
                else {
                    String t = firstText(el, "text", "content");
                    if (t != null) parts.add(t);
                }
            }
            return parts.isEmpty() ? null : String.join("\n", parts);
        }
        return firstText(c, "text", "content");
    }

    private String usage(JsonNode n, JsonNode msg) {
        JsonNode u = n.get("usage");
        if (u == null && msg != null) u = msg.get("usage");
        if (u == null && msg != null) u = msg.path("usage");
        if (u == null || u.isMissingNode() || u.isNull()) return null;
        try {
            return mapper.writeValueAsString(u);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(JsonNode n, String... keys) {
        if (n == null) return null;
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static String cap(String s) {
        if (s == null) return null;
        if (s.length() <= CONTENT_CAP) return s;
        return s.substring(0, CONTENT_CAP) + "…";
    }
}
