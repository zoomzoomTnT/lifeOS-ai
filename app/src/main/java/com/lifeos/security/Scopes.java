package com.lifeos.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The scope vocabulary. API keys carry these today; the same strings go into the JWT {@code scope}
 * claim later (Phase 3), so authorization rules never change when the credential type does.
 * Authorities are {@code SCOPE_<scope>}, exactly what Spring's JWT converter produces.
 */
public final class Scopes {
    private Scopes() {}

    public static final String PREFIX = "SCOPE_";

    public static final String FINANCE_READ = "finance:read", FINANCE_WRITE = "finance:write";
    public static final String FRIDGE_READ = "fridge:read", FRIDGE_WRITE = "fridge:write";
    public static final String MEMOS_READ = "memos:read", MEMOS_WRITE = "memos:write";
    public static final String STOCKS_READ = "stocks:read", STOCKS_WRITE = "stocks:write";
    public static final String OPS_READ = "ops:read", OPS_WRITE = "ops:write";
    /** Purge, actuator, key management, approvals. Only the admin client (kind admin) holds it. */
    public static final String OPS_ADMIN = "ops:admin";

    public static final List<String> ALL = List.of(FINANCE_READ, FINANCE_WRITE, FRIDGE_READ, FRIDGE_WRITE,
            MEMOS_READ, MEMOS_WRITE, STOCKS_READ, STOCKS_WRITE, OPS_READ, OPS_WRITE, OPS_ADMIN);

    /** Everything except ops:admin: the ceiling for any agent client. */
    public static final List<String> ALL_NON_ADMIN = ALL.stream().filter(s -> !s.equals(OPS_ADMIN)).toList();

    /** Grok Bot chat: domain read/write + ops usage logging. */
    public static final List<String> GROKBOT_CHAT = List.of(FINANCE_WRITE, FRIDGE_WRITE, MEMOS_WRITE,
            STOCKS_WRITE, OPS_READ, OPS_WRITE);
    /** The lifeOS Admin bot: ops:admin plus reads, no data writes. */
    public static final List<String> ADMIN_CLIENT = List.of(OPS_ADMIN, FINANCE_READ, FRIDGE_READ, MEMOS_READ,
            STOCKS_READ, OPS_READ);
    /** Data writes the admin client may never hold (it administers; it does not act as the assistant). */
    public static final List<String> DATA_WRITES = List.of(FINANCE_WRITE, FRIDGE_WRITE, MEMOS_WRITE, STOCKS_WRITE);
    /** Grok Bot routine (wake check): read-only. */
    public static final List<String> GROKBOT_ROUTINE = List.of(MEMOS_READ, OPS_READ);

    public static Set<String> parse(String spaceSeparated) {
        if (spaceSeparated == null || spaceSeparated.isBlank()) return new TreeSet<>();
        Set<String> out = new TreeSet<>();
        for (String s : spaceSeparated.trim().split("[\\s,]+")) {
            if (!ALL.contains(s)) throw new IllegalArgumentException("unknown scope: " + s + " (known: " + String.join(" ", ALL) + ")");
            out.add(s);
        }
        return out;
    }

    public static String format(Collection<String> scopes) {
        return new TreeSet<>(scopes).stream().collect(Collectors.joining(" "));
    }

    /** {@code x:write} implies {@code x:read}; {@code ops:admin} implies ops read/write. */
    public static Set<String> expand(Collection<String> scopes) {
        Set<String> out = new LinkedHashSet<>(scopes);
        for (String s : scopes) {
            if (s.endsWith(":write")) out.add(s.substring(0, s.length() - "write".length()) + "read");
            if (s.equals(OPS_ADMIN)) { out.add(OPS_WRITE); out.add(OPS_READ); }
        }
        return out;
    }

    /** True if every requested scope is within the ceiling (after expanding the ceiling). */
    public static boolean within(Collection<String> requested, Collection<String> ceiling) {
        return expand(ceiling).containsAll(requested);
    }

    public static String authority(String scope) {
        return PREFIX + scope;
    }

    public static String[] all() {
        return ALL.toArray(String[]::new);
    }

    static List<String> sorted(String... s) {
        return Arrays.stream(s).sorted().toList();
    }
}
