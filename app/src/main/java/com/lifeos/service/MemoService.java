package com.lifeos.service;

import com.lifeos.web.dto.MemoCreateRequest;
import com.lifeos.web.dto.MemoPatchRequest;
import com.lifeos.web.dto.MemoResponse;
import com.lifeos.web.dto.MemoWriteResponse;

import java.util.List;

public interface MemoService {
    List<MemoResponse> due(int withinHours, String handle);

    MemoWriteResponse create(MemoCreateRequest request, String handle);

    MemoWriteResponse patch(long id, MemoPatchRequest request);

    MemoWriteResponse markFired(long id);
}
