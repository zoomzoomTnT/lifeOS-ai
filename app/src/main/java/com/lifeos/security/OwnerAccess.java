package com.lifeos.security;

/** Authority names for the human owner. */
public final class OwnerAccess {
    private OwnerAccess() {}

    public static final String ROLE_OWNER = "ROLE_OWNER";
    /** The login name of the single owner account (passkeys + one-time tokens). */
    public static final String OWNER_USERNAME = "owner";
}
