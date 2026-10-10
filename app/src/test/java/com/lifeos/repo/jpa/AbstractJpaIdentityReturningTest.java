package com.lifeos.repo.jpa;

import com.lifeos.domain.Person;
import com.lifeos.domain.PersonRole;
import com.lifeos.testsupport.SqlCapture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TursoSQLiteDialect: IDENTITY ids come from INSERT ... RETURNING (no separate last_insert_rowid). */
@Transactional // test-managed transaction, rolled back after each test (also on the libSQL path)
abstract class AbstractJpaIdentityReturningTest {

    @Autowired
    EntityManager em;

    static Person person(String handle) {
        Person p = new Person();
        p.setHandle(handle);
        p.setDisplayName(handle);
        p.setRole(PersonRole.MEMBER);
        p.setTimezone("Asia/Tokyo");
        return p;
    }

    @Test
    void savingTwoEntitiesAssignsDistinctIdsFromReturning() {
        int before = SqlCapture.SQL.size();
        Person a = person("a-" + UUID.randomUUID());
        Person b = person("b-" + UUID.randomUUID());
        em.persist(a);
        em.persist(b);
        em.flush();

        assertNotNull(a.getId());
        assertNotNull(b.getId());
        assertNotEquals(a.getId(), b.getId());
        assertEquals(a.getId() + 1, b.getId());

        em.clear();
        assertEquals(a.getHandle(), em.find(Person.class, a.getId()).getHandle());
        assertEquals(b.getHandle(), em.find(Person.class, b.getId()).getHandle());

        List<String> inserts = SqlCapture.SQL.subList(before, SqlCapture.SQL.size()).stream()
                .filter(s -> s.toLowerCase().startsWith("insert into people")).toList();
        assertEquals(2, inserts.size(), inserts.toString());
        assertTrue(inserts.stream().allMatch(s -> s.toLowerCase().contains(" returning id")), inserts.toString());
        assertTrue(SqlCapture.SQL.subList(before, SqlCapture.SQL.size()).stream()
                .noneMatch(s -> s.toLowerCase().contains("last_insert_rowid")));
    }
}
