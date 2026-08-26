package com.tieat.identity.domain;

import java.util.Optional;

public interface StoreAccountRepository {

    StoreAccount save(StoreAccount account);

    Optional<StoreAccount> findByLoginId(String loginId);
}
