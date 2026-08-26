package com.tieat.store.adapter.out.persistence;

import com.tieat.store.domain.Store;
import com.tieat.store.domain.StoreCatalogEntry;
import com.tieat.store.domain.StoreCatalogRepository;
import com.tieat.store.domain.StoreId;
import com.tieat.store.domain.StoreRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class StorePersistenceAdapter implements StoreRepository, StoreCatalogRepository {

    private final StoreJpaRepository storeRepository;
    private final StoreCatalogJpaRepository catalogRepository;

    public StorePersistenceAdapter(StoreJpaRepository storeRepository, StoreCatalogJpaRepository catalogRepository) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.catalogRepository = Objects.requireNonNull(catalogRepository);
    }

    @Override
    public Store save(Store store) {
        Objects.requireNonNull(store, "Store must be supplied");
        return toStore(storeRepository.saveAndFlush(new StoreJpaEntity(
            store.id().value(),
            store.displayName(),
            store.catalogEntryId().orElse(null),
            store.onboardingStatus(),
            store.createdAt()
        )));
    }

    @Override
    public Optional<Store> findById(StoreId id) {
        Objects.requireNonNull(id, "Store id must be supplied");
        return storeRepository.findById(id.value()).map(this::toStore);
    }

    @Override
    public Optional<Store> findByIdForUpdate(StoreId id) {
        Objects.requireNonNull(id, "Store id must be supplied");
        return storeRepository.findByIdForUpdate(id.value()).map(this::toStore);
    }

    @Override
    public Optional<StoreCatalogEntry> findById(UUID id) {
        Objects.requireNonNull(id, "Catalog entry id must be supplied");
        return catalogRepository.findById(id).map(this::toCatalogEntry);
    }

    @Override
    public List<StoreCatalogEntry> search(String query, int limit) {
        Objects.requireNonNull(query, "Catalog query must be supplied");
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Catalog result limit must be between 1 and 50");
        }
        return catalogRepository.search(query, PageRequest.of(0, limit)).stream()
            .map(this::toCatalogEntry)
            .toList();
    }

    private Store toStore(StoreJpaEntity entity) {
        return new Store(
            new StoreId(entity.id()),
            entity.displayName(),
            entity.catalogEntryId(),
            entity.onboardingStatus(),
            entity.createdAt()
        );
    }

    private StoreCatalogEntry toCatalogEntry(StoreCatalogJpaEntity entity) {
        return new StoreCatalogEntry(
            entity.id(),
            entity.storeDisplayName(),
            entity.brandDisplayName(),
            safeLocalLogoPath(entity.logoPath())
        );
    }

    private String safeLocalLogoPath(String logoPath) {
        if (logoPath == null
            || !logoPath.startsWith("/")
            || logoPath.startsWith("//")
            || logoPath.contains("..")) {
            return null;
        }
        return logoPath;
    }
}
