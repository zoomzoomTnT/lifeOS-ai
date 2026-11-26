package com.lifeos.repo;

import com.lifeos.domain.Memo;
import com.lifeos.domain.MemoStatus;

import java.util.List;

public interface MemoRepository {
    long insert(Memo memo);

    List<Memo> due(long ownerId, int withinHours);

    List<Memo> dueForWake(long ownerId, int leadMinutes, boolean nightPriorityOnly);

    /** owner_id of the memo, empty if it does not exist. */
    java.util.Optional<Long> ownerOf(long id);

    void updateStatus(long id, MemoStatus status);

    void updateDueAt(long id, String dueAt);

    void updateAutomationId(long id, String automationId);

    void markFired(long id);
}
