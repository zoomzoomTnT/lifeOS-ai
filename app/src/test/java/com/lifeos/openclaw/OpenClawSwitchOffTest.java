package com.lifeos.openclaw;

import com.lifeos.ops.OpenClawClient;
import com.lifeos.ops.OpenClawLogIngestJob;
import com.lifeos.ops.ProactiveCronService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OPENCLAW_ENABLED=false: no OpenClaw beans or schedules, OpenClaw endpoints 404, rest of the API works. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class OpenClawSwitchOffTest extends AbstractSwitchTest {

    static final Path DB = newDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("lifeos.openclaw.enabled", () -> "false");
        // the old wake flag ON must not matter once the master switch is off
        r.add("life.openclaw.enabled", () -> "true");
        r.add("life.openclaw.hook-token", () -> "secret");
    }

    @Test
    void noOpenClawBeansOrSchedules() {
        assertEquals(0, ctx.getBeanNamesForType(OpenClawClient.class).length);
        assertEquals(0, ctx.getBeanNamesForType(ProactiveCronService.class).length);
        assertEquals(0, ctx.getBeanNamesForType(OpenClawLogIngestJob.class).length);
        String tasks = scheduledTasks();
        assertFalse(tasks.contains("ProactiveCronService"), tasks);
        assertFalse(tasks.contains("OpenClawLogIngestJob"), tasks);
    }

    @Test
    void openClawEndpointsSayDisabled() throws Exception {
        for (String path : new String[]{"/api/ops/webhook/ping", "/api/ops/proactive/run", "/api/ops/logs/ingest"}) {
            HttpResponse<String> r = post(path, "{}");
            assertEquals(404, r.statusCode(), path);
            assertTrue(r.body().contains("openclaw_disabled"), path + " " + r.body());
        }
    }

    @Test
    void restOfApiWorks() throws Exception {
        assertEquals(200, get("/api/ops/should-wake?lead_minutes=10").statusCode());
        HttpResponse<String> memo = post("/api/memos", "{\"title\":\"no openclaw\",\"kind\":\"reminder\"}");
        assertTrue(memo.statusCode() / 100 == 2, memo.body());
        assertEquals(200, get("/api/ops/logs/sessions?limit=5").statusCode());
        HttpResponse<String> health = get("/actuator/health");
        assertEquals(200, health.statusCode(), health.body());
        assertTrue(health.body().contains("\"openclaw\":\"disabled\""), health.body());
        assertTrue(get("/v3/api-docs/api").body().contains("OpenClaw integration is off"));
    }
}
