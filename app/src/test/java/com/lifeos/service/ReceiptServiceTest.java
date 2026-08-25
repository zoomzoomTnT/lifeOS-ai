package com.lifeos.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReceiptServiceTest {

    @Test
    void fingerprintIsStableForBarcodeAndPrintedAt() {
        String a = ReceiptService.fingerprint("262508241912", "2026-08-24 19:12:03");
        String b = ReceiptService.fingerprint("262508241912", "2026-08-24 19:12:03");
        assertEquals(32, a.length());
        assertEquals(a, b);
    }

    @Test
    void fingerprintTrimsWhitespace() {
        String raw = ReceiptService.fingerprint(" 262508241912 ", "2026-08-24 19:12:03");
        String trimmed = ReceiptService.fingerprint("262508241912", "2026-08-24 19:12:03");
        assertEquals(trimmed, raw);
    }

    @Test
    void fingerprintDiffersWhenBarcodeChanges() {
        String a = ReceiptService.fingerprint("111", "2026-08-24 19:12:03");
        String b = ReceiptService.fingerprint("222", "2026-08-24 19:12:03");
        assertNotEquals(a, b);
    }

    @Test
    void nameNormKeepsChineseAndStripsSpaces() {
        assertEquals("生菜", ReceiptService.nameNorm(" 生菜 "));
        assertEquals("hema", ReceiptService.nameNorm("He Ma"));
        assertEquals("", ReceiptService.nameNorm(null));
    }
}
