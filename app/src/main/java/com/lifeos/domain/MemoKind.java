package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoKind implements DbEnum {
    REMINDER("reminder"), FOLLOWUP("followup"), EXPIRY("expiry"), OPTIONS("options"),
    RESTOCK("restock"), BRIEF("brief"), CUSTOM("custom");

    private final String db;

    MemoKind(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static MemoKind from(String v) { return DbEnum.of(MemoKind.class, v); }
}
