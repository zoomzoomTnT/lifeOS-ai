package com.lifeos.web;

import com.lifeos.service.MemoService;
import com.lifeos.web.dto.MemoCreateRequest;
import com.lifeos.web.dto.MemoPatchRequest;
import com.lifeos.web.dto.MemoResponse;
import com.lifeos.web.dto.MemoWriteResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memos")
@RequiredArgsConstructor
public class MemoController {

    private final MemoService memoService;

    @GetMapping("/due")
    public ResponseEntity<List<MemoResponse>> due(@RequestParam(defaultValue = "36") int withinHours,
                                                  @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(memoService.due(withinHours, handle));
    }

    @PostMapping
    public ResponseEntity<MemoWriteResponse> create(@Valid @RequestBody MemoCreateRequest body,
                                                    @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(memoService.create(body, handle));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<MemoWriteResponse> patch(@PathVariable long id, @RequestBody MemoPatchRequest body) {
        return ResponseEntity.ok(memoService.patch(id, body));
    }

    @PostMapping("/{id}/fired")
    public ResponseEntity<MemoWriteResponse> fired(@PathVariable long id) {
        return ResponseEntity.ok(memoService.markFired(id));
    }
}
