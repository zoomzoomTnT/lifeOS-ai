package com.lifeos.domain;

/** Stored as the CHECK string in SQLite (e.g. {@code eaten}, not {@code EATEN}). */
public interface DbEnum {
    String db();

    static <E extends Enum<E> & DbEnum> E of(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (E e : type.getEnumConstants()) {
            if (e.db().equalsIgnoreCase(raw) || e.name().equalsIgnoreCase(raw)) {
                return e;
            }
        }
        throw new IllegalArgumentException("unknown " + type.getSimpleName() + ": " + raw);
    }
}
