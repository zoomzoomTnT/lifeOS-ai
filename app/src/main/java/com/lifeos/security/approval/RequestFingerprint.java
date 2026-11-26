package com.lifeos.security.approval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * SHA-256 over method, path, sorted query, client and the canonical body (JSON re-serialized with
 * sorted keys, so whitespace and key order don't matter; anything else hashed as raw bytes). An
 * approval is valid only for the request with this exact fingerprint.
 */
public final class RequestFingerprint {

    private static final JsonMapper CANON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private RequestFingerprint() {
    }

    public static String canonicalBody(byte[] body) {
        if (body == null || body.length == 0) return "";
        try {
            return "json:" + CANON.writeValueAsString(CANON.readValue(body, Object.class));
        } catch (JacksonException e) {
            return "raw:" + Base64.getEncoder().encodeToString(body);
        }
    }

    static String sortedQuery(String query) {
        if (query == null || query.isEmpty()) return "";
        String[] parts = query.split("&");
        Arrays.sort(parts);
        return String.join("&", parts);
    }

    public static String of(String method, String path, String query, String clientId, byte[] body) {
        String s = method + "\n" + path + "\n" + sortedQuery(query) + "\n" + clientId + "\n" + canonicalBody(body);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
