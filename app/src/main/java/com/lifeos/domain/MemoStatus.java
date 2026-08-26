package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoStatus implements DbEnum {
    OPEN("open"), SNOOZED("snoozed"), DONE("done"), CANCELLED("cancelled");

    private final String db;

    MemoStatus(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static MemoStatus from(String v) { return DbEnum.of(MemoStatus.class, v); }
}
