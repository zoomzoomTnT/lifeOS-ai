package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Transient;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Table("receipts")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Receipt extends AuditedEntity {

    /** FK → {@link Merchant}. */
    @Column("merchant_id")
    private Long merchantId;

    /** FK → {@link Person} (expense owner). */
    @Column("payer_id")
    private Long payerId;

    @Column("barcode")
    private String barcode;

    @Column("printed_at")
    private String printedAt;

    @Column("fingerprint")
    private String fingerprint;

    @Column("currency")
    private String currency;

    @Column("total_cents")
    private Integer totalCents;

    @Column("computed_cents")
    private Integer computedCents;

    @Column("tax_cents")
    private Integer taxCents;

    @Column("discount_cents")
    private Integer discountCents;

    @Column("status")
    private ReceiptStatus status;

    @Column("raw_ocr_json")
    private String rawOcrJson;

    @Column("image_path")
    private String imagePath;

    @Column("notes")
    private String notes;

    @Column("confirmed_at")
    private String confirmedAt;

    @MappedCollection(idColumn = "receipt_id", keyColumn = "sort_order")
    private List<ReceiptItem> items = new ArrayList<>();

    @MappedCollection(idColumn = "receipt_id")
    private Set<ReceiptClaim> claims = new LinkedHashSet<>();

    @Transient
    public AggregateReference<Merchant, Long> merchantRef() {
        return merchantId == null ? null : AggregateReference.to(merchantId);
    }

    @Transient
    public AggregateReference<Person, Long> payerRef() {
        return payerId == null ? null : AggregateReference.to(payerId);
    }
}
