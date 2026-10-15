package com.lifeos.service.impl;

import com.lifeos.domain.Memo;
import com.lifeos.mapper.MemoMapper;
import com.lifeos.repo.MemoRepository;
import com.lifeos.service.MemoService;
import com.lifeos.web.dto.MemoCreateRequest;
import com.lifeos.web.dto.MemoPatchRequest;
import com.lifeos.web.dto.MemoResponse;
import com.lifeos.web.dto.MemoWriteResponse;
import com.lifeos.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MemoServiceImpl implements MemoService {

    private final MemoRepository memos;
    private final MemoMapper memoMapper;

    @Override
    public List<MemoResponse> due(int withinHours, long personId) {
        return memoMapper.toResponseList(memos.due(personId, withinHours));
    }

    @Override
    @Transactional
    public MemoWriteResponse create(MemoCreateRequest request, long personId) {
        Memo memo = memoMapper.toNewMemo(request, personId);
        long id = memos.insert(memo);
        return memoMapper.toCreated(id);
    }

    @Override
    @Transactional
    public MemoWriteResponse patch(long id, MemoPatchRequest request, long personId) {
        requireOwned(id, personId);
        if (request.status() != null) memos.updateStatus(id, request.status());
        if (request.dueAt() != null) memos.updateDueAt(id, request.dueAt());
        if (request.automationId() != null) memos.updateAutomationId(id, request.automationId());
        if (request.lastFiredAt() != null) memos.markFired(id);
        return memoMapper.toPatched(id);
    }

    @Override
    public MemoWriteResponse markFired(long id, long personId) {
        requireOwned(id, personId);
        memos.markFired(id);
        return memoMapper.toFired(id);
    }

    private void requireOwned(long id, long personId) {
        if (memos.ownerOf(id).filter(o -> o == personId).isEmpty()) {
            throw new NotFoundException("memo not found: " + id); // also when it is someone else's
        }
    }
}
