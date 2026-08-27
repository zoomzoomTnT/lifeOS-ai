package com.lifeos.repo.jpa;

import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.domain.Utc;
import com.lifeos.repo.ReceiptRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@Transactional
public class JpaReceiptRepository implements ReceiptRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<Receipt> findByFingerprint(String fingerprint) {
        List<Receipt> rows = em.createQuery(
                        "select r from Receipt r where r.fingerprint = :fp", Receipt.class)
                .setParameter("fp", fingerprint)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<Receipt> findById(long id) {
        return Optional.ofNullable(em.find(Receipt.class, id));
    }

    @Override
    public List<Receipt> list(ReceiptStatus status, int limit) {
        var q = status == null
                ? em.createQuery("select r from Receipt r order by r.id desc", Receipt.class)
                : em.createQuery("select r from Receipt r where r.status = :st order by r.id desc", Receipt.class)
                        .setParameter("st", status);
        return q.setMaxResults(limit).getResultList();
    }

    @Override
    public List<Receipt> stalePending(long payerId, int olderThanHours) {
        return em.createQuery("""
                        select r from Receipt r
                        where r.payerId = :payer and r.status = :st and r.createdAt <= :cutoff
                        """, Receipt.class)
                .setParameter("payer", payerId)
                .setParameter("st", ReceiptStatus.PENDING_CONFIRM)
                .setParameter("cutoff", Utc.minusHours(olderThanHours))
                .setMaxResults(5)
                .getResultList();
    }

    @Override
    public long insertPending(Receipt receipt, String rawOcrJson, String imagePath, int taxCents, int discountCents) {
        receipt.setRawOcrJson(rawOcrJson);
        receipt.setImagePath(imagePath);
        receipt.setTaxCents(taxCents);
        receipt.setDiscountCents(discountCents);
        if (receipt.getStatus() == null) {
            receipt.setStatus(ReceiptStatus.PENDING_CONFIRM);
        }
        em.persist(receipt);
        em.flush();
        return receipt.getId();
    }

    @Override
    public void insertItem(long receiptId, ReceiptItem item, int sortOrder) {
        item.setReceiptId(receiptId);
        item.setSortOrder(sortOrder);
        Receipt parent = em.getReference(Receipt.class, receiptId);
        item.setReceipt(parent);
        em.persist(item);
    }

    @Override
    public List<ReceiptItem> foodItems(long receiptId) {
        return em.createQuery("""
                        select i from ReceiptItem i
                        where i.receiptId = :rid and i.food = true
                        """, ReceiptItem.class)
                .setParameter("rid", receiptId)
                .getResultList();
    }

    @Override
    public void markConfirmed(long id) {
        Receipt receipt = em.find(Receipt.class, id);
        if (receipt != null) {
            receipt.setStatus(ReceiptStatus.CONFIRMED);
            receipt.setConfirmedAt(Utc.now());
        }
    }
}
