package com.lifeos.security;

import com.lifeos.testsupport.TestAdminKey;
import com.lifeos.testsupport.TursoSpringTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production profile (no `local`), as on zoomzoom's server behind Caddy: there is no human web login
 * at all (no session cookie, no login/passkey/approval pages), and the admin key from the deploy
 * secrets (here the test admin key from config/application.properties) is the only way into /api/admin/**.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProdDomainHttpTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TursoSpringTestBase.tursoProps(r);
        r.add("lifeos.openclaw.enabled", () -> "false");
        r.add("life.auth.pepper", () -> "prod-test-pepper-0123456789abcdef0123456789");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    HttpResponse<String> send(String method, String path, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (key != null) b.header("Authorization", "Bearer " + key);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noLoginPagesAndNoSessionCookieEver() throws Exception {
        for (String p : List.of("/login", "/logout", "/approve/apr_x", "/webauthn/authenticate/options",
                "/login/webauthn", "/ott/generate", "/admin", "/ops")) {
            for (String m : List.of("GET", "POST")) {
                HttpResponse<String> r = send(m, p, null);
                assertThat(r.statusCode()).as(m + " " + p).isEqualTo(403);
                assertThat(r.headers().allValues("Set-Cookie")).as(m + " " + p).isEmpty();
            }
        }
        HttpResponse<String> api = send("GET", "/api/admin/credentials", TestAdminKey.FULL);
        assertThat(api.statusCode()).as(api.body()).isEqualTo(200);
        assertThat(api.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void theAdminKeyFromTheDeploySecretsWorksAndNothingElseGetsIn() throws Exception {
        HttpResponse<String> ok = send("GET", "/api/admin/credentials", TestAdminKey.FULL);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(ok.body()).contains("lifeos-admin").contains(TestAdminKey.KEY_ID).doesNotContain(TestAdminKey.SECRET);
        assertThat(send("GET", "/api/admin/credentials", null).statusCode()).isEqualTo(401);
        String wrongSecret = ApiKeyFormat.PREFIX + TestAdminKey.KEY_ID + "_" + ApiKeyFormat.generate().secret();
        assertThat(send("GET", "/api/admin/credentials", wrongSecret).statusCode()).isEqualTo(401);
    }
}
