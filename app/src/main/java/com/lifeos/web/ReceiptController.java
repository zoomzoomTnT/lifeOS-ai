package com.lifeos.web;

import com.lifeos.service.ReceiptService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody Map<String, Object> body,
                                     @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        return ResponseEntity.ok(receiptService.preview(body, handle));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable long id,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     @RequestHeader(value = "X-Life-Handle", required = false) String handle) {
        boolean alsoFridge = body != null && Boolean.TRUE.equals(body.get("also_fridge"));
        return ResponseEntity.ok(receiptService.confirm(id, alsoFridge, handle));
    }

    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(receiptService.lookup(body.get("barcode"), body.get("printed_at")));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(receiptService.list(status, limit));
    }
}
