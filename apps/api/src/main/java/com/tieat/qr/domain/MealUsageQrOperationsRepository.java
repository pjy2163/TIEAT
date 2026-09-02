package com.tieat.qr.domain;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface MealUsageQrOperationsRepository {

    void lockStoreForOperations(StoreId storeId);

    boolean enabledStoreAccountExists(StoreId storeId);

    Optional<MealUsageQrContext> findCurrentByStoreId(StoreId storeId);

    Optional<MealUsageQrContext> findCurrentByStoreIdForUpdate(StoreId storeId);

    void insert(MealUsageQrContext context);

    void revoke(MealUsageQrContextId contextId, Instant revokedAt);

    void renew(MealUsageQrContextId contextId, Instant renewedExpiresAt);

    void setAcceptingNewRequests(MealUsageQrContextId contextId, boolean accepting);

    List<QrPartnerSelection> findPartnerSelectionsByStoreId(StoreId storeId);

    Optional<QrPartnerSelection> findPartnerSelectionByIdAndStoreIdForUpdate(
        MealContractId mealContractId,
        StoreId storeId
    );

    void updateQrSelectable(MealContractId mealContractId, StoreId storeId, boolean qrSelectable);

    void appendAudit(QrOperationAudit audit);

    record QrPartnerSelection(MealContractId mealContractId, String partnerDisplayName, boolean qrSelectable) {

        public QrPartnerSelection {
            Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
            if (partnerDisplayName == null || partnerDisplayName.isBlank()) {
                throw new IllegalArgumentException("Partner display name must not be blank");
            }
        }
    }
}
