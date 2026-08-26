package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("people")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Person extends AuditedEntity {

    /** OpenClaw WeChat peer id. Unique. */
    @Column("handle")
    private String handle;

    @Column("display_name")
    private String displayName;

    @Column("role")
    private PersonRole role;

    @Column("timezone")
    private String timezone;
}
