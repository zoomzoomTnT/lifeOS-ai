package com.lifeos.service;

import com.lifeos.domain.Memo;
import com.lifeos.web.dto.MemoCreateRequest;
import com.lifeos.web.dto.MemoCreateResponse;
import com.lifeos.web.dto.MemoFiredResponse;
import com.lifeos.web.dto.MemoPatchRequest;
import com.lifeos.web.dto.MemoPatchResponse;

import java.util.List;

public interface MemoService {
    List<Memo> due(int withinHours, String handle);

    MemoCreateResponse create(MemoCreateRequest request, String handle);

    MemoPatchResponse patch(long id, MemoPatchRequest request);

    MemoFiredResponse markFired(long id);
}
