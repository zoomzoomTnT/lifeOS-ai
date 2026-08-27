package com.lifeos.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "receipts")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Receipt extends AuditedEntity {

    @Column(name = "merchant_id")
    private Long merchantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false)
    private Merchant merchant;

    @Column(name = "payer_id", nullable = false)
    private Long payerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_id", insertable = false, updatable = false)
    private Person payer;

    @Column(name = "barcode")
    private String barcode;

    @Column(name = "printed_at")
    private String printedAt;

    @Column(name = "fingerprint", nullable = false, unique = true)
    private String fingerprint;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "total_cents", nullable = false)
    private Integer totalCents;

    @Column(name = "computed_cents")
    private Integer computedCents;

    @Column(name = "tax_cents")
    private Integer taxCents;

    @Column(name = "discount_cents")
    private Integer discountCents;

    @Column(name = "status", nullable = false)
    private ReceiptStatus status;

    @Column(name = "raw_ocr_json")
    private String rawOcrJson;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "notes")
    private String notes;

    @Column(name = "confirmed_at")
    private String confirmedAt;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ReceiptItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ReceiptClaim> claims = new LinkedHashSet<>();

    public void addItem(ReceiptItem item) {
        items.add(item);
        item.setReceipt(this);
        item.setReceiptId(getId());
    }
}
