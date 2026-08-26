package com.lifeos.domain;

public final class Names {
    private Names() {}

    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[\\s\\p{Punct}]+", "");
    }
}
