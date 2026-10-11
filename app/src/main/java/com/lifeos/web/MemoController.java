package com.lifeos.web;

import com.lifeos.service.MemoService;
import com.lifeos.web.dto.MemoCreateRequest;
import com.lifeos.web.dto.MemoPatchRequest;
import com.lifeos.web.dto.MemoResponse;
import com.lifeos.web.dto.MemoWriteResponse;
import jakarta.validation.Valid;
import com.lifeos.security.LifePrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memos")
@RequiredArgsConstructor
public class MemoController {

    private final MemoService memoService;

    @PreAuthorize("hasAuthority('SCOPE_memos:read')")
    @GetMapping("/due")
    public ResponseEntity<List<MemoResponse>> due(@RequestParam(defaultValue = "36") int withinHours,
                                                  @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(memoService.due(withinHours, actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_memos:write')")
    @PostMapping
    public ResponseEntity<MemoWriteResponse> create(@Valid @RequestBody MemoCreateRequest body,
                                                    @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(memoService.create(body, actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_memos:write')")
    @PatchMapping("/{id}")
    public ResponseEntity<MemoWriteResponse> patch(@PathVariable long id, @RequestBody MemoPatchRequest body,
                                                   @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(memoService.patch(id, body, actor.personId()));
    }

    @PreAuthorize("hasAuthority('SCOPE_memos:write')")
    @PostMapping("/{id}/fired")
    public ResponseEntity<MemoWriteResponse> fired(@PathVariable long id,
                                                   @AuthenticationPrincipal LifePrincipal actor) {
        return ResponseEntity.ok(memoService.markFired(id, actor.personId()));
    }
}
