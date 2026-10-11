package com.lifeos.service.impl;

import com.lifeos.repo.PersonRepository;
import com.lifeos.service.PersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PersonServiceImpl implements PersonService {

    private final PersonRepository people;

    @Override
    public long ownerId() {
        return people.findOwnerId().orElseThrow(() -> new IllegalStateException("no owner row in people"));
    }
}
