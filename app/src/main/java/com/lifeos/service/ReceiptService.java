package com.lifeos.service;

import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.web.dto.ReceiptConfirmRequest;
import com.lifeos.web.dto.ReceiptLookupRequest;
import com.lifeos.web.dto.ReceiptPreviewRequest;

import java.util.List;
import java.util.Map;

public interface ReceiptService {
    Map<String, Object> lookup(ReceiptLookupRequest request);

    Map<String, Object> preview(ReceiptPreviewRequest request, String handle);

    Map<String, Object> confirm(long id, ReceiptConfirmRequest request, String handle);

    List<Receipt> list(ReceiptStatus status, int limit);
}
