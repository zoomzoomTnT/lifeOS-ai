package com.lifeos.repo.jpa;

import com.lifeos.testsupport.SqlCapture;
import com.lifeos.testsupport.TestDb;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** @DataJpaTest on a local SQLite file (the `local` profile's driver). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaIdentityReturningSqliteTest extends AbstractJpaIdentityReturningTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String file = TestDb.createPlain().toString();
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + file);
        r.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", SqlCapture.class::getName);
    }
}
