package com.lifeos.testsupport;

/**
 * The admin key that src/test/resources/config/application.properties configures (key id + SHA-256 of
 * this secret) for every test context. Test-only, not a secret.
 */
public final class TestAdminKey {
    private TestAdminKey() {}

    public static final String KEY_ID = "testadminkey";
    public static final String SECRET = "ewb7at6s47f7j4ddx3i67pgt7ery5dqwadvla6a35y2rmtd5xwqp";
    public static final String FULL = "lok_" + KEY_ID + "_" + SECRET;
}
