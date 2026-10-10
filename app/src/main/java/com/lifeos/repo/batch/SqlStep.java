package com.lifeos.repo.batch;

import java.util.Arrays;
import java.util.List;

/** One SQL statement with positional ({@code ?}) arguments. */
public record SqlStep(String sql, List<Object> args) {

    public static SqlStep of(String sql, Object... args) {
        return new SqlStep(sql, Arrays.asList(args));
    }
}
