package com.lifeos.repo.jpa;

import com.lifeos.domain.LocationTag;
import com.lifeos.domain.Merchant;
import com.lifeos.domain.MerchantKind;
import com.lifeos.repo.MerchantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@Transactional
public class JpaMerchantRepository implements MerchantRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<Long> findIdByNameNorm(String nameNorm) {
        List<Long> rows = em.createQuery(
                        "select m.id from Merchant m where m.nameNorm = :n", Long.class)
                .setParameter("n", nameNorm)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public long insert(String name, String nameNorm) {
        Merchant merchant = new Merchant();
        merchant.setName(name);
        merchant.setNameNorm(nameNorm);
        merchant.setKind(MerchantKind.OTHER);
        merchant.setLocationTag(LocationTag.OTHER);
        merchant.setFavoriteScore(0.0);
        em.persist(merchant);
        em.flush();
        return merchant.getId();
    }
}
