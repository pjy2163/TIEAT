package com.tieat.identity.domain;

import java.util.Optional;

public interface StoreAccountRepository {

    Optional<StoreAccount> findByLoginId(String loginId);
}
