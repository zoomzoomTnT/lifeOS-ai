package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PersonRole implements DbEnum {
    OWNER("owner"), MEMBER("member"), GUEST("guest");

    private final String db;

    PersonRole(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static PersonRole from(String v) { return DbEnum.of(PersonRole.class, v); }
}
