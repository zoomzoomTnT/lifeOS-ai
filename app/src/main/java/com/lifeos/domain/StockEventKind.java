package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum StockEventKind implements DbEnum {
    OPTIONS_EXPIRY("options_expiry"),
    EARNINGS("earnings"),
    DIVIDEND("dividend"),
    CUSTOM("custom");

    private final String db;

    StockEventKind(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static StockEventKind from(String v) { return DbEnum.of(StockEventKind.class, v); }
}
