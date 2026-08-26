package com.tieat.store.application;

import com.tieat.store.domain.StoreId;
import com.tieat.store.domain.StoreRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreProfileService {

    private final StoreRepository storeRepository;

    public StoreProfileService(StoreRepository storeRepository) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
    }

    @Transactional(readOnly = true)
    public StoreProfile profile(StoreId storeId, String loginId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("Login id must be supplied");
        }
        String storeDisplayName = storeRepository.findById(storeId)
            .map(store -> store.displayName())
            .orElse(null);
        return new StoreProfile(loginId, storeDisplayName);
    }

    public record StoreProfile(String loginId, String storeDisplayName) {

        public StoreProfile {
            if (loginId == null || loginId.isBlank()) {
                throw new IllegalArgumentException("Login id must be supplied");
            }
        }
    }
}
