package com.lifeos.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Fingerprints {
    private Fingerprints() {}

    public static String receipt(String barcode, String printedAt) {
        String raw = (barcode == null ? "" : barcode.trim()) + "|" + (printedAt == null ? "" : printedAt.trim());
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", dig[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
