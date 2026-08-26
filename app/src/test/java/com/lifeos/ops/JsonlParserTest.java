package com.lifeos.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlParserTest {

    private final JsonlParser parser = new JsonlParser(new ObjectMapper());

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
