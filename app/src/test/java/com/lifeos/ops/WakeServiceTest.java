package com.lifeos.ops;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WakeServiceTest {

    @Test
    void tokyoNightMatchesWallClock() {
        int hour = ZonedDateTime.now(ZoneId.of("Asia/Tokyo")).getHour();
        boolean expectNight = hour >= 22 || hour < 8;
        assertEquals(expectNight, WakeService.isTokyoNight());
    }
}
