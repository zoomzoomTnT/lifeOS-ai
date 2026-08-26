package com.lifeos.web;

import com.lifeos.ops.OpsService;
import com.lifeos.ops.ProactiveCronService;
import com.lifeos.ops.WakeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OpsService opsService;
    private final WakeService wakeService;
    private final ProactiveCronService proactiveCronService;

    public OpsController(OpsService opsService, WakeService wakeService,
                         ProactiveCronService proactiveCronService) {
        this.opsService = opsService;
        this.wakeService = wakeService;
        this.proactiveCronService = proactiveCronService;
    }

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
    public ResponseEntity<?> shouldWake(@RequestParam(defaultValue = "10") int leadMinutes,
                                        @RequestParam(required = false) Integer withinHours,
                                        @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        int lead = withinHours != null ? withinHours * 60 : leadMinutes;
        return ResponseEntity.ok(wakeService.shouldWake(handle, lead));
    }

    @PostMapping("/proactive/run")
    public ResponseEntity<?> proactiveRun(@RequestBody(required = false) Map<String, Object> body) {
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        return ResponseEntity.ok(proactiveCronService.run(force));
    }

    @PostMapping("/purge")
    public ResponseEntity<?> purge(@RequestBody Map<String, Object> body) {
        int days = 90;
        if (body.get("older_than_days") instanceof Number n) days = n.intValue();
        return ResponseEntity.ok(opsService.purge(days));
    }
}
