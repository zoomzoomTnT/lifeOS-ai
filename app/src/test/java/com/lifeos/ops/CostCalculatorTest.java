package com.lifeos.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostCalculatorTest {

    @Test
    void grok4ThousandInputTokensIsThreeTenthsOfACent() {
        // $3 / 1M input → 1000 tokens = $0.003000 = 3000 micros
        long micros = CostCalculator.costMicros(1000, 0, 3_000_000, 15_000_000);
        assertEquals(3000, micros);
        assertEquals("$0.003000", CostCalculator.usdDisplay(micros));
    }

    @Test
    void outputTokensPricedSeparately() {
        long micros = CostCalculator.costMicros(0, 1_000_000, 3_000_000, 15_000_000);
        assertEquals(15_000_000, micros);
        assertEquals("$15.000000", CostCalculator.usdDisplay(micros));
    }

    @Test
    void displayPadsSixDecimalPlaces() {
        assertEquals("$1.000000", CostCalculator.usdDisplay(1_000_000));
        assertEquals("$0.000001", CostCalculator.usdDisplay(1));
    }
}
