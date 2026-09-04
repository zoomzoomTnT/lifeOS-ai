package com.lifeos.web;

import com.lifeos.ops.LogIngestService;
import com.lifeos.ops.OpsService;
import com.lifeos.ops.ProactiveCronService;
import com.lifeos.ops.WakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsService opsService;
    private final WakeService wakeService;
    private final ProactiveCronService proactiveCronService;
    private final LogIngestService logIngestService;

    @PostMapping("/ai")
    public ResponseEntity<?> recordAi(@RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(opsService.recordAi(body, handle));
    }

    @GetMapping("/ai")
    public ResponseEntity<?> listAi(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(opsService.listAi(limit));
    }

    @GetMapping("/http")
    public ResponseEntity<?> listHttp(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(opsService.listHttp(limit));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(opsService.summary(hours));
    }

    @GetMapping("/prices")
    public ResponseEntity<?> prices() {
        return ResponseEntity.ok(opsService.prices());
    }

    @PutMapping("/prices")
    public ResponseEntity<?> upsertPrice(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(opsService.upsertPrice(body));
    }

    @GetMapping("/should-wake")
    public ResponseEntity<?> shouldWake(
            @RequestParam(name = "lead_minutes", defaultValue = "10") int leadMinutes,
            @RequestParam(name = "within_hours", required = false) Integer withinHours,
            @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        int lead = withinHours != null ? withinHours * 60 : leadMinutes;
        return ResponseEntity.ok(wakeService.shouldWake(handle, lead));
    }

    @PostMapping("/proactive/run")
    public ResponseEntity<?> proactiveRun(@RequestBody(required = false) Map<String, Object> body) {
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        return ResponseEntity.ok(proactiveCronService.run(force));
    }

    /** Always invoke the Gateway Life OS webhook and deliver to WeChat. */
    @PostMapping("/webhook/ping")
    public ResponseEntity<?> webhookPing(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(proactiveCronService.ping(body));
    }

    @PostMapping("/logs/ingest")
    public ResponseEntity<?> ingestLogs() {
        return ResponseEntity.ok(logIngestService.run());
    }

    @GetMapping("/logs/app")
    public ResponseEntity<?> appLogs(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(logIngestService.listApp(limit));
    }

    @GetMapping("/logs/sessions")
    public ResponseEntity<?> sessionLogs(@RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(name = "include_content", defaultValue = "false") boolean includeContent) {
        return ResponseEntity.ok(logIngestService.listSessions(limit, includeContent));
    }

    @PostMapping("/purge")
    public ResponseEntity<?> purge(@RequestBody Map<String, Object> body) {
        int days = 90;
        if (body.get("older_than_days") instanceof Number n) days = n.intValue();
        return ResponseEntity.ok(opsService.purge(days));
    }
}
