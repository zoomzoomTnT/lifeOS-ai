package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

/** SQLite INTEGER PRIMARY KEY. Subclasses are mutable so MapStruct and RowMappers can fill them. */
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class BaseEntity {

    @Id
    @Column("id")
    @EqualsAndHashCode.Include
    private Long id;

    public boolean isNew() {
        return id == null;
    }
}
