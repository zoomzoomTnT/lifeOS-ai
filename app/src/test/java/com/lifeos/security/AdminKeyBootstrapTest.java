package com.lifeos.security;

import com.lifeos.LifeOsApplication;
import com.lifeos.testsupport.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AdminKeyBootstrap}: the admin client and its only key come from LIFE_ADMIN_KEY_ID +
 * LIFE_ADMIN_KEY_SHA256 at startup. Real app restarts on one database file.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminKeyBootstrapTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static ConfigurableApplicationContext boot(Path db, ApiKeyFormat.Generated admin) {
        return bootRaw(db, admin.keyId(), KeyHasher.sha256Hex(admin.secret()));
    }

    static ConfigurableApplicationContext bootRaw(Path db, String keyId, String sha256Hex) {
        return new SpringApplicationBuilder(LifeOsApplication.class).run("--spring.profiles.active=local",
                "--LIFE_DB=" + db, "--life.auth.local-open=false", "--server.port=0", "--life.openclaw.enabled=false",
                "--lifeos.openclaw.enabled=false", "--life.proactive.cron=-", "--life.logs.ingest-ms=86400000",
                "--life.auth.admin-key-id=" + keyId, "--life.auth.admin-key-sha256=" + sha256Hex);
    }

    static int status(ConfigurableApplicationContext ctx, String key) throws Exception {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/credentials"))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    static long count(ConfigurableApplicationContext ctx, String sql, Object... args) {
        Long n = ctx.getBean(JdbcTemplate.class).queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    static Throwable root(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    @Test
    void createsOnFirstStartIsANoOpOnRestartAndAChangedSecretReplacesTheKey(CapturedOutput out) throws Exception {
        Path db = TestDb.createPlain();
        ApiKeyFormat.Generated first = ApiKeyFormat.generate(), second = ApiKeyFormat.generate();

        try (ConfigurableApplicationContext ctx = boot(db, first)) {
            assertThat(count(ctx, "SELECT COUNT(*) FROM api_clients WHERE client_id='lifeos-admin' AND kind='admin'")).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM api_keys WHERE key_id=? AND revoked_at IS NULL AND expires_at IS NULL",
                    first.keyId())).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM security_events WHERE type='client_created' AND client_id='lifeos-admin' "
                    + "AND detail_json LIKE '%deploy_secret%'")).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM security_events WHERE type='key_created' AND credential_id=? "
                    + "AND detail_json LIKE '%deploy_secret%'", first.keyId())).isEqualTo(1);
            assertThat(status(ctx, first.fullKey())).isEqualTo(200);
        }
        try (ConfigurableApplicationContext ctx = boot(db, first)) { // same env: nothing changes
            assertThat(count(ctx, "SELECT COUNT(*) FROM api_keys k JOIN api_clients c ON c.id = k.api_client_id "
                    + "WHERE c.kind='admin'")).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM security_events WHERE type IN ('key_created','key_revoked','client_created') "
                    + "AND (client_id='lifeos-admin' OR credential_id=?)", first.keyId())).isEqualTo(2);
            assertThat(status(ctx, first.fullKey())).isEqualTo(200);
        }
        try (ConfigurableApplicationContext ctx = boot(db, second)) { // rotated secret: old key dead at startup
            assertThat(status(ctx, first.fullKey())).isEqualTo(401);
            assertThat(status(ctx, second.fullKey())).isEqualTo(200);
            assertThat(count(ctx, "SELECT COUNT(*) FROM security_events WHERE type='key_revoked' AND credential_id=? "
                    + "AND detail_json LIKE '%deploy_secret%'", first.keyId())).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM api_keys k JOIN api_clients c ON c.id = k.api_client_id "
                    + "WHERE c.kind='admin' AND k.revoked_at IS NULL")).isEqualTo(1);
        }
        // rollback: the immediately previous key id comes back, the current one is revoked
        try (ConfigurableApplicationContext ctx = boot(db, first)) {
            assertThat(status(ctx, first.fullKey())).isEqualTo(200);
            assertThat(status(ctx, second.fullKey())).isEqualTo(401);
            assertThat(count(ctx, "SELECT COUNT(*) FROM security_events WHERE type='key_reactivated' AND credential_id=? "
                    + "AND detail_json LIKE '%deploy_secret%' AND detail_json LIKE '%rollback%'", first.keyId())).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM security_events WHERE type='key_revoked' AND credential_id=? "
                    + "AND detail_json LIKE '%rolled_back%'", second.keyId())).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM api_keys k JOIN api_clients c ON c.id = k.api_client_id "
                    + "WHERE c.kind='admin' AND k.revoked_at IS NULL")).isEqualTo(1);
        }
        try (ConfigurableApplicationContext ctx = boot(db, first)) { // restart after rollback: no change
            assertThat(count(ctx, "SELECT COUNT(*) FROM security_events WHERE type='key_reactivated'")).isEqualTo(1);
            assertThat(status(ctx, first.fullKey())).isEqualTo(200);
        }
        // the previous key's id with a different secret is refused
        assertThatThrownBy(() -> bootRaw(db, second.keyId(), KeyHasher.sha256Hex("other")).close())
                .satisfies(e -> assertThat(root(e).getMessage()).contains("already used"));
        ApiKeyFormat.Generated third = ApiKeyFormat.generate();
        try (ConfigurableApplicationContext ctx = boot(db, third)) {
            assertThat(status(ctx, third.fullKey())).isEqualTo(200);
            assertThat(status(ctx, first.fullKey())).isEqualTo(401);
        }
        // second is now older than the immediately previous key (first): refused
        assertThatThrownBy(() -> boot(db, second).close())
                .satisfies(e -> assertThat(root(e).getMessage()).contains("older admin key"));

        for (ApiKeyFormat.Generated k : new ApiKeyFormat.Generated[]{first, second, third}) {
            assertThat(out.getAll()).doesNotContain(k.secret()).doesNotContain(k.fullKey());
        }
        assertThat(out.getAll()).contains(third.keyId());
    }

    static final String[] PROD = {"--spring.datasource.url=jdbc:libsql:http://127.0.0.1:9", "--server.port=0",
            "--life.openclaw.enabled=false", "--life.proactive.cron=-", "--logging.level.com.lifeos=WARN"};

    static void prod(String id, String sha) {
        String[] args = new String[PROD.length + 2];
        System.arraycopy(PROD, 0, args, 0, PROD.length);
        args[PROD.length] = "--life.auth.admin-key-id=" + id;
        args[PROD.length + 1] = "--life.auth.admin-key-sha256=" + sha;
        new SpringApplicationBuilder(LifeOsApplication.class).run(args).close();
    }

    @Test
    void productionRefusesToStartWithoutAWellFormedAdminKey(CapturedOutput out) {
        String sha = KeyHasher.sha256Hex("x");
        assertThatThrownBy(() -> prod("", ""))
                .satisfies(e -> assertThat(root(e).getMessage()).contains("LIFE_ADMIN_KEY_ID and LIFE_ADMIN_KEY_SHA256 are not set"));
        assertThatThrownBy(() -> prod("abcdefghijkl", ""))
                .satisfies(e -> assertThat(root(e).getMessage()).contains("Set both"));
        assertThatThrownBy(() -> prod("not-a-key-id", sha))
                .satisfies(e -> assertThat(root(e).getMessage()).contains("LIFE_ADMIN_KEY_ID must be"));
        assertThatThrownBy(() -> prod("abcdefghijkl", sha.toUpperCase()))
                .satisfies(e -> assertThat(root(e).getMessage()).contains("LIFE_ADMIN_KEY_SHA256 must be"));
        // a raw key pasted into the hash variable is refused, and not echoed back
        String raw = ApiKeyFormat.generate().fullKey();
        assertThatThrownBy(() -> prod("abcdefghijkl", raw))
                .satisfies(e -> assertThat(root(e).getMessage()).contains("LIFE_ADMIN_KEY_SHA256 must be").doesNotContain(raw));
        assertThat(out.getAll()).doesNotContain(raw);
    }

    @Test
    void localProfileMayOmitTheAdminKey() {
        assertThat(AuthSettingsCheck.adminKeyProblem(null, null, true)).isNull();
        assertThat(AuthSettingsCheck.adminKeyProblem("", " ", false)).contains("not set");
        assertThat(AuthSettingsCheck.adminKeyProblem("abcdefghijkl", null, true)).contains("Set both");
    }
}
