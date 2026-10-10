package com.lifeos.repo.jpa;

import com.lifeos.testsupport.FakeTursoServer;
import com.lifeos.testsupport.SqlCapture;
import com.lifeos.testsupport.TestDb;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** @DataJpaTest through the libSQL driver (default profile settings) against the Hrana fake. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaIdentityReturningTursoTest extends AbstractJpaIdentityReturningTest {

    static final FakeTursoServer TURSO;

    static {
        try {
            TURSO = new FakeTursoServer(TestDb.createPlain());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        TURSO.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TURSO::url);
        r.add("spring.datasource.password", () -> "t");
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", SqlCapture.class::getName);
    }
}
