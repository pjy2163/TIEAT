package com.tieat.partnership.domain;

import com.tieat.store.domain.StoreId;
import java.util.Optional;

public interface StoreArchivePinSecurityRepository {

    void lockStore(StoreId storeId);

    Optional<StoreArchivePinSecurity> findByStoreId(StoreId storeId);

    Optional<StoreArchivePinSecurity> findByStoreIdForUpdate(StoreId storeId);

    StoreArchivePinSecurity save(StoreArchivePinSecurity security);
}
