package com.lifeos.config;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean exists only when the OpenClaw master switch is on
 * ({@code lifeos.openclaw.enabled}, env {@code OPENCLAW_ENABLED}; default true).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OpenClawSwitch.OnEnabled.class)
public @interface ConditionalOnOpenClaw {
}
