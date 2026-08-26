package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("stock_events")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class StockEvent extends BaseEntity {

    @Column("holding_id")
    private Long holdingId;

    @Column("kind")
    private String kind;

    @Column("event_date")
    private String eventDate;

    @Column("notes")
    private String notes;

    /** FK → {@link Memo}. */
    @Column("memo_id")
    private Long memoId;

    @Column("created_at")
    private String createdAt;
}
