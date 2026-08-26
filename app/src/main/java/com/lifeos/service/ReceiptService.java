package com.lifeos.service;

import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptStatus;

import java.util.List;
import java.util.Map;

public interface ReceiptService {
    Map<String, Object> lookup(Map<String, Object> body);

    Map<String, Object> preview(Map<String, Object> body, String handle);

    Map<String, Object> confirm(long id, Map<String, Object> body, String handle);

    List<Receipt> list(ReceiptStatus status, int limit);
}
