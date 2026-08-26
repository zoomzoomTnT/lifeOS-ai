package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MerchantKind implements DbEnum {
    SUPERMARKET("supermarket"), RESTAURANT("restaurant"), CAFE("cafe"),
    MARKET("market"), OTHER("other");

    private final String db;

    MerchantKind(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static MerchantKind from(String v) { return DbEnum.of(MerchantKind.class, v); }
}
