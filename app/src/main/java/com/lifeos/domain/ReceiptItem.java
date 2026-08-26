package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Transient;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("receipt_items")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ReceiptItem extends BaseEntity {

    /** FK → {@link Receipt}. Also the {@code @MappedCollection} join on the parent. */
    @Column("receipt_id")
    private Long receiptId;

    @Column("name")
    private String name;

    @Column("name_norm")
    private String nameNorm;

    @Column("qty")
    private double qty;

    @Column("unit")
    private String unit;

    @Column("amount_cents")
    private int amountCents;

    @Column("is_food")
    private boolean food;

    @Column("category")
    private FoodCategory category;

    @Column("sort_order")
    private int sortOrder;

    @Column("created_at")
    private String createdAt;

    @Transient
    public AggregateReference<Receipt, Long> receiptRef() {
        return receiptId == null ? null : AggregateReference.to(receiptId);
    }
}
