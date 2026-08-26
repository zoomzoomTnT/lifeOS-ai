package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FridgeResolveAction implements DbEnum {
    EATEN("eaten"), DISCARDED("discarded"), KEEP_ONE_MORE_DAY("keep_one_more_day");

    private final String db;

    FridgeResolveAction(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static FridgeResolveAction from(String v) { return DbEnum.of(FridgeResolveAction.class, v); }
}
