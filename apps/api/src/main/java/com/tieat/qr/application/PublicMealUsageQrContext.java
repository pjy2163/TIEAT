package com.tieat.qr.application;

import com.tieat.partnership.domain.MealContractId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PublicMealUsageQrContext(
    String storeDisplayName,
    List<PartnerOption> partners,
    Instant qrExpiresAt
) {

    public PublicMealUsageQrContext {
        if (storeDisplayName == null || storeDisplayName.isBlank()) {
            throw new IllegalArgumentException("Store display name must not be blank");
        }
        partners = List.copyOf(Objects.requireNonNull(partners, "Partner options must be supplied"));
        Objects.requireNonNull(qrExpiresAt, "QR expiry must be supplied");
    }

    public record PartnerOption(MealContractId mealContractId, String partnerDisplayName) {

        public PartnerOption {
            Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
            if (partnerDisplayName == null || partnerDisplayName.isBlank()) {
                throw new IllegalArgumentException("Partner display name must not be blank");
            }
        }
    }
}
