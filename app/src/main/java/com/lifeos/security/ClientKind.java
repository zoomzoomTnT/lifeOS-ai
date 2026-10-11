package com.lifeos.security;

/** What kind of caller a credential belongs to. Matches api_clients.kind, plus session kinds. */
public enum ClientKind {
    /** An AI agent (Grok Bot). Can be prompt-injected: never ops:admin, risky calls need approval. */
    AGENT,
    /** A human-operated app or script holding its own key. */
    HUMAN_APP,
    /** A non-AI automation (deploy check, backup job). */
    SERVICE,
    /** zoomzoom in a browser, signed in with a passkey (or a one-time token, bootstrap only). */
    OWNER_SESSION,
    /** `local` profile with life.auth.local-open=true: unauthenticated local development. */
    LOCAL_DEV;

    public static ClientKind fromDb(String kind) {
        return switch (kind) {
            case "agent" -> AGENT;
            case "human_app" -> HUMAN_APP;
            case "service" -> SERVICE;
            default -> throw new IllegalArgumentException("unknown client kind: " + kind);
        };
    }

    public String db() {
        return switch (this) {
            case AGENT -> "agent";
            case HUMAN_APP -> "human_app";
            case SERVICE -> "service";
            default -> throw new IllegalStateException(this + " is not stored in api_clients");
        };
    }

    /** Agents may only request risky actions; a human must approve them. */
    public boolean needsApproval() {
        return this == AGENT || this == HUMAN_APP || this == SERVICE;
    }
}
