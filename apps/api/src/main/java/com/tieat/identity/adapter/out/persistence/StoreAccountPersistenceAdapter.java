package com.tieat.identity.adapter.out.persistence;

import com.tieat.identity.domain.StoreAccount;
import com.tieat.identity.domain.StoreAccountRepository;
import com.tieat.store.domain.StoreId;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class StoreAccountPersistenceAdapter implements StoreAccountRepository {

    private final StoreAccountJpaRepository repository;
    private final EntityManager entityManager;

    public StoreAccountPersistenceAdapter(StoreAccountJpaRepository repository, EntityManager entityManager) {
        this.repository = Objects.requireNonNull(repository);
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    public StoreAccount save(StoreAccount account) {
        Objects.requireNonNull(account, "Store account must be supplied");
        StoreAccountJpaEntity entity = new StoreAccountJpaEntity(
            account.loginId(), account.passwordHash(), account.storeId().value(), account.enabled()
        );
        entityManager.persist(entity);
        entityManager.flush();
        return toDomain(entity);
    }

    @Override
    public Optional<StoreAccount> findByLoginId(String loginId) {
        return repository.findByLoginId(loginId).map(this::toDomain);
    }

    private StoreAccount toDomain(StoreAccountJpaEntity account) {
        return new StoreAccount(
            account.loginId(), account.passwordHash(), new StoreId(account.storeId()), account.enabled()
        );
    }
}
