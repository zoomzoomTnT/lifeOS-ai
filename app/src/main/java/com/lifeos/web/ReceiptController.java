package com.lifeos.web;

import com.lifeos.domain.ReceiptStatus;
import com.lifeos.service.ReceiptService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService receiptService;

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody Map<String, Object> body,
                                     @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(receiptService.preview(body, handle));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable long id,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(receiptService.confirm(id, body, handle));
    }

    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(receiptService.lookup(body));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) ReceiptStatus status,
                                  @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(receiptService.list(status, limit));
    }
}
