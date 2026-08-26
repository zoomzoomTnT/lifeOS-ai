package com.lifeos.repo;

import java.util.Optional;

public interface MerchantRepository {
    Optional<Long> findIdByNameNorm(String nameNorm);

    long insert(String name, String nameNorm);
}
