package com.lifeos.security;

import com.lifeos.testsupport.TestDb;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Whole app over real HTTP (random port), `local` profile on a fresh SQLite file, with
 * local-open OFF so every request is authenticated exactly like production, and the test admin key
 * ({@link com.lifeos.testsupport.TestAdminKey}) configured the way CD does it (key id + SHA-256).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = "life.auth.local-open=false")
public abstract class AuthHttpTestBase {

    static final Path DB;

    static {
        try {
            DB = TestDb.createPlain();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("life.auth.writes-per-minute", () -> "1000");
        r.add("life.openclaw.enabled", () -> "false");
        r.add("lifeos.openclaw.enabled", () -> "false");
        r.add("life.proactive.cron", () -> "-");
        r.add("life.logs.ingest-ms", () -> "86400000");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApiKeyService keys;

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A fresh client of the given kind with a single key; returns the full key. */
    String newKey(ClientKind kind, List<String> scopes) {
        String id = "test-" + kind.name().toLowerCase() + "-" + uniq();
        keys.createClient(id, id, kind, scopes);
        return keys.mint(id, null, 90, false).key();
    }

    String adminKey() {
        return com.lifeos.testsupport.TestAdminKey.FULL;
    }

    String chatKey() {
        return newKey(ClientKind.AGENT, Scopes.GROKBOT_CHAT);
    }

    String routineKey() {
        return newKey(ClientKind.AGENT, Scopes.GROKBOT_ROUTINE);
    }

    HttpResponse<String> send(String method, String path, String key, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) b.header("Content-Type", "application/json");
        if (key != null) b.header("Authorization", "Bearer " + key);
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path, String key) throws Exception {
        return send("GET", path, key, null);
    }

    HttpResponse<String> post(String path, String key, String json, String... headers) throws Exception {
        return send("POST", path, key, json, headers);
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
