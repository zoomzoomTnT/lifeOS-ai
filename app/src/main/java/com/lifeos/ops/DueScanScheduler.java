package com.lifeos.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Free due-scan inside the app. Does not call any LLM.
 * Optional webhook if you later wire a non-model OpenClaw hook.
 */
@Component
public class DueScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(DueScanScheduler.class);

    private final WakeService wakeService;

    @Value("${life.wake-webhook:}")
    private String wakeWebhook;

    public DueScanScheduler(WakeService wakeService) {
        this.wakeService = wakeService;
    }

    @Scheduled(fixedDelayString = "${life.due-scan-ms:900000}")
    public void scan() {
        Map<String, Object> gate = wakeService.shouldWake("owner", 36);
        boolean wake = Boolean.TRUE.equals(gate.get("wake"));
        log.info("due-scan wake={} reasons={}", wake, gate.get("reasons"));
        if (wake && wakeWebhook != null && !wakeWebhook.isBlank()) {
            ping(gate);
        }
    }

    private void ping(Map<String, Object> gate) {
        try {
            String body = "{\"wake\":true,\"reasons\":\"" + gate.get("reasons") + "\"}";
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(wakeWebhook))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("wake webhook failed: {}", e.getMessage());
        }
    }
}
