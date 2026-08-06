package com.tieat.identity.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "store_accounts")
class StoreAccountJpaEntity {

    @Id
    @Column(name = "login_id", nullable = false, length = 120)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false)
    private boolean enabled;

    protected StoreAccountJpaEntity() {
    }

    StoreAccountJpaEntity(String loginId, String passwordHash, UUID storeId, boolean enabled) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.storeId = storeId;
        this.enabled = enabled;
    }

    String loginId() {
        return loginId;
    }

    String passwordHash() {
        return passwordHash;
    }

    UUID storeId() {
        return storeId;
    }

    boolean enabled() {
        return enabled;
    }
}
