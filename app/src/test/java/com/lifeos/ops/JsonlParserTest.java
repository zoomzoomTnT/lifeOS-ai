package com.lifeos.ops;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlParserTest {

    private final JsonlParser parser = new JsonlParser(JsonMapper.builder().build());

    @Test
    void parsesSessionMessageWithContentArray() {
        String line = """
                {"type":"message","timestamp":"2026-08-26T00:00:01Z","message":{"role":"user","content":[{"type":"text","text":"提醒我期权"}]}}
                """;
        JsonlParser.Parsed p = parser.parse(line);
        assertEquals("message", p.eventType());
        assertEquals("user", p.role());
        assertEquals("提醒我期权", p.content());
        assertTrue(p.conversation());
        assertEquals("2026-08-26T00:00:01Z", p.occurredAt());
        assertNull(p.sessionId());
    }

    @Test
    void sessionRowUuidIsSessionIdNotLaterEventIds() {
        JsonlParser.Parsed session = parser.parse("""
                {"type":"session","version":3,"id":"a9b782f0-ac99-481c-bb29-d70cc62d268f","timestamp":"2026-08-26T04:52:15.796Z","cwd":"/home/admin/.openclaw/workspace"}
                """);
        assertEquals("session", session.eventType());
        assertEquals("a9b782f0-ac99-481c-bb29-d70cc62d268f", session.sessionId());
        assertEquals("a9b782f0-ac99-481c-bb29-d70cc62d268f", session.eventId());

        JsonlParser.Parsed msg = parser.parse("""
                {"type":"message","id":"da6f557c","parentId":"9a177e2d","timestamp":"2026-08-26T04:52:16.629Z","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}
                """);
        assertEquals("da6f557c", msg.eventId());
        assertEquals("9a177e2d", msg.parentId());
        assertNull(msg.sessionId());
    }

    @Test
    void stripsImageBytesAndThoughtSignature() {
        String jpeg = "/9j/" + "A".repeat(500);
        String line = """
                {"type":"message","id":"img1","timestamp":"2026-08-26T04:52:16.629Z","message":{"role":"user","content":[{"type":"text","text":"[media attached: /tmp/x.jpg (image/*)]"},{"type":"image","mimeType":"image/jpeg","data":"%s"}]}}
                """.formatted(jpeg);
        JsonlParser.Parsed p = parser.parse(line);
        assertTrue(p.content().contains("[image mime=image/jpeg bytes="));
        assertTrue(p.mediaPathsJson().contains("/tmp/x.jpg"));
        assertFalse(p.sanitizedRawJson().contains(jpeg));
        assertTrue(p.sanitizedRawJson().contains("[omitted"));
        assertTrue(p.sanitizedRawJson().length() < 2000);
    }

    @Test
    void parsesAssistantToolCallThinkingAndUsage() {
        String line = """
                {"type":"message","id":"d311e217","parentId":"da6f557c","timestamp":"2026-08-26T04:52:26.444Z","message":{"role":"assistant","content":[{"type":"thinking","thinking":"decode the receipt","thoughtSignature":"AAAABBBB"},{"type":"toolCall","id":"call_1","name":"exec","arguments":{"command":"python3 life.py --help"},"thoughtSignature":"SIG"},{"type":"text","text":""}],"api":"google-generative-ai","provider":"google","model":"gemini-3.1-pro-preview","usage":{"input":18394,"output":612,"cacheRead":8111,"cacheWrite":0,"totalTokens":27117,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"stopReason":"toolUse"}}
                """;
        JsonlParser.Parsed p = parser.parse(line);
        assertEquals("assistant", p.role());
        assertEquals("google", p.provider());
        assertEquals("gemini-3.1-pro-preview", p.model());
        assertEquals("toolUse", p.stopReason());
        assertEquals("exec", p.toolName());
        assertTrue(p.content().contains("[thinking]"));
        assertTrue(p.content().contains("[tool exec]"));
        assertEquals(18394, p.promptTokens());
        assertEquals(612, p.completionTokens());
        assertEquals(8111, p.cacheReadTokens());
        assertEquals(27117, p.totalTokens());
        assertEquals(0L, p.costMicros());
        assertFalse(p.sanitizedRawJson().contains("AAAABBBB"));
        assertTrue(p.sanitizedRawJson().contains("[omitted]"));
    }

    @Test
    void detectsHeartbeatPoll() {
        JsonlParser.Parsed p = parser.parse("""
                {"type":"message","id":"hb1","timestamp":"2026-08-26T07:37:07.836Z","message":{"role":"user","content":[{"type":"text","text":"[OpenClaw heartbeat poll]"}],"__openclaw":{"senderIsOwner":true}}}
                """);
        assertTrue(p.heartbeat());
        assertEquals("user", p.role());
    }

    @Test
    void parsesModelChangeAndLeaf() {
        JsonlParser.Parsed mc = parser.parse("""
                {"type":"model_change","id":"ad7430ce","parentId":null,"timestamp":"2026-08-26T04:52:15.804Z","provider":"google","modelId":"gemini-3.1-pro-preview"}
                """);
        assertEquals("model_change", mc.eventType());
        assertEquals("google", mc.provider());
        assertEquals("gemini-3.1-pro-preview", mc.model());

        JsonlParser.Parsed leaf = parser.parse("""
                {"type":"leaf","id":"d20ff501","parentId":"x","timestamp":"2026-08-26T04:52:27.090Z","targetId":"d311e217","appendMode":"side"}
                """);
        assertEquals("leaf", leaf.eventType());
        assertTrue(leaf.content().contains("d311e217"));
    }

    @Test
    void parsesTrajectoryEvent() {
        String line = """
                {"type":"prompt.submitted","timestamp":"2026-08-26T01:00:00Z","session_id":"abc","text":"hello"}
                """;
        JsonlParser.Parsed p = parser.parse(line);
        assertEquals("prompt.submitted", p.eventType());
        assertEquals("abc", p.sessionId());
        assertTrue(p.conversation());
    }

    @Test
    void gatewayLineIsNotConversation() {
        String line = """
                {"time":"2026-08-26T02:00:00Z","level":"info","message":"gateway listening","subsystem":"http"}
                """;
        JsonlParser.Parsed p = parser.parse(line);
        assertEquals("gateway listening", p.content());
        assertFalse(p.conversation());
    }
}
