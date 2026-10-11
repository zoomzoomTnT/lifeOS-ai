package com.lifeos.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every /api handler is covered twice and both agree: a path rule in {@link ScopeRules} and an
 * {@code @PreAuthorize} with the same scope. A new endpoint without both fails here.
 */
class ScopeAnnotationConsistencyTest extends AuthHttpTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void pathRulesAndPreAuthorizeAgreeForEveryApiEndpoint() {
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            for (String pattern : e.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) continue;
                for (var method : e.getKey().getMethodsCondition().getMethods()) {
                    String path = pattern.replaceAll("\\{[^}]+}", "1");
                    MockHttpServletRequest req = new MockHttpServletRequest(method.name(), path);
                    req.setServletPath(path);
                    var rule = ScopeRules.match(req);
                    assertThat(rule).as(method + " " + pattern + " has a ScopeRules entry").isPresent();
                    PreAuthorize pre = e.getValue().getMethodAnnotation(PreAuthorize.class);
                    if (rule.get().isPublic()) {
                        assertThat(pre).as(method + " " + pattern + " is public").isNull();
                    } else {
                        assertThat(pre).as(method + " " + pattern + " needs @PreAuthorize").isNotNull();
                        assertThat(pre.value()).as(method + " " + pattern)
                                .isEqualTo("hasAuthority('SCOPE_" + rule.get().scope() + "')");
                    }
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(20);
    }
}
