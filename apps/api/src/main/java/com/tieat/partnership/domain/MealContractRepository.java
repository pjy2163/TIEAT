package com.tieat.partnership.domain;

import java.util.Optional;
import java.util.List;
import com.tieat.store.domain.StoreId;

public interface MealContractRepository {

    Optional<MealContract> findById(MealContractId id);

    Optional<MealContract> findByIdForUpdate(MealContractId id);

    List<QrSelectableMealContract> findQrSelectableByStoreId(StoreId storeId);

    MealContract save(MealContract mealContract);
}
