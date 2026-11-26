package com.lifeos.repo.jpa;

import com.lifeos.domain.Person;
import com.lifeos.domain.PersonRole;
import com.lifeos.repo.PersonRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@Transactional
public class JpaPersonRepository implements PersonRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<Long> findIdByHandle(String handle) {
        List<Long> rows = em.createQuery(
                        "select p.id from Person p where p.handle = :h", Long.class)
                .setParameter("h", handle)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<String> findOwnerHandle() {
        List<String> rows = em.createQuery(
                        "select p.handle from Person p where p.role = :role", String.class)
                .setParameter("role", PersonRole.OWNER)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    @Override
    public Optional<Long> findOwnerId() {
        List<Long> rows = em.createQuery(
                        "select p.id from Person p where p.role = :role order by p.id", Long.class)
                .setParameter("role", PersonRole.OWNER)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
