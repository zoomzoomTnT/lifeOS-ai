package com.lifeos.repo;

import java.util.Optional;

public interface PersonRepository {
    Optional<Long> findIdByHandle(String handle);

    Optional<String> findOwnerHandle();

    Optional<Long> findOwnerId();
}
