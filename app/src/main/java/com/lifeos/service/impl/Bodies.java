package com.lifeos.service.impl;

import java.util.Map;

final class Bodies {
    private Bodies() {}

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static String str(Map<String, Object> body, String key) {
        return body == null ? null : str(body.get(key));
    }

    static int intVal(Object o, int fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    static Integer intOrNull(Object o) {
        if (o == null) return null;
        return intVal(o, 0);
    }

    static double doubleVal(Object o, double fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }

    static long longVal(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "1".equals(String.valueOf(o)) || "true".equalsIgnoreCase(String.valueOf(o));
    }
}
