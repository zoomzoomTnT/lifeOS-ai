package com.lifeos.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * OpenClaw master switch. {@code OPENCLAW_ENABLED=false} turns off every OpenClaw integration:
 * the webhook client, the proactive cron wake, the webhook ping, and OpenClaw log ingest. The REST
 * API and database keep working (Grok Bot calls the API directly).
 */
public final class OpenClawSwitch {

    /** Spring property; bound to env {@code OPENCLAW_ENABLED} in application.yml. */
    public static final String PROPERTY = "lifeos.openclaw.enabled";
    public static final String VALUE = "${" + PROPERTY + ":true}";

    private OpenClawSwitch() {}

    /** Same boolean conversion as {@code @Value(VALUE) boolean} (true/false/on/off/yes/no/1/0). */
    static final class OnEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty(PROPERTY, Boolean.class, true);
        }
    }

    /** Response for OpenClaw-only endpoints when the switch is off. */
    public static ResponseEntity<Map<String, Object>> disabledResponse() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "openclaw_disabled",
                "message", "OpenClaw integration is turned off (OPENCLAW_ENABLED=false)"));
    }
}
