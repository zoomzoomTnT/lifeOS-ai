package com.lifeos.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintsAndNamesTest {

    @Test
    void barcodeAloneIsTheKey() {
        String a = Fingerprints.receipt(" 0006e02b032623758296 ", "ignored-shop", "2026-08-25 16:11", 1609);
        String b = Fingerprints.receipt("0006E02B032623758296", "other-shop", "different-time", 1);
        assertEquals("0006e02b032623758296", a);
        assertEquals(a, b);
    }

    @Test
    void noBarcodeUsesMerchantTimeAndTotal() {
        String a = Fingerprints.receipt(null, "新鮮激安市場", "2026-08-25 16:11", 1609);
        String b = Fingerprints.receipt("", " 新鮮激安市場 ", "2026-08-25 16:11", 1609);
        String c = Fingerprints.receipt(null, "新鮮激安市場", "2026-08-25 16:11", 1610);
        assertEquals(32, a.length());
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertTrue(a.matches("[0-9a-f]{32}"));
    }

    @Test
    void nameNormKeepsChineseAndStripsSpaces() {
        assertEquals("生菜", Names.norm(" 生菜 "));
        assertEquals("hema", Names.norm("He Ma"));
        assertEquals("", Names.norm(null));
    }

    @Test
    void fridgeStatusRoundTrip() {
        assertEquals(FridgeStatus.EATEN, FridgeStatus.from("eaten"));
        assertEquals("discarded", FridgeStatus.DISCARDED.db());
    }
}
