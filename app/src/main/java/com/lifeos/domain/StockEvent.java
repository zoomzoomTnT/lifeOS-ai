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
@Table(name = "stock_events")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class StockEvent extends BaseEntity {

    @Column(name = "holding_id", nullable = false)
    private Long holdingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id", insertable = false, updatable = false)
    private Holding holding;

    @Column(name = "kind", nullable = false)
    private StockEventKind kind;

    @Column(name = "event_date")
    private String eventDate;

    @Column(name = "notes")
    private String notes;

    @Column(name = "memo_id")
    private Long memoId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memo_id", insertable = false, updatable = false)
    private Memo memo;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Utc.now();
        }
    }
}
