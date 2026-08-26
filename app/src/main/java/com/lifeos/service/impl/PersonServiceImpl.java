package com.lifeos.service.impl;

import com.lifeos.repo.PersonRepository;
import com.lifeos.service.PersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PersonServiceImpl implements PersonService {

    private final PersonRepository people;

    @Override
    @Transactional
    public long resolveId(String handle) {
        if (handle == null || handle.isBlank() || "owner".equals(handle)) {
            return 1L;
        }
        return people.findIdByHandle(handle)
                .orElseGet(() -> people.insertMember(handle, handle));
    }
}
