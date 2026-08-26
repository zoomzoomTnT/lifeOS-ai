package com.lifeos.service.impl;

import com.lifeos.domain.Memo;
import com.lifeos.domain.MemoKind;
import com.lifeos.domain.MemoStatus;
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

    @Override
    public List<Memo> due(int withinHours, String handle) {
        return memos.due(people.resolveId(handle), withinHours);
    }

    @Override
    @Transactional
    public Map<String, Object> create(MemoCreateRequest request, String handle) {
        long ownerId = people.resolveId(handle);
        MemoKind kind = request.kind() == null ? MemoKind.REMINDER : request.kind();
        int priority = request.priority() == null ? 3 : request.priority();
        String tz = request.timezone() == null ? "Asia/Tokyo" : request.timezone();
        String payload = request.payloadJson() == null ? null : request.payloadJson().toString();
        Memo memo = new Memo(
                null, ownerId, request.title(), request.body(), kind, MemoStatus.OPEN, priority,
                request.dueAt(), tz, request.cronExpr(), request.cronTz(),
                request.sourceDomain(), request.sourceTable(), request.sourceId(), payload
        );
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
