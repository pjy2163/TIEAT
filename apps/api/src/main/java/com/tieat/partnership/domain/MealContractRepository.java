package com.tieat.partnership.domain;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import com.tieat.store.domain.StoreId;

public interface MealContractRepository {

    Optional<MealContract> findById(MealContractId id);

    Optional<MealContract> findByIdForUpdate(MealContractId id);

    List<QrSelectableMealContract> findQrSelectableByStoreId(StoreId storeId);

    List<StorePartnerDirectoryEntry> findPartnerDirectoryByStoreId(StoreId storeId);

    Optional<StorePartnerDirectoryEntry> findPartnerDirectoryEntryByIdAndStoreId(
        MealContractId mealContractId,
        StoreId storeId
    );

    boolean existsPendingUsage(MealContractId mealContractId, StoreId storeId, Instant now);

    boolean existsOutstandingReceivable(MealContractId mealContractId, StoreId storeId);

    MealContract save(MealContract mealContract);
}
