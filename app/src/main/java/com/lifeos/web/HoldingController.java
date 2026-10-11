package com.lifeos.web;

import com.lifeos.service.HoldingService;
import com.lifeos.web.dto.HoldingResponse;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.HoldingWriteResponse;
import com.lifeos.web.dto.StockEventRequest;
import com.lifeos.web.dto.StockEventResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/holdings")
@RequiredArgsConstructor
public class HoldingController {

    private final HoldingService holdingService;

    @PreAuthorize("hasAuthority('SCOPE_stocks:read')")
    @GetMapping
    public ResponseEntity<List<HoldingResponse>> list(
            @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(holdingService.list(actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_stocks:read')")
    @GetMapping("/{id}")
    public ResponseEntity<HoldingResponse> get(@PathVariable long id,
                                               @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(holdingService.get(id, actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_stocks:write')")
    @PostMapping
    public ResponseEntity<HoldingWriteResponse> upsert(@Valid @RequestBody HoldingUpsertRequest body,
                                                       @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(holdingService.upsert(body, actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_stocks:write')")
    @PostMapping("/{id}/events")
    public ResponseEntity<StockEventResponse> addEvent(@PathVariable long id,
                                                       @Valid @RequestBody StockEventRequest body,
                                                       @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(holdingService.addEvent(id, body, actor.personId()));
    }
}
