package com.tieat.qr.adapter.out.persistence;

import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.qr.domain.MealUsageQrContextRepository;
import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MealUsageQrContextPersistenceAdapter implements MealUsageQrContextRepository {

    private final MealUsageQrContextJpaRepository repository;

    public MealUsageQrContextPersistenceAdapter(MealUsageQrContextJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Optional<MealUsageQrContext> findByTokenHash(String tokenHash) {
        Objects.requireNonNull(tokenHash, "QR token hash must be supplied");
        return repository.findByTokenHash(tokenHash).map(this::toDomain);
    }

    @Override
    public Optional<MealUsageQrContext> findByTokenHashForUpdate(String tokenHash) {
        Objects.requireNonNull(tokenHash, "QR token hash must be supplied");
        return repository.findByTokenHashForUpdate(tokenHash).map(this::toDomain);
    }

    private MealUsageQrContext toDomain(MealUsageQrContextJpaEntity entity) {
        MealUsageQrContext.ProtectedToken protectedToken = protectedToken(
            entity.tokenCiphertext(), entity.tokenNonce(), entity.tokenKeyVersion()
        );
        return new MealUsageQrContext(
            new MealUsageQrContextId(entity.id()),
            new StoreId(entity.storeId()),
            entity.storeDisplayName(),
            entity.tokenHash(),
            entity.createdAt(),
            entity.expiresAt(),
            entity.revokedAt(),
            protectedToken
        );
    }

    private MealUsageQrContext.ProtectedToken protectedToken(byte[] ciphertext, byte[] nonce, Integer keyVersion) {
        if (ciphertext == null && nonce == null && keyVersion == null) {
            return null;
        }
        if (ciphertext == null || nonce == null || keyVersion == null) {
            throw new IllegalStateException("QR token protection columns are incomplete");
        }
        return new MealUsageQrContext.ProtectedToken(ciphertext, nonce, keyVersion);
    }
}
