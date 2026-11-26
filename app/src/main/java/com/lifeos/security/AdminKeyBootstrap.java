package com.lifeos.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Installs the admin key from the deploy secrets LIFE_ADMIN_KEY_ID + LIFE_ADMIN_KEY_SHA256 (validated
 * by {@link AuthSettingsCheck}): client {@value #ADMIN_CLIENT_ID} (kind admin) gets exactly that key;
 * every other admin key is revoked ({@code via=deploy_secret} in security_events). Same values again =
 * no change. Runs after the schema is in place (all singletons initialised) and before the web
 * server accepts requests; any failure stops startup. The server only ever sees the hash.
 */
public class AdminKeyBootstrap implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyBootstrap.class);

    public static final String ADMIN_CLIENT_ID = "lifeos-admin";

    private final ApiKeyService keys;
    private final AuthProperties props;
    private volatile boolean running;

    public AdminKeyBootstrap(ApiKeyService keys, AuthProperties props) {
        this.keys = keys;
        this.props = props;
    }

    @Override
    public void start() {
        if (props.hasAdminKey()) {
            boolean changed = keys.installAdminKey(props.adminKeyId(), props.adminKeySha256());
            log.info("admin key {} for client {}: {}", props.adminKeyId(), ADMIN_CLIENT_ID,
                    changed ? "installed, other admin keys revoked" : "unchanged");
        } else {
            log.warn("no admin key configured (local profile): /api/admin/** and tier-3 actions are unavailable");
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Before the web server (and anything else) starts. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
