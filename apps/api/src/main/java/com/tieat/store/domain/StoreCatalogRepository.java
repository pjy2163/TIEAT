package com.tieat.store.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreCatalogRepository {

    Optional<StoreCatalogEntry> findById(UUID id);

    List<StoreCatalogEntry> search(String query, int limit);
}
