package com.lifeos.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Transient;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("memos")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Memo extends AuditedEntity {

    /** FK → {@link Person}. */
    @Column("owner_id")
    private Long ownerId;

    @Column("title")
    private String title;

    @Column("body")
    private String body;

    @Column("kind")
    private MemoKind kind;

    @Column("status")
    private MemoStatus status;

    @Column("priority")
    private int priority;

    @Column("due_at")
    private String dueAt;

    @Column("timezone")
    private String timezone;

    @Column("cron_expr")
    private String cronExpr;

    @Column("cron_tz")
    private String cronTz;

    @Column("source_domain")
    private String sourceDomain;

    @Column("source_table")
    private String sourceTable;

    @Column("source_id")
    private Long sourceId;

    @Column("payload_json")
    private String payloadJson;

    @Column("automation_id")
    private String automationId;

    @Column("last_fired_at")
    private String lastFiredAt;

    @Transient
    public AggregateReference<Person, Long> ownerRef() {
        return ownerId == null ? null : AggregateReference.to(ownerId);
    }
}
