package com.lifeos.web;

import com.lifeos.service.HoldingService;
import com.lifeos.web.dto.HoldingResponse;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.HoldingWriteResponse;
import com.lifeos.web.dto.StockEventRequest;
import com.lifeos.web.dto.StockEventResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/holdings")
@RequiredArgsConstructor
public class HoldingController {

    private final HoldingService holdingService;

    @GetMapping
    public ResponseEntity<List<HoldingResponse>> list(
            @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(holdingService.list(handle));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HoldingResponse> get(@PathVariable long id,
                                               @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(holdingService.get(id, handle));
    }

    @PostMapping
    public ResponseEntity<HoldingWriteResponse> upsert(@Valid @RequestBody HoldingUpsertRequest body,
                                                       @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(holdingService.upsert(body, handle));
    }

    @PostMapping("/{id}/events")
    public ResponseEntity<StockEventResponse> addEvent(@PathVariable long id,
                                                       @Valid @RequestBody StockEventRequest body,
                                                       @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(holdingService.addEvent(id, body, handle));
    }
}
