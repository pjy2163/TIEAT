package com.tieat.partnership.application;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.StorePartnerDirectoryEntry;
import com.tieat.store.domain.StoreId;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StorePartnerDirectoryService {

    private final MealContractRepository mealContractRepository;

    StorePartnerDirectoryService(MealContractRepository mealContractRepository) {
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
    }

    @Transactional(readOnly = true)
    List<StorePartnerDirectoryEntry> list(StoreId actorStoreId) {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        return mealContractRepository.findPartnerDirectoryByStoreId(actorStoreId);
    }

    @Transactional(readOnly = true)
    StorePartnerDirectoryEntry requireOwnedPartner(StoreId actorStoreId, MealContractId mealContractId) {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        return mealContractRepository.findPartnerDirectoryEntryByIdAndStoreId(mealContractId, actorStoreId)
            .orElseThrow(StorePartnerNotFoundException::new);
    }
}
