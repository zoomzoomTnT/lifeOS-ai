package com.lifeos.service.impl;

import com.lifeos.domain.Memo;
import com.lifeos.domain.MemoStatus;
import com.lifeos.mapper.MemoMapper;
import com.lifeos.repo.MemoRepository;
import com.lifeos.service.MemoService;
import com.lifeos.service.PersonService;
import com.lifeos.web.dto.MemoCreateRequest;
import com.lifeos.web.dto.MemoPatchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MemoServiceImpl implements MemoService {

    private final MemoRepository memos;
    private final PersonService people;
    private final MemoMapper memoMapper;

    @Override
    public List<Memo> due(int withinHours, String handle) {
        return memos.due(people.resolveId(handle), withinHours);
    }

    @Override
    @Transactional
    public Map<String, Object> create(MemoCreateRequest request, String handle) {
        Memo memo = memoMapper.toNewMemo(request, people.resolveId(handle));
        long id = memos.insert(memo);
        return Map.of("id", id, "status", MemoStatus.OPEN.db());
    }

    @Override
    @Transactional
    public Map<String, Object> patch(long id, MemoPatchRequest request) {
        if (request.status() != null) memos.updateStatus(id, request.status());
        if (request.dueAt() != null) memos.updateDueAt(id, request.dueAt());
        if (request.automationId() != null) memos.updateAutomationId(id, request.automationId());
        if (request.lastFiredAt() != null) memos.markFired(id);
        return Map.of("id", id, "updated", true);
    }

    @Override
    public Map<String, Object> markFired(long id) {
        memos.markFired(id);
        return Map.of("id", id, "fired", true);
    }
}
