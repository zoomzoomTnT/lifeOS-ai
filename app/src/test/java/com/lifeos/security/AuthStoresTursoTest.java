package com.lifeos.security;

import com.lifeos.testsupport.TursoSpringTestBase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Auth stores over the libSQL driver against FakeTurso (Hrana over a SQLite file). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthStoresTursoTest extends AbstractAuthStoresTest {

    @DynamicPropertySource
    static void turso(DynamicPropertyRegistry r) {
        TursoSpringTestBase.tursoProps(r);
    }
}
