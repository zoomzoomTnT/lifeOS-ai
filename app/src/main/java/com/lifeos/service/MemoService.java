package com.lifeos.service;

import com.lifeos.domain.Memo;
import com.lifeos.web.dto.MemoCreateRequest;
import com.lifeos.web.dto.MemoPatchRequest;

import java.util.List;
import java.util.Map;

public interface MemoService {
    List<Memo> due(int withinHours, String handle);

    Map<String, Object> create(MemoCreateRequest request, String handle);

    Map<String, Object> patch(long id, MemoPatchRequest request);

    Map<String, Object> markFired(long id);
}
