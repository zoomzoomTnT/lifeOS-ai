package com.lifeos.security;

import com.lifeos.receipts.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A configured key is enforced in every profile, including `local`. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ApiKeyAuthHttpTest {

    static final String KEY = "test-api-key-0123456789abcdef0123456789";
    static final Path DB;

    static {
        try {
            DB = TestDb.create();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("life.api-key", () -> KEY);
        r.add("lifeos.openclaw.enabled", () -> "false");
        r.add("life.proactive.cron", () -> "-");
        r.add("life.logs.ingest-ms", () -> "86400000");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) b.header("Authorization", authorization);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Raw HTTP/1.1 so the path reaches Tomcat byte-for-byte (no client-side normalization). */
    int rawStatus(String rawPath) throws Exception {
        try (Socket s = new Socket("localhost", port)) {
            OutputStream out = s.getOutputStream();
            out.write(("GET " + rawPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII); // "HTTP/1.1 200"
            return Integer.parseInt(status.substring(9, 12));
        }
    }

    @Test
    void noKeyIs401WithJsonAndChallenge() throws Exception {
        HttpResponse<String> r = get("/api/ops/summary?hours=1", null);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"lifeos\"");
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(r.body()).contains("\"error\":\"unauthorized\"").doesNotContain(KEY);
    }

    @Test
    void wrongKeyIs401InvalidToken() throws Exception {
        HttpResponse<String> r = get("/api/ops/summary?hours=1", "Bearer " + KEY + "x");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"lifeos\", error=\"invalid_token\"");
        assertThat(r.body()).doesNotContain(KEY);
        assertThat(get("/api/ops/summary?hours=1", "Bearer short").statusCode()).isEqualTo(401);
        assertThat(get("/api/ops/summary?hours=1", "Basic " + KEY).statusCode()).isEqualTo(401);
        assertThat(get("/api/ops/summary?hours=1", KEY).statusCode()).isEqualTo(401);
        assertThat(get("/api/ops/summary?hours=1", "Bearer ").statusCode()).isEqualTo(401);
    }

    @Test
    void rightKeyIs200() throws Exception {
        assertThat(get("/api/ops/summary?hours=1", "Bearer " + KEY).statusCode()).isEqualTo(200);
        assertThat(get("/api/ops/summary?hours=1", "bearer " + KEY).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/info", "Bearer " + KEY).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/db", "Bearer " + KEY).statusCode()).isEqualTo(200);
    }

    @Test
    void healthIsOpenOtherActuatorIsNot() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/info", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/db", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/mappings", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/healthx", null).statusCode()).isEqualTo(401);
    }

    @Test
    void staticAndDocsArePublic() throws Exception {
        assertThat(get("/ops/index.html", null).statusCode()).isEqualTo(200);
        assertThat(get("/v3/api-docs", null).statusCode()).isEqualTo(200);
    }

    @Test
    void webhookPingIsLeftToItsOwnTokenCheck() throws Exception {
        // OpenClaw is off here, so the endpoint itself answers 404 openclaw_disabled, not our 401.
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/ops/webhook/ping"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("openclaw_disabled");
    }

    @Test
    void pathTricksDoNotBypassAuth() throws Exception {
        for (String p : new String[]{
                "/actuator/health/../db",
                "/actuator/health/..;/db",
                "/actuator/health;x=1/../info",
                "/actuator/health/%2e%2e/db",
                "/ops/../api/ops/summary",
                "/ops/..;/api/ops/summary",
                "//api/ops/summary",
                "/api;x=1/ops/summary",
                "/%61pi/ops/summary",
                "/api/ops/webhook/ping/../../ops/summary",
                "/api/ops/webhook/ping;/../../ops/summary",
        }) {
            assertThat(rawStatus(p)).as(p).isNotEqualTo(200);
        }
        assertThat(rawStatus("/actuator/health")).isEqualTo(200);
    }
}
