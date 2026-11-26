package com.lifeos.ops;

import com.lifeos.testsupport.TursoSpringTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** recordAi returns the id produced by INSERT ... RETURNING id (Turso path). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OpsServiceRecordAiTest extends TursoSpringTestBase {

    @Autowired
    OpsService ops;

    @Test
    void recordAiReturnsTheReturningId() throws Exception {
        int before = TURSO.executed.size();
        Map<String, Object> first = ops.recordAi(Map.of("provider", "xai", "model", "grok-4",
                "prompt_tokens", 1000, "completion_tokens", 0, "correlation_id", "t-1"), 1L);
        Map<String, Object> second = ops.recordAi(Map.of("provider", "xai", "model", "grok-4",
                "prompt_tokens", 10, "correlation_id", "t-2"), 1L);

        long id1 = ((Number) first.get("id")).longValue();
        long id2 = ((Number) second.get("id")).longValue();
        assertEquals(id1, count("SELECT id FROM ai_calls WHERE correlation_id = 't-1'"));
        assertEquals(id2, count("SELECT id FROM ai_calls WHERE correlation_id = 't-2'"));
        assertEquals(id1 + 1, id2);
        assertTrue(TURSO.executed.subList(before, TURSO.executed.size()).stream()
                .anyMatch(s -> s.contains("INSERT INTO ai_calls") && s.contains("RETURNING id")));
        // no separate last_insert_rowid() round trip
        assertTrue(TURSO.executed.subList(before, TURSO.executed.size()).stream()
                .noneMatch(s -> s.toLowerCase().contains("last_insert_rowid")));
    }

    @Test
    void missingProviderIsRejectedWithoutWriting() throws Exception {
        long before = count("SELECT COUNT(*) FROM ai_calls");
        assertEquals("provider_and_model_required", ops.recordAi(Map.of("model", "m"), 1L).get("error"));
        assertEquals(before, count("SELECT COUNT(*) FROM ai_calls"));
    }
}
