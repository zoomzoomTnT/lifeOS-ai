package com.lifeos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "receipt_items")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ReceiptItem extends BaseEntity {

    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", insertable = false, updatable = false)
    private Receipt receipt;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "name_norm", nullable = false)
    private String nameNorm;

    @Column(name = "qty", nullable = false)
    private double qty;

    @Column(name = "unit")
    private String unit;

    @Column(name = "amount_cents", nullable = false)
    private int amountCents;

    @Column(name = "is_food", nullable = false)
    private boolean food;

    @Column(name = "category")
    private FoodCategory category;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Utc.now();
        }
        if (receipt != null && receiptId == null) {
            receiptId = receipt.getId();
        }
    }
}
