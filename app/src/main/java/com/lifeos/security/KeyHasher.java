package com.lifeos.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256(pepper, secret). Secrets are 256-bit random, so a slow hash (bcrypt/argon2) adds
 * nothing but CPU on a 1 GB box; the pepper (LIFE_KEY_PEPPER, env only, never in the DB) means a
 * leaked database alone cannot be used to verify guesses. Comparison is constant-time.
 */
public final class KeyHasher {

    public static final int MIN_PEPPER_LENGTH = 32;

    private final SecretKeySpec key;

    public KeyHasher(String pepper) {
        if (pepper == null || pepper.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalArgumentException("LIFE_KEY_PEPPER must be at least " + MIN_PEPPER_LENGTH + " characters");
        }
        this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String hash(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Unpeppered SHA-256 hex of a secret: the admin key's stored hash (it arrives as a deploy secret). */
    public static String sha256Hex(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean equalHex(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
                b.getBytes(StandardCharsets.US_ASCII));
    }

    public boolean matches(String secret, String expectedHex) {
        if (secret == null || expectedHex == null) return false;
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII),
                expectedHex.getBytes(StandardCharsets.US_ASCII));
    }
}
