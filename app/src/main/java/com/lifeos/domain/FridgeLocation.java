package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FridgeLocation implements DbEnum {
    FRIDGE("fridge"), FREEZER("freezer"), PANTRY("pantry"), COUNTER("counter");

    private final String db;

    FridgeLocation(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static FridgeLocation from(String v) { return DbEnum.of(FridgeLocation.class, v); }
}
