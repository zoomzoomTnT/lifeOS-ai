package com.lifeos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FoodCategory implements DbEnum {
    VEG("veg"), FRUIT("fruit"), MEAT("meat"), SEAFOOD("seafood"),
    DAIRY("dairy"), DRINK("drink"), LEFTOVER("leftover"), STAPLE("staple"), OTHER("other");

    private final String db;

    FoodCategory(String db) { this.db = db; }

    @Override @JsonValue public String db() { return db; }

    @JsonCreator public static FoodCategory from(String v) { return DbEnum.of(FoodCategory.class, v); }
}
