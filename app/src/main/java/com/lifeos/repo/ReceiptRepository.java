package com.lifeos.repo;

import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;

import java.util.List;
import java.util.Optional;

public interface ReceiptRepository {
    Optional<Receipt> findByFingerprint(String fingerprint);

    Optional<Receipt> findById(long id);

    List<Receipt> list(ReceiptStatus status, int limit);

    List<Receipt> stalePending(long payerId, int olderThanHours);


    long insertPending(Receipt receipt, String rawOcrJson, String imagePath,
                       int taxCents, int discountCents);

    void insertItem(long receiptId, ReceiptItem item, int sortOrder);

    List<ReceiptItem> foodItems(long receiptId);

    void markConfirmed(long id);
}
