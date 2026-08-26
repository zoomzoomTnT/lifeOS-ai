package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.util.LinkedHashSet;
import java.util.Set;

@Table("holdings")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Holding extends AuditedEntity {

    @Column("owner_id")
    private Long ownerId;

    @Column("symbol")
    private String symbol;

    @Column("market")
    private String market;

    @Column("name")
    private String name;

    @Column("qty")
    private double qty;

    @Column("avg_cost")
    private Double avgCost;

    @Column("currency")
    private String currency;

    @Column("notes")
    private String notes;

    @MappedCollection(idColumn = "holding_id")
    private Set<StockEvent> events = new LinkedHashSet<>();
}
