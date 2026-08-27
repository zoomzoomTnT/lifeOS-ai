package com.lifeos.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "holdings")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Holding extends AuditedEntity {

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", insertable = false, updatable = false)
    private Person owner;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "market", nullable = false)
    private Market market;

    @Column(name = "name")
    private String name;

    @Column(name = "qty", nullable = false)
    private double qty;

    @Column(name = "avg_cost")
    private Double avgCost;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "notes")
    private String notes;

    @OneToMany(mappedBy = "holding", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<StockEvent> events = new LinkedHashSet<>();
}
