package com.lifeos.cli;

import com.lifeos.security.ApiKeyAuthenticationProvider;
import com.lifeos.security.ApiKeyAuthenticationToken;
import com.lifeos.security.ApiKeyFormat;
import com.lifeos.security.ApiKeyStore;
import com.lifeos.security.AuthProperties;
import com.lifeos.security.KeyHasher;
import com.lifeos.security.LifePrincipal;
import com.lifeos.security.SecurityEventLog;
import com.lifeos.testsupport.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifeAdminTest {

    static final String PEPPER = "cli-test-pepper-0123456789abcdef0123";
    Path db;
    Map<String, String> env;
    JdbcTemplate jdbc;
    String out, err;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.createPlain();
        env = Map.of("LIFE_DB", db.toString(), "SPRING_PROFILES_ACTIVE", "local", "LIFE_KEY_PEPPER", PEPPER,
                "LIFE_DOMAIN", "life.example.com");
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(new org.sqlite.JDBC(), "jdbc:sqlite:" + db));
    }

    int admin(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int code = LifeAdmin.run(args, env, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
        return code;
    }

    LifePrincipal auth(String key) {
        var p = new ApiKeyAuthenticationProvider(new ApiKeyStore(jdbc), new KeyHasher(PEPPER),
                new SecurityEventLog(jdbc, Clock.systemUTC()), Clock.systemUTC());
        return (LifePrincipal) p.authenticate(ApiKeyAuthenticationToken.unauthenticated(key)).getPrincipal();
    }

    List<String> lines() {
        return Arrays.stream(out.split("\n")).filter(s -> !s.isBlank()).toList();
    }

    @Test
    void bootstrapMintsGrokBotKeysOnceAndNeverTheOwnerLink() {
        assertThat(admin("bootstrap")).isZero();
        List<String> l = lines();
        assertThat(l).hasSize(2);
        assertThat(out).doesNotContain("/login/ott");
        assertThat(err).contains("owner-login");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM one_time_tokens", Long.class)).isZero();
        LifePrincipal chat = auth(l.get(0)), routine = auth(l.get(1));
        assertThat(chat.clientId()).isEqualTo("grokbot-chat");
        assertThat(chat.scopes()).contains("finance:write", "fridge:write", "memos:write", "stocks:write", "ops:write", "ops:read")
                .doesNotContain("ops:admin");
        assertThat(routine.clientId()).isEqualTo("grokbot-routine");
        assertThat(routine.scopes()).containsExactlyInAnyOrder("memos:read", "ops:read");
        // keys never go to stderr, and nothing secret is stored in clear text
        assertThat(err).doesNotContain(l.get(0)).doesNotContain(l.get(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_keys WHERE secret_hash LIKE ?", Long.class,
                "%" + ApiKeyFormat.parse(l.get(0)).secret() + "%")).isZero();

        // idempotent: a second run mints no keys; with a passkey registered, no link either
        jdbc.update("INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) "
                + "VALUES ('c1','u1',x'00',0,0,'test')");
        assertThat(admin("bootstrap")).isZero();
        assertThat(lines()).isEmpty();
        assertThat(err).contains("already has 1 active key").contains("passkey registered");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_keys", Long.class)).isEqualTo(2);
    }

    @Test
    void envFormatAndRotation() {
        assertThat(admin("bootstrap", "--format", "env")).isZero();
        assertThat(lines().get(0)).startsWith("LIFEOS_GROKBOT_CHAT_KEY=lok_");
        assertThat(lines().get(1)).startsWith("LIFEOS_GROKBOT_ROUTINE_KEY=lok_");
        String first = lines().get(1).substring(lines().get(1).indexOf('=') + 1);

        assertThat(admin("mint-key", "grokbot-routine")).isZero();
        String second = lines().get(0);
        assertThatThrownBy(() -> admin("mint-key", "grokbot-routine")).hasMessageContaining("already has 2 active keys");
        assertThat(admin("mint-key", "grokbot-routine", "--rotate")).isZero();
        assertThatThrownBy(() -> auth(first)).hasMessageContaining("revoked");
        auth(second);

        String keyId = ApiKeyFormat.parse(second).keyId();
        assertThat(admin("revoke-key", keyId)).isZero();
        assertThatThrownBy(() -> auth(second)).hasMessageContaining("revoked");
        assertThatThrownBy(() -> admin("revoke-key", keyId)).hasMessageContaining("no active key");

        assertThat(admin("list")).isZero();
        assertThat(out).contains("grokbot-routine").contains(keyId).doesNotContain("lok_");
    }

    @Test
    void createClientRefusesAdminForAgentsButAllowsAServiceKey() {
        assertThatThrownBy(() -> admin("create-client", "evil", "--kind", "agent", "--scopes", "ops:admin"))
                .hasMessageContaining("never granted to an agent");
        assertThat(admin("create-client", "deploy-check", "--kind", "service", "--scopes", "ops:admin")).isZero();
        assertThat(admin("mint-key", "deploy-check", "--ttl-days", "0")).isZero();
        assertThat(auth(lines().get(0)).scopes()).contains("ops:admin");
        assertThatThrownBy(() -> admin("mint-key", "deploy-check", "--scopes", "nope:x")).hasMessageContaining("unknown scope");
    }

    @Test
    void ownerLoginOnlyBeforeTheFirstPasskeyUnlessRecovery() {
        assertThat(admin("owner-login")).isZero();
        assertThat(lines().get(0)).matches("https://life\\.example\\.com/login/ott\\?token=[0-9a-f-]{36}");
        assertThat(err).doesNotContain(lines().get(0).substring(lines().get(0).indexOf('=') + 1));
        assertThat(admin("owner-login")).isZero(); // replaces the previous token
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM one_time_tokens", Long.class)).isEqualTo(1);
        jdbc.update("INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) "
                + "VALUES ('c1','u1',x'00',0,0,'test')");
        assertThatThrownBy(() -> admin("owner-login")).hasMessageContaining("--recovery");
        assertThat(admin("owner-login", "--recovery")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_events WHERE type='owner_login_token_created'", Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_events WHERE outcome='recovery'", Long.class)).isEqualTo(1);
    }

    @Test
    void refusesWithoutPepperOrDatabase() {
        env = Map.of("LIFE_DB", db.toString());
        assertThatThrownBy(() -> admin("list")).hasMessageContaining("LIFE_KEY_PEPPER");
        env = Map.of("LIFE_KEY_PEPPER", PEPPER);
        assertThatThrownBy(() -> admin("list")).hasMessageContaining("no database");
        // local profile without LIFE_KEY_PEPPER uses the same dev pepper as application-local.yml
        env = Map.of("LIFE_DB", db.toString(), "SPRING_PROFILES_ACTIVE", "local");
        assertThat(admin("list")).isZero();
        assertThat(AuthProperties.LOCAL_DEV_PEPPER).isEqualTo("local-dev-pepper-not-a-secret-000000");
    }

    @Test
    void helpWorks() {
        assertThat(admin("help")).isZero();
        assertThat(err).contains("bootstrap").contains("mint-key");
    }
}
