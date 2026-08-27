package com.lifeos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "fridge_items")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class FridgeItem extends AuditedEntity {

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", insertable = false, updatable = false)
    private Person owner;

    @Column(name = "added_by_id", nullable = false)
    private Long addedById;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_id", insertable = false, updatable = false)
    private Person addedBy;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "name_norm", nullable = false)
    private String nameNorm;

    @Column(name = "category")
    private FoodCategory category;

    @Column(name = "location", nullable = false)
    private FridgeLocation location;

    @Column(name = "status", nullable = false)
    private FridgeStatus status;

    @Column(name = "qty", nullable = false)
    private double qty;

    @Column(name = "unit")
    private String unit;

    @Column(name = "purchased_at")
    private String purchasedAt;

    @Column(name = "expires_at")
    private String expiresAt;

    @Column(name = "preference")
    private Integer preference;

    @Column(name = "source_receipt_id")
    private Long sourceReceiptId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_receipt_id", insertable = false, updatable = false)
    private Receipt sourceReceipt;

    @Column(name = "source_receipt_item_id")
    private Long sourceReceiptItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_receipt_item_id", insertable = false, updatable = false)
    private ReceiptItem sourceReceiptItem;

    @Column(name = "notes")
    private String notes;
}
