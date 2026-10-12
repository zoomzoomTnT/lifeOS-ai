package com.lifeos.security;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * {@code lok_<keyId>_<secret>}: keyId = 12 base32 chars (60 bits, public, used to find the row),
 * secret = 52 base32 chars (256 bits). Base32 so the key has no '_' or '-' inside its parts and is
 * safe in shells, headers and env files. The prefix makes leaked keys greppable by secret scanners.
 */
public final class ApiKeyFormat {
    private ApiKeyFormat() {}

    public static final String PREFIX = "lok_";
    static final int KEY_ID_LEN = 12;
    static final int SECRET_LEN = 52;
    private static final char[] B32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    public record Parsed(String keyId, String secret) {
        @Override
        public String toString() {
            return "Parsed[keyId=" + keyId + ", secret=***]";
        }
    }

    public record Generated(String keyId, String secret, String fullKey) {
        @Override
        public String toString() {
            return "Generated[keyId=" + keyId + ", secret=***]";
        }
    }

    public static Generated generate() {
        String keyId = random(KEY_ID_LEN);
        String secret = random(SECRET_LEN);
        return new Generated(keyId, secret, PREFIX + keyId + "_" + secret);
    }

    /** Random lowercase base32 string (5 bits per char). */
    public static String random(int chars) {
        char[] out = new char[chars];
        for (int i = 0; i < chars; i++) out[i] = B32[RNG.nextInt(32)];
        return new String(out);
    }

    /** A well-formed public key id (12 lowercase base32 chars). */
    public static boolean isKeyId(String s) {
        return s != null && s.length() == KEY_ID_LEN && isB32(s);
    }

    public static boolean looksLikeKey(String token) {
        return token != null && token.startsWith(PREFIX);
    }

    /** @return the parts, or null if the string is not a well-formed lifeOS key. */
    public static Parsed parse(String token) {
        if (token == null || !token.startsWith(PREFIX)) return null;
        String rest = token.substring(PREFIX.length());
        int us = rest.indexOf('_');
        if (us != KEY_ID_LEN || rest.length() != KEY_ID_LEN + 1 + SECRET_LEN) return null;
        String keyId = rest.substring(0, us), secret = rest.substring(us + 1);
        if (!isB32(keyId) || !isB32(secret)) return null;
        return new Parsed(keyId, secret);
    }

    private static boolean isB32(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '2' && c <= '7'))) return false;
        }
        return s.equals(s.toLowerCase(Locale.ROOT));
    }
}
