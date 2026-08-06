package com.tieat.identity.adapter.out.persistence;

import com.tieat.identity.domain.StoreAccount;
import com.tieat.identity.domain.StoreAccountRepository;
import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class StoreAccountPersistenceAdapter implements StoreAccountRepository {

    private final StoreAccountJpaRepository repository;

    public StoreAccountPersistenceAdapter(StoreAccountJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Optional<StoreAccount> findByLoginId(String loginId) {
        return repository.findByLoginId(loginId).map(account -> new StoreAccount(
            account.loginId(), account.passwordHash(), new StoreId(account.storeId()), account.enabled()
        ));
    }
}
