package com.lifeos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "merchants")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Merchant extends AuditedEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "name_norm", nullable = false, unique = true)
    private String nameNorm;

    @Column(name = "kind", nullable = false)
    private MerchantKind kind;

    @Column(name = "location_tag", nullable = false)
    private LocationTag locationTag;

    @Column(name = "favorite_score", nullable = false)
    private Double favoriteScore;

    @Column(name = "notes")
    private String notes;
}
