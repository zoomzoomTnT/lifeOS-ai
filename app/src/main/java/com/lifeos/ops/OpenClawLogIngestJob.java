package com.lifeos.ops;

import com.lifeos.config.ConditionalOnOpenClaw;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic OpenClaw session/gateway log ingest. Only scheduled when OpenClaw is on. */
@Component
@ConditionalOnOpenClaw
@RequiredArgsConstructor
public class OpenClawLogIngestJob {

    private final LogIngestService logIngestService;

    @Scheduled(fixedDelayString = "${life.logs.ingest-ms:120000}")
    public void tick() {
        logIngestService.run();
    }
}
