package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("merchants")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Merchant extends AuditedEntity {

    @Column("name")
    private String name;

    @Column("name_norm")
    private String nameNorm;

    @Column("kind")
    private MerchantKind kind;

    @Column("location_tag")
    private LocationTag locationTag;

    @Column("favorite_score")
    private Double favoriteScore;

    @Column("notes")
    private String notes;
}
