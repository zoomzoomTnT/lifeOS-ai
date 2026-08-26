package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FridgeStatus implements DbEnum {
    IN_STOCK("in_stock"), EATEN("eaten"), DISCARDED("discarded"), EXPIRED("expired"), GIFTED("gifted");

    private final String db;

    FridgeStatus(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static FridgeStatus from(String v) { return DbEnum.of(FridgeStatus.class, v); }
}
