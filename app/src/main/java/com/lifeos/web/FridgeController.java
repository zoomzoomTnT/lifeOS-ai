package com.lifeos.web;

import com.lifeos.domain.FridgeStatus;
import com.lifeos.service.FridgeService;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeResolveRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fridge")
@RequiredArgsConstructor
public class FridgeController {

    private final FridgeService fridgeService;

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody FridgeAddRequest body,
                                 @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(fridgeService.add(body, handle));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) FridgeStatus status,
                                  @RequestParam(required = false) Integer expiringWithinHours,
                                  @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(fridgeService.list(status, expiringWithinHours, handle));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable long id,
                                     @Valid @RequestBody FridgeResolveRequest body,
                                     @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(fridgeService.resolve(id, body, handle));
    }
}
