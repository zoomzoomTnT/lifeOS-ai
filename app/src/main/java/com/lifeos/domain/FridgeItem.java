package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Transient;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("fridge_items")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class FridgeItem extends AuditedEntity {

    /** FK → {@link Person} (food owner). */
    @Column("owner_id")
    private Long ownerId;

    /** FK → {@link Person} (who put it in). */
    @Column("added_by_id")
    private Long addedById;

    @Column("name")
    private String name;

    @Column("name_norm")
    private String nameNorm;

    @Column("category")
    private FoodCategory category;

    @Column("location")
    private FridgeLocation location;

    @Column("status")
    private FridgeStatus status;

    @Column("qty")
    private double qty;

    @Column("unit")
    private String unit;

    @Column("purchased_at")
    private String purchasedAt;

    @Column("expires_at")
    private String expiresAt;

    @Column("preference")
    private Integer preference;

    /** FK → {@link Receipt}. */
    @Column("source_receipt_id")
    private Long sourceReceiptId;

    /** FK → {@link ReceiptItem}. */
    @Column("source_receipt_item_id")
    private Long sourceReceiptItemId;

    @Column("notes")
    private String notes;

    @Transient
    public AggregateReference<Person, Long> ownerRef() {
        return ownerId == null ? null : AggregateReference.to(ownerId);
    }

    @Transient
    public AggregateReference<Person, Long> addedByRef() {
        return addedById == null ? null : AggregateReference.to(addedById);
    }

    @Transient
    public AggregateReference<Receipt, Long> sourceReceiptRef() {
        return sourceReceiptId == null ? null : AggregateReference.to(sourceReceiptId);
    }

    @Transient
    public AggregateReference<ReceiptItem, Long> sourceReceiptItemRef() {
        return sourceReceiptItemId == null ? null : AggregateReference.to(sourceReceiptItemId);
    }
}
