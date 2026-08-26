package com.lifeos.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FingerprintsAndNamesTest {

    @Test
    void fingerprintIsStableForBarcodeAndPrintedAt() {
        String a = Fingerprints.receipt("262508241912", "2026-08-24 19:12:03");
        String b = Fingerprints.receipt("262508241912", "2026-08-24 19:12:03");
        assertEquals(32, a.length());
        assertEquals(a, b);
    }

    @Test
    void fingerprintTrimsWhitespace() {
        String raw = Fingerprints.receipt(" 262508241912 ", "2026-08-24 19:12:03");
        String trimmed = Fingerprints.receipt("262508241912", "2026-08-24 19:12:03");
        assertEquals(trimmed, raw);
    }

    @Test
    void fingerprintDiffersWhenBarcodeChanges() {
        String a = Fingerprints.receipt("111", "2026-08-24 19:12:03");
        String b = Fingerprints.receipt("222", "2026-08-24 19:12:03");
        assertNotEquals(a, b);
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
