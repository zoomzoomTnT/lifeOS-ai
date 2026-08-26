package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ReceiptStatus implements DbEnum {
    PENDING_CONFIRM("pending_confirm"), CONFIRMED("confirmed"), REJECTED("rejected"), DUPLICATE("duplicate");

    private final String db;

    ReceiptStatus(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static ReceiptStatus from(String v) { return DbEnum.of(ReceiptStatus.class, v); }
}
