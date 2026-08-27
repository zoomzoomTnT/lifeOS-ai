package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Market implements DbEnum {
    US("US"), HK("HK"), CN("CN");

    private final String db;

    Market(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static Market from(String v) { return DbEnum.of(Market.class, v); }

    public String defaultCurrency() {
        return switch (this) {
            case US -> "USD";
            case HK -> "HKD";
            case CN -> "CNY";
        };
    }
}
