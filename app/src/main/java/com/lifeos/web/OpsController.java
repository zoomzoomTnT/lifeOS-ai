package com.lifeos.web;

import com.lifeos.ops.OpsService;
import com.lifeos.ops.WakeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OpsService opsService;
    private final WakeService wakeService;

    public OpsController(OpsService opsService, WakeService wakeService) {
        this.opsService = opsService;
        this.wakeService = wakeService;
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
    public ResponseEntity<?> shouldWake(@RequestParam(defaultValue = "36") int withinHours,
                                        @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(wakeService.shouldWake(handle, withinHours));
    }

    @PostMapping("/purge")
    public ResponseEntity<?> purge(@RequestBody Map<String, Object> body) {
        int days = 90;
        if (body.get("older_than_days") instanceof Number n) days = n.intValue();
        return ResponseEntity.ok(opsService.purge(days));
    }
}
