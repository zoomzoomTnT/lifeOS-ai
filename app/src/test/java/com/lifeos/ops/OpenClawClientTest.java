package com.lifeos.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenClawClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsMappedWebhookNotAgent() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hooks/life-os", ex -> {
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"ok\":true,\"runId\":\"t\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        OpenClawClient client = new OpenClawClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "baseUrl", "http://127.0.0.1:" + port);
        ReflectionTestUtils.setField(client, "hookToken", "secret");
        ReflectionTestUtils.setField(client, "hooksPath", "/hooks");
        ReflectionTestUtils.setField(client, "hookName", "life-os");
        ReflectionTestUtils.setField(client, "channel", "openclaw-weixin");
        ReflectionTestUtils.setField(client, "model", "");

        Map<String, Object> res = client.wakeProactive("hello", "wxid_test");
        assertEquals(Boolean.TRUE, res.get("ok"));
        assertEquals("/hooks/life-os", path.get());
        assertEquals("Bearer secret", auth.get());
        assertTrue(body.get().contains("\"message\":\"hello\""));
        assertTrue(body.get().contains("\"to\":\"wxid_test\""));
        assertTrue(String.valueOf(res.get("url")).endsWith("/hooks/life-os"));
    }

    @Test
    void hookTokenMatchesBearerAndOpenClawHeader() {
        OpenClawClient client = new OpenClawClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "hookToken", "hook-secret");
        assertTrue(client.hookTokenMatches("Bearer hook-secret", null));
        assertTrue(client.hookTokenMatches("bearer hook-secret", null));
        assertTrue(client.hookTokenMatches(null, "hook-secret"));
        assertFalse(client.hookTokenMatches("Bearer other", null));
        assertFalse(client.hookTokenMatches(null, null));
        assertFalse(client.hookTokenMatches("hook-secret", null));
    }

    @Test
    void hookTokenRejectsWhenUnset() {
        OpenClawClient client = new OpenClawClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "hookToken", "");
        assertFalse(client.hookTokenMatches("Bearer anything", null));
    }

    @Test
    void normalizePathRejectsRoot() {
        assertEquals("/hooks", OpenClawClient.normalizePath("/"));
        assertEquals("/hooks", OpenClawClient.normalizePath("hooks"));
        assertEquals("/webhooks", OpenClawClient.normalizePath("/webhooks/"));
    }
}
