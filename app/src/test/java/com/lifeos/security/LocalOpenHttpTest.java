package com.lifeos.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** `local` profile with local-open (the developer default): no key needed, a bad key still fails. */
@org.springframework.test.context.TestPropertySource(properties = "life.auth.local-open=true")
class LocalOpenHttpTest extends AuthHttpTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    AuthProperties props;

    @Test
    void requestsWithoutCredentialsActAsTheOwner() throws Exception {
        assertThat(props.localOpen()).isTrue();
        assertThat(get("/api/memos/due", null).statusCode()).isEqualTo(200);
        assertThat(post("/api/memos", null, "{\"title\":\"local " + uniq() + "\",\"kind\":\"reminder\"}").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/db", null).statusCode()).isEqualTo(200);
    }

    @Test
    void aPresentedKeyIsStillVerified() throws Exception {
        assertThat(get("/api/memos/due", ApiKeyFormat.generate().fullKey()).statusCode()).isEqualTo(401);
        String routine = routineKey();
        assertThat(post("/api/fridge", routine, "{\"name\":\"x\"}").statusCode()).isEqualTo(403);
    }
}
