package com.lifeos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "people")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Person extends AuditedEntity {

    @Column(name = "handle", nullable = false, unique = true)
    private String handle;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "role", nullable = false)
    private PersonRole role;

    @Column(name = "timezone", nullable = false)
    private String timezone;
}
