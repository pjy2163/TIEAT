package com.tieat.store.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StoreCatalogJpaRepository extends JpaRepository<StoreCatalogJpaEntity, UUID> {

    @Query("""
        select catalogEntry
        from StoreCatalogJpaEntity catalogEntry
        where lower(catalogEntry.storeDisplayName) like lower(concat('%', :query, '%'))
           or lower(catalogEntry.brandDisplayName) like lower(concat('%', :query, '%'))
        order by catalogEntry.brandDisplayName asc, catalogEntry.storeDisplayName asc, catalogEntry.id asc
        """)
    List<StoreCatalogJpaEntity> search(@Param("query") String query, Pageable pageable);
}
