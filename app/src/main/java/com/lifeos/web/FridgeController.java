package com.lifeos.web;

import com.lifeos.domain.FridgeStatus;
import com.lifeos.service.FridgeService;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeItemResponse;
import com.lifeos.web.dto.FridgeResolveRequest;
import com.lifeos.web.dto.FridgeWriteResponse;
import jakarta.validation.Valid;
import com.lifeos.security.LifePrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/fridge")
@RequiredArgsConstructor
public class FridgeController {

    private final FridgeService fridgeService;

    @PreAuthorize("hasAuthority('SCOPE_fridge:write')")
    @PostMapping
    public ResponseEntity<FridgeWriteResponse> add(@Valid @RequestBody FridgeAddRequest body,
                                                   @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(fridgeService.add(body, actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_fridge:read')")
    @GetMapping
    public ResponseEntity<List<FridgeItemResponse>> list(@RequestParam(required = false) FridgeStatus status,
                                                         @RequestParam(required = false) Integer expiringWithinHours,
                                                         @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(fridgeService.list(status, expiringWithinHours, actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_fridge:write')")
    @PostMapping("/{id}/resolve")
    public ResponseEntity<FridgeWriteResponse> resolve(@PathVariable long id,
                                                       @Valid @RequestBody FridgeResolveRequest body,
                                                       @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(fridgeService.resolve(id, body, actor.personId()));
    }
}
