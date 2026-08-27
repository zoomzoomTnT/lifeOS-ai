package com.lifeos.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Second-precision UTC ISO-8601 with Z, same shape as SQLite strftime. */
public final class Utc {
    private Utc() {}

    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public static String plusHours(int hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public static String plusMinutes(int minutes) {
        return Instant.now().plus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public static String plusDays(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public static String minusHours(int hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
