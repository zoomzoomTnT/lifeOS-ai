package com.lifeos.service.impl;

import com.lifeos.domain.Memo;
import com.lifeos.domain.MemoKind;
import com.lifeos.domain.MemoStatus;
import com.lifeos.repo.MemoRepository;
import com.lifeos.service.MemoService;
import com.lifeos.service.PersonService;
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
    public Map<String, Object> create(Map<String, Object> body, String handle) {
        long ownerId = people.resolveId(handle);
        MemoKind kind = body.get("kind") == null ? MemoKind.REMINDER : MemoKind.from(Bodies.str(body, "kind"));
        int priority = Bodies.intVal(body.get("priority"), 3);
        String tz = body.get("timezone") == null ? "Asia/Tokyo" : Bodies.str(body, "timezone");
        Object payload = body.get("payload_json");
        Long sourceId = body.get("source_id") == null ? null : Bodies.longVal(body.get("source_id"));
        Memo memo = new Memo(
                null, ownerId, Bodies.str(body, "title"), Bodies.str(body, "body"),
                kind, MemoStatus.OPEN, priority,
                Bodies.str(body, "due_at"), tz,
                Bodies.str(body, "cron_expr"), Bodies.str(body, "cron_tz"),
                Bodies.str(body, "source_domain"), Bodies.str(body, "source_table"),
                sourceId, payload == null ? null : payload.toString()
        );
        long id = memos.insert(memo);
        return Map.of("id", id, "status", MemoStatus.OPEN.db());
    }

    @Override
    @Transactional
    public Map<String, Object> patch(long id, Map<String, Object> body) {
        if (body.containsKey("status")) memos.updateStatus(id, MemoStatus.from(Bodies.str(body, "status")));
        if (body.containsKey("due_at")) memos.updateDueAt(id, Bodies.str(body, "due_at"));
        if (body.containsKey("automation_id")) memos.updateAutomationId(id, Bodies.str(body, "automation_id"));
        if (body.containsKey("last_fired_at")) memos.markFired(id);
        return Map.of("id", id, "updated", true);
    }

    @Override
    public Map<String, Object> markFired(long id) {
        memos.markFired(id);
        return Map.of("id", id, "fired", true);
    }
}
