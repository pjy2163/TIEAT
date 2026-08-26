package com.tieat.store.domain;

import java.util.Optional;

public interface StoreRepository {

    Store save(Store store);

    Optional<Store> findById(StoreId id);

    Optional<Store> findByIdForUpdate(StoreId id);
}
