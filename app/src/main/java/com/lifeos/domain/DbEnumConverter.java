package com.lifeos.domain;

import jakarta.persistence.AttributeConverter;

public abstract class DbEnumConverter<E extends Enum<E> & DbEnum> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected DbEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : value.db();
    }

    @Override
    public E convertToEntityAttribute(String db) {
        return DbEnum.of(type, db);
    }
}
