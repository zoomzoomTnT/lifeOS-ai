package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LocationTag implements DbEnum {
    HOME_NEARBY("home_nearby"), OFFICE_NEARBY("office_nearby"), OTHER("other");

    private final String db;

    LocationTag(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static LocationTag from(String v) { return DbEnum.of(LocationTag.class, v); }
}
