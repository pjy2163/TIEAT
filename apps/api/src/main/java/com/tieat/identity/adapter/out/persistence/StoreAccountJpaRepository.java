package com.tieat.identity.adapter.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface StoreAccountJpaRepository extends JpaRepository<StoreAccountJpaEntity, String> {

    Optional<StoreAccountJpaEntity> findByLoginId(String loginId);
}
