package com.lifeos.security;

import com.lifeos.LifeOsApplication;
import com.lifeos.testsupport.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Production (default profile) refuses to start without a pepper; local-open is local-only. */
class AuthStartupTest {

    static final String[] QUIET = {"--life.openclaw.enabled=false", "--life.proactive.cron=-",
            "--life.logs.ingest-ms=86400000", "--logging.level.com.lifeos=WARN", "--server.port=0"};

    static ConfigurableApplicationContext boot(String... args) {
        String[] all = new String[QUIET.length + args.length];
        System.arraycopy(QUIET, 0, all, 0, QUIET.length);
        System.arraycopy(args, 0, all, QUIET.length, args.length);
        return new SpringApplicationBuilder(LifeOsApplication.class).web(WebApplicationType.NONE).run(all);
    }

    static Throwable root(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    @Test
    void defaultProfileWithoutPepperDoesNotStart() {
        // A syntactically valid DB URL so DatabaseSettingsCheck passes and ours is what stops startup.
        assertThatThrownBy(() -> boot("--spring.datasource.url=jdbc:libsql:http://127.0.0.1:9", "--life.auth.pepper="))
                .satisfies(e -> assertThat(root(e).getMessage()).contains("LIFE_KEY_PEPPER is not set"));
        assertThatThrownBy(() -> boot("--spring.datasource.url=jdbc:libsql:http://127.0.0.1:9", "--life.auth.pepper=short"))
                .satisfies(e -> assertThat(root(e).getMessage()).contains("too short"));
    }

    @Test
    void localOpenOutsideLocalProfileDoesNotStart() {
        assertThatThrownBy(() -> boot("--spring.datasource.url=jdbc:libsql:http://127.0.0.1:9", "--life.auth.local-open=true"))
                .satisfies(e -> assertThat(root(e).getMessage()).contains("only allowed with the `local` profile"));
    }

    @Test
    void localProfileStartsWithoutAnySecret() throws Exception {
        Path db = TestDb.createPlain();
        try (ConfigurableApplicationContext ctx = boot("--spring.profiles.active=local", "--LIFE_DB=" + db,
                "--life.auth.pepper=")) {
            // application-local.yml supplies a dev pepper when LIFE_KEY_PEPPER is unset
            assertThat(ctx.getBean(AuthProperties.class).pepper()).isNotBlank();
        } catch (IllegalStateException e) {
            // --life.auth.pepper= (blank, highest precedence) must still fail even under local
            assertThat(root(e).getMessage()).contains("LIFE_KEY_PEPPER");
            try (ConfigurableApplicationContext ctx = boot("--spring.profiles.active=local", "--LIFE_DB=" + db)) {
                assertThat(ctx.getBean(AuthProperties.class).localOpen()).isTrue();
            }
        }
    }
}
