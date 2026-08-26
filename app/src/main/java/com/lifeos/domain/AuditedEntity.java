package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;

/** Tables with created_at / updated_at TEXT (UTC ISO-8601). */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public abstract class AuditedEntity extends BaseEntity {

    @Column("created_at")
    private String createdAt;

    @Column("updated_at")
    private String updatedAt;
}
