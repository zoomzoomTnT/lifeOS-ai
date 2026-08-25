package com.lifeos.web;

import com.lifeos.service.MemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/memos")
public class MemoController {

    private final MemoService memoService;

    public MemoController(MemoService memoService) {
        this.memoService = memoService;
    }

    @GetMapping("/due")
    public ResponseEntity<?> due(@RequestParam(defaultValue = "36") int withinHours,
                                 @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(memoService.due(withinHours, handle));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body,
                                    @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(memoService.create(body, handle));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(memoService.patch(id, body));
    }

    @PostMapping("/{id}/fired")
    public ResponseEntity<?> fired(@PathVariable long id) {
        return ResponseEntity.ok(memoService.markFired(id));
    }
}
