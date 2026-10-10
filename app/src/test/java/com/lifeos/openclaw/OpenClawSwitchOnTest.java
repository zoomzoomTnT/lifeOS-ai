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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Default (switch not set): existing OpenClaw behavior is unchanged. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class OpenClawSwitchOnTest extends AbstractSwitchTest {

    static final Path DB = newDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("life.openclaw.enabled", () -> "false"); // cron wake off, so nothing is sent
        r.add("life.openclaw.hook-token", () -> "secret");
    }

    @Test
    void openClawBeansAndSchedulesPresent() {
        assertEquals(1, ctx.getBeanNamesForType(OpenClawClient.class).length);
        assertEquals(1, ctx.getBeanNamesForType(ProactiveCronService.class).length);
        assertEquals(1, ctx.getBeanNamesForType(OpenClawLogIngestJob.class).length);
        String tasks = scheduledTasks();
        assertTrue(tasks.contains("ProactiveCronService.tick"), tasks);
        assertTrue(tasks.contains("OpenClawLogIngestJob.tick"), tasks);
    }

    @Test
    void openClawEndpointsBehaveAsBefore() throws Exception {
        HttpResponse<String> ping = post("/api/ops/webhook/ping", "{}");
        assertEquals(401, ping.statusCode(), ping.body()); // no token presented
        HttpResponse<String> run = post("/api/ops/proactive/run", "{}");
        assertEquals(200, run.statusCode(), run.body());
        assertTrue(run.body().contains("LIFE_OPENCLAW_WAKE=false"), run.body());
        HttpResponse<String> ingest = post("/api/ops/logs/ingest", "{}");
        assertEquals(200, ingest.statusCode(), ingest.body());
        assertTrue(ingest.body().contains("session_rows"), ingest.body());
        assertTrue(get("/actuator/health").body().contains("\"openclaw\":\"enabled\""));
    }
}
