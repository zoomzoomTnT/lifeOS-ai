package com.lifeos.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenClawClientTest {

    @Test
    void normalizePathKeepsOfficialWebhookPrefix() {
        assertEquals("/hooks", OpenClawClient.normalizePath(null));
        assertEquals("/hooks", OpenClawClient.normalizePath(""));
        assertEquals("/hooks", OpenClawClient.normalizePath("/"));
        assertEquals("/hooks", OpenClawClient.normalizePath("/hooks"));
        assertEquals("/hooks", OpenClawClient.normalizePath("/hooks/"));
        assertEquals("/hooks", OpenClawClient.normalizePath("hooks"));
    }

    @Test
    void normalizePathAllowsDedicatedOverride() {
        assertEquals("/ingress", OpenClawClient.normalizePath("/ingress"));
        assertEquals("/ingress", OpenClawClient.normalizePath("ingress/"));
    }
}
