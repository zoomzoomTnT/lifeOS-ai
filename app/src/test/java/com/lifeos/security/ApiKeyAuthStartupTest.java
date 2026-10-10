package com.lifeos.security;

import com.lifeos.LifeOsApplication;
import com.lifeos.receipts.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fail closed: no key outside the `local` profile means the app does not start. */
class ApiKeyAuthStartupTest {

    static final String KEY = "0123456789abcdef0123456789abcdef";

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    void blankKeyRefusedOutsideLocal() {
        for (String k : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> new ApiKeyAuthFilter(k, env()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LIFE_API_KEY is not set");
            assertThatThrownBy(() -> new ApiKeyAuthFilter(k, env("prod")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void shortKeyRefusedOutsideLocal() {
        assertThatThrownBy(() -> new ApiKeyAuthFilter("tooshort", env()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short")
                .hasMessageNotContaining("tooshort");
    }

    @Test
    void validKeyEnabled() {
        assertThat(new ApiKeyAuthFilter(KEY, env()).enabled()).isTrue();
    }

    @Test
    void localWithoutKeyIsDisabledLocalWithKeyIsEnforced() {
        assertThat(new ApiKeyAuthFilter("", env("local")).enabled()).isFalse();
        assertThat(new ApiKeyAuthFilter("dev", env("local")).enabled()).isTrue();
    }

    @Test
    void publicPathMatchingIsSegmentAware() {
        assertThat(ApiKeyAuthFilter.isPublic("/actuator/health")).isTrue();
        assertThat(ApiKeyAuthFilter.isPublic("/actuator/health/liveness")).isTrue();
        assertThat(ApiKeyAuthFilter.isPublic("/actuator/healthx")).isFalse();
        assertThat(ApiKeyAuthFilter.isPublic("/actuator/info")).isFalse();
        assertThat(ApiKeyAuthFilter.isPublic("/api/ops/summary")).isFalse();
        assertThat(ApiKeyAuthFilter.isPublic("/api/ops/webhook/ping")).isTrue();
        assertThat(ApiKeyAuthFilter.isPublic("/api/ops/webhook/pingx")).isFalse();
        assertThat(ApiKeyAuthFilter.isPublic("/opsx")).isFalse();
    }

    @Test
    void fullAppDoesNotStartWithoutKeyInDefaultProfile() throws Exception {
        Path db = TestDb.create();
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(LifeOsApplication.class)
                    .properties(
                            "server.port=0",
                            "spring.datasource.url=jdbc:sqlite:" + db,
                            "spring.datasource.driver-class-name=org.sqlite.JDBC",
                            "spring.datasource.password=",
                            "life.api-key=",
                            "lifeos.openclaw.enabled=false",
                            "life.proactive.cron=-")
                    .run()) {
                // must not get here
            }
        }).hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("LIFE_API_KEY is not set");
    }
}
