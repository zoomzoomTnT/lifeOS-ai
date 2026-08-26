package com.lifeos.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Receipt dedupe key stored in {@code receipts.fingerprint} (UNIQUE).
 * <ul>
 *   <li>Barcode present → normalized barcode payload (digits / hex from the ticket).</li>
 *   <li>No barcode → sha256(merchant_norm|printed_at|total_cents)[:32].</li>
 * </ul>
 */
public final class Fingerprints {
    private Fingerprints() {}

    public static String receipt(String barcode, String merchantName, String printedAt, Integer totalCents) {
        String code = normalizeBarcode(barcode);
        if (!code.isEmpty()) {
            return code;
        }
        String raw = Names.norm(merchantName) + "|"
                + trim(printedAt) + "|"
                + (totalCents == null ? 0 : totalCents);
        return sha256_16(raw);
    }

    /** Old key sha256(barcode|printed_at)[:32], only to match rows written before this change. */
    public static String legacy(String barcode, String printedAt) {
        return sha256_16(trim(barcode) + "|" + trim(printedAt));
    }

    public static String normalizeBarcode(String barcode) {
        if (barcode == null) {
            return "";
        }
        return barcode.trim().toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z]", "");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String sha256_16(String raw) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
