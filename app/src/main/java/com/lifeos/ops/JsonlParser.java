package com.lifeos.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenClaw session JSONL v3 (and leftover trajectory / gateway lines).
 *
 * Real session files look like:
 *   {"type":"session","version":3,"id":"<uuid>",...}
 *   {"type":"model_change","id":"ad7430ce","provider":"google","modelId":"..."}
 *   {"type":"message","id":"da6f557c","parentId":"...","message":{"role":"user","content":[...]}}
 *
 * The UUID lives only on {@code type=session}. Later rows use short event ids — never
 * treat those as {@code session_id}. Image parts embed JPEG base64 in {@code data};
 * thinking/toolCall carry huge {@code thoughtSignature}. Both are stripped.
 */
@Component
@RequiredArgsConstructor
public final class JsonlParser {

    public static final int CONTENT_CAP = 32_768;
    public static final int RAW_CAP = 16_384;
    static final Pattern MEDIA_PATH = Pattern.compile("\\[media attached:\\s*([^\\s\\]]+)");

    public record Parsed(
            String occurredAt,
            String eventType,
            String eventId,
            String parentId,
            String role,
            String content,
            String sessionId,
            String agentId,
            String sessionKey,
            String usageJson,
            String provider,
            String model,
            String stopReason,
            String toolName,
            String customType,
            String mediaPathsJson,
            boolean heartbeat,
            boolean conversation,
            Integer promptTokens,
            Integer completionTokens,
            Integer cacheReadTokens,
            Integer cacheWriteTokens,
            Integer totalTokens,
            Long costMicros,
            String sanitizedRawJson
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
        String eventId = firstText(n, "id");
        String parentId = firstText(n, "parentId", "parent_id");
        String occurred = firstText(n, "timestamp", "time", "ts", "created_at", "at");
        if (occurred != null && occurred.matches("\\d{10,13}")) {
            occurred = epochToIso(occurred);
        }

        JsonNode msg = n.get("message");
        String role = firstText(n, "role");
        if (role == null && msg != null) role = firstText(msg, "role");

        String sessionId = firstText(n, "session_id", "sessionId");
        if ("session".equals(eventType) && eventId != null) {
            sessionId = eventId;
        }
        String agentId = firstText(n, "agent_id", "agentId");
        String sessionKey = firstText(n, "session_key", "sessionKey", "key");

        String provider = firstText(n, "provider");
        String model = firstText(n, "modelId", "model");
        if (msg != null) {
            if (provider == null) provider = firstText(msg, "provider");
            if (model == null) model = firstText(msg, "model");
        }
        String stopReason = msg == null ? null : firstText(msg, "stopReason", "stop_reason");
        String customType = firstText(n, "customType", "custom_type");

        ContentExtracted extracted = extractContent(n, msg);
        JsonNode usageNode = usageNode(n, msg);
        UsageTokens usage = parseUsage(usageNode);

        boolean heartbeat = extracted.heartbeat
                || (extracted.text != null && extracted.text.contains("[OpenClaw heartbeat poll]"));
        boolean conversation = role != null
                || "message".equals(eventType)
                || (eventType != null && (eventType.startsWith("prompt.") || eventType.contains("transcript")));

        String toolName = extracted.toolName;
        if (toolName == null && msg != null) toolName = firstText(msg, "toolName", "tool_name");

        if (occurred == null && eventType == null && extracted.text == null) return null;

        String usageJson = null;
        if (usageNode != null) {
            try {
                usageJson = mapper.writeValueAsString(usageNode);
            } catch (Exception ignored) { }
        }

        String mediaJson = null;
        if (!extracted.mediaPaths.isEmpty()) {
            try {
                mediaJson = mapper.writeValueAsString(extracted.mediaPaths);
            } catch (Exception ignored) { }
        }

        return new Parsed(
                occurred, eventType, eventId, parentId, role, cap(extracted.text, CONTENT_CAP),
                sessionId, agentId, sessionKey, usageJson, provider, model, stopReason, toolName,
                customType, mediaJson, heartbeat, conversation,
                usage.prompt, usage.completion, usage.cacheRead, usage.cacheWrite, usage.total, usage.costMicros,
                cap(sanitizeRaw(n), RAW_CAP)
        );
    }

    private record ContentExtracted(String text, String toolName, List<String> mediaPaths, boolean heartbeat) {}

    private ContentExtracted extractContent(JsonNode n, JsonNode msg) {
        List<String> parts = new ArrayList<>();
        List<String> media = new ArrayList<>();
        String toolName = null;
        boolean heartbeat = false;

        JsonNode content = msg != null ? msg.get("content") : n.get("content");
        Flattened flat = flattenContent(content);
        parts.addAll(flat.parts);
        media.addAll(flat.media);
        if (flat.toolName != null) toolName = flat.toolName;
        heartbeat |= flat.heartbeat;

        if (msg != null) {
            collectMedia(msg.get("MediaPath"), media);
            collectMedia(msg.get("MediaPaths"), media);
            String t = firstText(msg, "text", "message");
            if (t != null && parts.isEmpty()) parts.add(t);
        }
        if (parts.isEmpty()) {
            String direct = firstText(n, "text", "message");
            if (direct != null) parts.add(direct);
            if ("thinking_level_change".equals(firstText(n, "type"))) {
                String lvl = firstText(n, "thinkingLevel");
                if (lvl != null) parts.add("thinkingLevel=" + lvl);
            }
            if ("model_change".equals(firstText(n, "type"))) {
                parts.add(firstText(n, "provider") + "/" + firstText(n, "modelId"));
            }
            if ("session".equals(firstText(n, "type"))) {
                String cwd = firstText(n, "cwd");
                if (cwd != null) parts.add("cwd=" + cwd);
            }
            if ("leaf".equals(firstText(n, "type"))) {
                parts.add("leaf targetId=" + firstText(n, "targetId") + " mode=" + firstText(n, "appendMode"));
            }
        }
        for (String p : parts) {
            Matcher m = MEDIA_PATH.matcher(p);
            while (m.find()) media.add(m.group(1));
            if (p.contains("[OpenClaw heartbeat poll]")) heartbeat = true;
        }
        String text = parts.isEmpty() ? null : String.join("\n", parts);
        return new ContentExtracted(text, toolName, media, heartbeat);
    }

    private record Flattened(List<String> parts, List<String> media, String toolName, boolean heartbeat) {}

    private Flattened flattenContent(JsonNode c) {
        List<String> parts = new ArrayList<>();
        List<String> media = new ArrayList<>();
        String toolName = null;
        boolean heartbeat = false;
        if (c == null || c.isNull()) return new Flattened(parts, media, null, false);
        if (c.isTextual()) {
            parts.add(c.asText());
            return new Flattened(parts, media, null, c.asText().contains("[OpenClaw heartbeat poll]"));
        }
        if (c.isArray()) {
            for (JsonNode el : c) {
                Flattened one = flattenContent(el);
                parts.addAll(one.parts);
                media.addAll(one.media);
                if (toolName == null) toolName = one.toolName;
                heartbeat |= one.heartbeat;
            }
            return new Flattened(parts, media, toolName, heartbeat);
        }
        if (!c.isObject()) return new Flattened(parts, media, null, false);

        String type = firstText(c, "type");
        if ("text".equals(type) || type == null) {
            String t = firstText(c, "text", "content");
            if (t != null) {
                parts.add(t);
                heartbeat = t.contains("[OpenClaw heartbeat poll]");
            }
        } else if ("thinking".equals(type)) {
            String t = firstText(c, "thinking", "text");
            if (t != null) parts.add("[thinking] " + t);
        } else if ("toolCall".equals(type) || "tool_call".equals(type)) {
            toolName = firstText(c, "name", "toolName");
            String args = compactArgs(c.get("arguments"));
            parts.add("[tool " + (toolName == null ? "?" : toolName) + "] " + (args == null ? "" : args));
        } else if ("image".equals(type)) {
            JsonNode data = c.get("data");
            int bytes = data == null || data.isNull() ? 0 : data.asText("").length();
            String mime = firstText(c, "mimeType", "mime");
            parts.add("[image mime=" + (mime == null ? "unknown" : mime) + " bytes=" + bytes + "]");
        } else {
            String t = firstText(c, "text", "content", "thinking");
            if (t != null) parts.add(t);
        }
        return new Flattened(parts, media, toolName, heartbeat);
    }

    private String compactArgs(JsonNode args) {
        if (args == null || args.isNull()) return null;
        try {
            String s = mapper.writeValueAsString(args);
            return cap(s, 2000);
        } catch (Exception e) {
            return null;
        }
    }

    private static void collectMedia(JsonNode node, List<String> media) {
        if (node == null || node.isNull()) return;
        if (node.isTextual() && !node.asText().isBlank()) media.add(node.asText());
        else if (node.isArray()) {
            for (JsonNode el : node) {
                if (el != null && el.isTextual() && !el.asText().isBlank()) media.add(el.asText());
            }
        }
    }

    private JsonNode usageNode(JsonNode n, JsonNode msg) {
        JsonNode u = n.get("usage");
        if ((u == null || u.isNull()) && msg != null) u = msg.get("usage");
        if (u == null || u.isMissingNode() || u.isNull() || !u.isObject()) return null;
        return u;
    }

    private record UsageTokens(Integer prompt, Integer completion, Integer cacheRead,
                               Integer cacheWrite, Integer total, Long costMicros) {}

    private UsageTokens parseUsage(JsonNode u) {
        if (u == null) return new UsageTokens(null, null, null, null, null, null);
        Integer prompt = intOrNull(u, "input", "prompt_tokens", "promptTokens");
        Integer completion = intOrNull(u, "output", "completion_tokens", "completionTokens");
        Integer cacheRead = intOrNull(u, "cacheRead", "cache_read", "cacheReadTokens");
        Integer cacheWrite = intOrNull(u, "cacheWrite", "cache_write", "cacheWriteTokens");
        Integer total = intOrNull(u, "totalTokens", "total_tokens", "total");
        Long costMicros = null;
        JsonNode cost = u.get("cost");
        if (cost != null && cost.isObject()) {
            JsonNode totalCost = cost.get("total");
            if (totalCost != null && totalCost.isNumber()) {
                double usd = totalCost.asDouble();
                costMicros = Math.round(usd * CostCalculator.MICROS_PER_USD);
            }
        }
        return new UsageTokens(prompt, completion, cacheRead, cacheWrite, total, costMicros);
    }

    String sanitizeRaw(JsonNode n) {
        try {
            JsonNode copy = mapper.readTree(mapper.writeValueAsString(n));
            stripSecrets(copy);
            return mapper.writeValueAsString(copy);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void stripSecrets(JsonNode n) {
        if (n == null) return;
        if (n.isArray()) {
            for (JsonNode child : n) stripSecrets(child);
            return;
        }
        if (!n.isObject()) return;
        ObjectNode obj = (ObjectNode) n;
        if ("image".equals(obj.path("type").asText("")) && obj.has("data")) {
            obj.put("data", "[omitted " + obj.path("data").asText("").length() + " chars]");
        }
        if (obj.has("thoughtSignature")) {
            obj.put("thoughtSignature", "[omitted]");
        }
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && v.isTextual() && looksLikeBinary(v.asText())
                    && !"data".equals(k) && !"thoughtSignature".equals(k)) {
                obj.put(k, "[omitted " + v.asText().length() + " chars]");
            } else {
                stripSecrets(v);
            }
        }
    }

    private static boolean looksLikeBinary(String s) {
        if (s == null || s.length() < 400) return false;
        return s.startsWith("/9j/") || s.startsWith("iVBOR") || s.startsWith("UklGR")
                || (s.length() > 4000 && s.matches("[A-Za-z0-9+/=\\s]+"));
    }

    private static Integer intOrNull(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isNumber()) return v.intValue();
        }
        return null;
    }

    private static String firstText(JsonNode n, String... keys) {
        if (n == null) return null;
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
            if (v != null && v.isNumber()) return v.asText();
        }
        return null;
    }

    private static String epochToIso(String raw) {
        try {
            long v = Long.parseLong(raw);
            if (raw.length() <= 10) v *= 1000;
            return java.time.Instant.ofEpochMilli(v).toString();
        } catch (Exception e) {
            return raw;
        }
    }

    static String cap(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    static String cap(String s) {
        return cap(s, CONTENT_CAP);
    }
}
