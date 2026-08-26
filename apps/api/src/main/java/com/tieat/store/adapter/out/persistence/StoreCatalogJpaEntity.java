package com.tieat.store.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "store_catalog_entries")
class StoreCatalogJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_display_name", nullable = false, length = 100)
    private String storeDisplayName;

    @Column(name = "brand_display_name", nullable = false, length = 100)
    private String brandDisplayName;

    @Column(name = "logo_path", length = 255)
    private String logoPath;

    protected StoreCatalogJpaEntity() {
    }

    StoreCatalogJpaEntity(UUID id, String storeDisplayName, String brandDisplayName, String logoPath) {
        this.id = id;
        this.storeDisplayName = storeDisplayName;
        this.brandDisplayName = brandDisplayName;
        this.logoPath = logoPath;
    }

    UUID id() {
        return id;
    }

    String storeDisplayName() {
        return storeDisplayName;
    }

    String brandDisplayName() {
        return brandDisplayName;
    }

    String logoPath() {
        return logoPath;
    }
}
