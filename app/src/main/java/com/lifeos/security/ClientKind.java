package com.lifeos.security;

/** What kind of caller a credential belongs to. Matches api_clients.kind, plus local development. */
public enum ClientKind {
    /** An AI agent (Grok Bot). Can be prompt-injected: never ops:admin, risky calls need approval. */
    AGENT,
    /** A human-operated app or script holding its own key. */
    HUMAN_APP,
    /** A non-AI automation (deploy check, backup job). */
    SERVICE,
    /**
     * The lifeOS Admin bot (exactly one client, {@code lifeos-admin}): the only holder of ops:admin.
     * Acts only after zoomzoom says yes in chat; approves agents' risky requests; no data writes.
     */
    ADMIN,
    /** `local` profile with life.auth.local-open=true: unauthenticated local development. */
    LOCAL_DEV;

    public static ClientKind fromDb(String kind) {
        return switch (kind) {
            case "agent" -> AGENT;
            case "human_app" -> HUMAN_APP;
            case "service" -> SERVICE;
            case "admin" -> ADMIN;
            default -> throw new IllegalArgumentException("unknown client kind: " + kind);
        };
    }

    public String db() {
        return switch (this) {
            case AGENT -> "agent";
            case HUMAN_APP -> "human_app";
            case SERVICE -> "service";
            case ADMIN -> "admin";
            default -> throw new IllegalStateException(this + " is not stored in api_clients");
        };
    }

    /** These may only request risky (tier 2) actions; the admin client approves them. */
    public boolean needsApproval() {
        return this == AGENT || this == HUMAN_APP || this == SERVICE;
    }
}
