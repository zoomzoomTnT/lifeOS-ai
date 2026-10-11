package com.lifeos.security;

import com.lifeos.cli.LifeAdmin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 bootstrap end to end over HTTP: CLI one-time link → OTT session → register a passkey
 * (software authenticator) → passkey sign-in → owner pages, /api via the session (CSRF on writes).
 * The stock Spring Security JDBC repositories run on the test DB (SQLite here; FakeTurso / sqld in
 * AuthStores*Test).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OwnerPasskeyHttpTest extends OwnerHttpTestBase {

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_credentials");
        jdbc.update("DELETE FROM user_entities");
        jdbc.update("DELETE FROM one_time_tokens");
    }

    @Test
    @Order(1)
    void bootstrapFirstPasskeyThenSignInWithIt() throws Exception {
        OwnerBrowser b = new OwnerBrowser(port);
        // nothing works before signing in
        assertThat(b.get("/admin").statusCode()).isEqualTo(302);
        assertThat(b.get("/admin").headers().firstValue("Location").orElse("")).endsWith("/login");

        HttpResponse<String> ott = useLink(b, ownerLink());
        assertThat(ott.statusCode()).isEqualTo(302);
        assertThat(ott.headers().firstValue("Location").orElse("")).endsWith("/webauthn/register");
        assertThat(count("SELECT COUNT(*) FROM one_time_tokens")).isZero(); // consumed

        // an OTT session may manage passkeys, nothing else (no ops, no approvals, no API)
        assertThat(b.get("/webauthn/register").statusCode()).isEqualTo(200);
        assertThat(b.get("/admin").statusCode()).isEqualTo(302);
        assertThat(b.send("GET", "/api/ops/summary?hours=1", null, false).statusCode()).isEqualTo(401);

        SoftPasskey pk = registerPasskey(b, "test laptop");
        assertThat(count("SELECT COUNT(*) FROM user_credentials")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_entities WHERE name = 'owner'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='passkey_registered'")).isPositive();

        // fresh browser: passkey only
        OwnerBrowser b2 = new OwnerBrowser(port);
        HttpResponse<String> login = passkeySignIn(b2, pk);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertThat(login.body()).contains("\"authenticated\":true");
        assertThat(b2.get("/admin").statusCode()).isEqualTo(200);
        assertThat(b2.get("/ops/index.html").statusCode()).isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='owner_login' AND credential_id='passkey'")).isPositive();
        assertThat(count("SELECT signature_count FROM user_credentials")).isEqualTo(1);

        // the session reaches /api as the owner; writes need the CSRF token
        HttpResponse<String> ops = b2.send("GET", "/api/ops/summary?hours=1", null, false);
        assertThat(ops.statusCode()).as(ops.body()).isEqualTo(200);
        String memo = "{\"title\":\"from the owner session\",\"kind\":\"reminder\"}";
        assertThat(b2.send("POST", "/api/memos", memo, false).statusCode()).isEqualTo(403);
        HttpResponse<String> w = b2.send("POST", "/api/memos", memo, true);
        assertThat(w.statusCode()).as(w.body()).isIn(200, 201);
        assertThat(count("SELECT COUNT(*) FROM http_requests WHERE client_id='owner-web' AND credential_id='passkey' AND path='/api/memos'")).isPositive();

        // logout ends it
        assertThat(b2.postForm("/logout", "_csrf=" + b2.csrf).statusCode()).isEqualTo(302);
        assertThat(b2.get("/admin").statusCode()).isEqualTo(302);
    }

    @Test
    @Order(2)
    void oneTimeLinkIsSingleUseAndRefusedOnceAPasskeyExists() throws Exception {
        String link = ownerLink();
        OwnerBrowser b = new OwnerBrowser(port);
        useLink(b, link);
        registerPasskey(b, "k1");

        // replaying the same link in another browser fails
        OwnerBrowser thief = new OwnerBrowser(port);
        HttpResponse<String> replay = useLink(thief, link);
        assertThat(replay.statusCode()).isEqualTo(302);
        assertThat(replay.headers().firstValue("Location").orElse("")).contains("error");
        assertThat(thief.get("/webauthn/register").statusCode()).isEqualTo(302);

        // the CLI won't hand out another bootstrap link once a passkey exists, only --recovery
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ownerLink())
                .hasMessageContaining("passkey is already registered");
        assertThat(ownerLink("--recovery")).contains("/login/ott?token=");
    }

    @Test
    @Order(3)
    void expiredLinkAndForgedAssertionsFail() throws Exception {
        String link = ownerLink();
        jdbc.update("UPDATE one_time_tokens SET expires_at = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)));
        OwnerBrowser b = new OwnerBrowser(port);
        HttpResponse<String> r = useLink(b, link);
        assertThat(r.headers().firstValue("Location").orElse("")).contains("error");
        assertThat(b.get("/webauthn/register").statusCode()).isEqualTo(302);

        // a valid registration, then an assertion from a different key with the same credential id
        OwnerBrowser owner = new OwnerBrowser(port);
        useLink(owner, ownerLink("--recovery"));
        SoftPasskey real = registerPasskey(owner, "real");
        SoftPasskey forged = new SoftPasskey(ORIGIN);
        System.arraycopy(real.credentialId, 0, forged.credentialId, 0, 32);
        forged.userHandle = real.userHandle;
        OwnerBrowser attacker = new OwnerBrowser(port);
        HttpResponse<String> bad = passkeySignIn(attacker, forged);
        assertThat(bad.statusCode()).isEqualTo(401);
        assertThat(attacker.get("/admin").statusCode()).isEqualTo(302);

        // wrong origin (phishing site) is rejected even with the real key
        OwnerBrowser p = new OwnerBrowser(port);
        assertThat(passkeySignIn(p, new SoftPasskeyOrigin(real, "https://evil.example")).statusCode()).isEqualTo(401);
    }

    /** The real key, but the browser reports another origin. */
    static final class SoftPasskeyOrigin extends SoftPasskey {
        SoftPasskeyOrigin(SoftPasskey real, String origin) throws Exception {
            super(origin, real);
        }
    }

    @Test
    @Order(4)
    void webTokenGenerationAndPasswordLoginDoNotExist() throws Exception {
        OwnerBrowser b = new OwnerBrowser(port);
        b.refreshCsrf();
        assertThat(b.postForm("/ott/generate", "username=owner&_csrf=" + b.csrf).statusCode()).isIn(403, 404);
        assertThat(count("SELECT COUNT(*) FROM one_time_tokens")).isZero();
        assertThat(b.postForm("/login", "username=owner&password=x&_csrf=" + b.csrf).statusCode()).isIn(403, 404, 405);
        String page = b.get("/login").body();
        assertThat(page).contains("passkey").doesNotContain("password").doesNotContain("ott/generate");
    }
}
