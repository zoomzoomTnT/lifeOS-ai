package com.lifeos.service;

import com.lifeos.domain.Memo;

import java.util.List;
import java.util.Map;

public interface MemoService {
    List<Memo> due(int withinHours, String handle);

    Map<String, Object> create(Map<String, Object> body, String handle);

    Map<String, Object> patch(long id, Map<String, Object> body);

    Map<String, Object> markFired(long id);
}
