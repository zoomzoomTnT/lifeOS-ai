package com.lifeos.service;

import com.lifeos.web.dto.MemoCreateRequest;
import com.lifeos.web.dto.MemoPatchRequest;
import com.lifeos.web.dto.MemoResponse;
import com.lifeos.web.dto.MemoWriteResponse;

import java.util.List;

public interface MemoService {
    List<MemoResponse> due(int withinHours, long personId);

    MemoWriteResponse create(MemoCreateRequest request, long personId);

    MemoWriteResponse patch(long id, MemoPatchRequest request, long personId);

    MemoWriteResponse markFired(long id, long personId);
}
