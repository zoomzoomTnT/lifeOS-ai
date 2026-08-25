package com.lifeos.web;

import com.lifeos.service.FridgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/fridge")
public class FridgeController {

    private final FridgeService fridgeService;

    public FridgeController(FridgeService fridgeService) {
        this.fridgeService = fridgeService;
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody Map<String, Object> body,
                                 @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(fridgeService.add(body, handle));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) Integer expiringWithinHours,
                                  @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(fridgeService.list(status, expiringWithinHours, handle));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable long id,
                                     @RequestBody Map<String, Object> body,
                                     @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(fridgeService.resolve(id, body, handle));
    }
}
