package com.lifeos.web;

import com.lifeos.config.OpenClawSwitch;
import com.lifeos.ops.LogIngestService;
import com.lifeos.ops.OpenClawClient;
import com.lifeos.ops.OpsService;
import com.lifeos.ops.ProactiveCronService;
import com.lifeos.ops.WakeService;
import com.lifeos.security.LifePrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
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
    private final LogIngestService logIngestService;
    /** Absent when OPENCLAW_ENABLED=false. */
    private final ObjectProvider<ProactiveCronService> proactiveCronService;
    /** Absent when OPENCLAW_ENABLED=false. */
    private final ObjectProvider<OpenClawClient> openClawClient;

    @PreAuthorize("hasAuthority('SCOPE_ops:write')")
    @PostMapping("/ai")
    public ResponseEntity<?> recordAi(@RequestBody Map<String, Object> body,
                                      @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(opsService.recordAi(body, actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:read')")
    @GetMapping("/ai")
    public ResponseEntity<?> listAi(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(opsService.listAi(limit));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:read')")
    @GetMapping("/http")
    public ResponseEntity<?> listHttp(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(opsService.listHttp(limit));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:read')")
    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(opsService.summary(hours));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:read')")
    @GetMapping("/prices")
    public ResponseEntity<?> prices() {
        return ResponseEntity.ok(opsService.prices());
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:write')")
    @PutMapping("/prices")
    public ResponseEntity<?> upsertPrice(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(opsService.upsertPrice(body));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:read')")
    @GetMapping("/should-wake")
    public ResponseEntity<?> shouldWake(
            @RequestParam(name = "lead_minutes", defaultValue = "10") int leadMinutes,
            @RequestParam(name = "within_hours", required = false) Integer withinHours,
            @AuthenticationPrincipal LifePrincipal actor) {
        int lead = withinHours != null ? withinHours * 60 : leadMinutes;
        return ResponseEntity.ok(wakeService.shouldWake(actor.personId(), lead));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:write')")
    @PostMapping("/proactive/run")
    public ResponseEntity<?> proactiveRun(@RequestBody(required = false) Map<String, Object> body) {
        ProactiveCronService cron = proactiveCronService.getIfAvailable();
        if (cron == null) return OpenClawSwitch.disabledResponse();
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        return ResponseEntity.ok(cron.run(force));
    }

    /** Always invoke the Gateway Life OS webhook and deliver to WeChat. */
    @PostMapping("/webhook/ping")
    public ResponseEntity<?> webhookPing(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-openclaw-token", required = false) String openclawToken) {
        OpenClawClient client = openClawClient.getIfAvailable();
        ProactiveCronService cron = proactiveCronService.getIfAvailable();
        if (client == null || cron == null) return OpenClawSwitch.disabledResponse();
        if (!client.hookTokenMatches(authorization, openclawToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization: Bearer <OPENCLAW_HOOK_TOKEN> required"));
        }
        return ResponseEntity.ok(cron.ping(body));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:write')")
    @PostMapping("/logs/ingest")
    public ResponseEntity<?> ingestLogs() {
        if (!logIngestService.enabled()) return OpenClawSwitch.disabledResponse();
        return ResponseEntity.ok(logIngestService.run());
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:read')")
    @GetMapping("/logs/app")
    public ResponseEntity<?> appLogs(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(logIngestService.listApp(limit));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:read')")
    @GetMapping("/logs/sessions")
    public ResponseEntity<?> sessionLogs(@RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(name = "include_content", defaultValue = "false") boolean includeContent) {
        return ResponseEntity.ok(logIngestService.listSessions(limit, includeContent));
    }

    @PreAuthorize("hasAuthority('SCOPE_ops:admin')")
    @PostMapping("/purge")
    public ResponseEntity<?> purge(@RequestBody Map<String, Object> body) {
        int days = 90;
        if (body.get("older_than_days") instanceof Number n) days = n.intValue();
        return ResponseEntity.ok(opsService.purge(days));
    }
}
