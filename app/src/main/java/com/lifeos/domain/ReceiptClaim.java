package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("receipt_claims")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ReceiptClaim extends BaseEntity {

    @Column("receipt_id")
    private Long receiptId;

    /** FK → {@link Person}. */
    @Column("person_id")
    private Long personId;

    @Column("share_cents")
    private Integer shareCents;

    @Column("note")
    private String note;

    @Column("created_at")
    private String createdAt;
}
