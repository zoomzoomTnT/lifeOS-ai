package com.lifeos.security;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Auth stores against a REAL sqld / Turso (CI: libsql-server service; LIBSQL_TEST_URL). */
@EnabledIfEnvironmentVariable(named = "LIBSQL_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthStoresRealSqldTest extends AbstractAuthStoresTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("LIBSQL_TEST_URL"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("LIBSQL_TEST_TOKEN", ""));
        r.add("life.openclaw.enabled", () -> "false");
        r.add("life.proactive.cron", () -> "-");
        r.add("life.logs.ingest-ms", () -> "86400000");
    }
}
