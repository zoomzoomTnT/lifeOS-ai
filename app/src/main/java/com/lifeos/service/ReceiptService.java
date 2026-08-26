package com.lifeos.service;

import com.lifeos.domain.ReceiptStatus;
import com.lifeos.web.dto.ReceiptConfirmRequest;
import com.lifeos.web.dto.ReceiptConfirmResponse;
import com.lifeos.web.dto.ReceiptLookupRequest;
import com.lifeos.web.dto.ReceiptLookupResponse;
import com.lifeos.web.dto.ReceiptPreviewRequest;
import com.lifeos.web.dto.ReceiptPreviewResponse;
import com.lifeos.web.dto.ReceiptResponse;

import java.util.List;

public interface ReceiptService {
    ReceiptLookupResponse lookup(ReceiptLookupRequest request);

    ReceiptPreviewResponse preview(ReceiptPreviewRequest request, String handle);

    ReceiptConfirmResponse confirm(long id, ReceiptConfirmRequest request, String handle);

    List<ReceiptResponse> list(ReceiptStatus status, int limit);
}
