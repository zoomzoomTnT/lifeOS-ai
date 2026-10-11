package com.lifeos.security;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production profile (no `local`) with an sslip.io LIFE_DOMAIN, as on zoomzoom's server: the session
 * cookie is a __Host- cookie (Secure, Path=/, no Domain) and the passkey RP ID / origin come from
 * LIFE_DOMAIN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProdDomainHttpTest {

    static final String DOMAIN = "203-0-113-5.sslip.io";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TursoSpringTestBase.tursoProps(r);
        r.add("lifeos.openclaw.enabled", () -> "false");
        r.add("life.auth.pepper", () -> "prod-test-pepper-0123456789abcdef0123456789");
        r.add("life.auth.domain", () -> DOMAIN);
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void sessionCookieIsHostPrefixedAndPasskeyOptionsUseTheDomain() throws Exception {
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        List<String> cookies = login.headers().allValues("Set-Cookie");
        String session = cookies.stream().filter(c -> c.startsWith("__Host-LIFEOS_SESSION=")).findFirst().orElseThrow(
                () -> new AssertionError("no __Host-LIFEOS_SESSION cookie in " + cookies));
        assertThat(session).contains("; Path=/").contains("; Secure").contains("; HttpOnly").containsIgnoringCase("SameSite=Strict")
                .doesNotContainIgnoringCase("Domain=");

        Matcher m = Pattern.compile("\"X-CSRF-TOKEN\":\"([^\"]+)\"()").matcher(login.body());
        assertThat(m.find()).as(login.body()).isTrue();
        String csrf = m.group(1) != null ? m.group(1) : m.group(2);
        // the client is on plain http here, so send the (Secure) cookie by hand
        HttpResponse<String> opts = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webauthn/authenticate/options"))
                .header("Cookie", session.substring(0, session.indexOf(';')))
                .header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(opts.statusCode()).as(opts.body()).isEqualTo(200);
        assertThat(opts.body()).contains("\"rpId\":\"" + DOMAIN + "\"");
    }

    @Test
    void rpIdAndOriginsDeriveFromLifeDomain() {
        var p = new AuthProperties("x".repeat(40), false, 60, " 203-0-113-5.SSLIP.io. ", null);
        assertThat(OwnerWebConfig.rpId(p)).isEqualTo("203-0-113-5.sslip.io");
        assertThat(OwnerWebConfig.origins(p)).containsExactly("https://203-0-113-5.sslip.io");
        assertThat(p.baseUrl()).isEqualTo("https://203-0-113-5.sslip.io");
        var withPublic = new AuthProperties("x".repeat(40), false, 60, DOMAIN, "https://" + DOMAIN + "/");
        assertThat(OwnerWebConfig.origins(withPublic)).containsExactly("https://" + DOMAIN);
        assertThat(withPublic.baseUrl()).isEqualTo("https://" + DOMAIN);
        var none = new AuthProperties("x".repeat(40), false, 60, "", null);
        assertThat(OwnerWebConfig.rpId(none)).isEqualTo("localhost");
    }

    @Test
    void lifeDomainMustBeABareHost() {
        assertThat(AuthProperties.domainProblem(DOMAIN)).isNull();
        assertThat(AuthProperties.domainProblem("life.example.com")).isNull();
        assertThat(AuthProperties.domainProblem("")).isNull();
        for (String bad : List.of("https://" + DOMAIN, DOMAIN + "/", DOMAIN + ":443", "life example.com", "-x.example.com")) {
            assertThat(AuthProperties.domainProblem(bad)).as(bad).isNotNull();
        }
    }
}
