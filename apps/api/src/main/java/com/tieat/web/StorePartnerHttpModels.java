package com.tieat.web;

import com.tieat.partnership.application.StorePartnerValidationException;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.PartnerKind;
import com.tieat.partnership.domain.StorePartnerDirectoryEntry;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class StorePartnerHttpModels {

    private StorePartnerHttpModels() {
    }

    record StorePartnerRequest(
        String partnerName,
        PartnerKind partnerKind,
        MealContractPaymentType paymentType,
        JsonNode initialPrepaidBalanceMinor,
        Boolean qrSelectable,
        String representativePhone,
        String representativeEmail
    ) {
    }

    record PaymentTermRequest(
        MealContractPaymentType expectedPaymentType,
        MealContractPaymentType paymentType,
        JsonNode prepaidBalanceMinor
    ) {
    }

    record ArchivePinRequest(String pin) {
    }

    record ArchivePinSettingsRequest(
        String currentPin,
        String accountPassword,
        String newPin,
        String newPinConfirmation
    ) {
    }

    record ArchivePinResponse(boolean configured) {
    }

    record StorePartnerResponse(
        UUID mealContractId,
        UUID partnerOrganizationId,
        String partnerDisplayName,
        String partnerKind,
        String paymentType,
        boolean qrSelectable,
        String representativePhone,
        String representativeEmail
    ) {

        static StorePartnerResponse from(StorePartnerDirectoryEntry entry) {
            return new StorePartnerResponse(
                entry.mealContractId().value(),
                entry.partnerOrganizationId() == null ? null : entry.partnerOrganizationId().value(),
                entry.partnerDisplayName(),
                entry.partnerKind().name(),
                entry.paymentType().name(),
                entry.qrSelectable(),
                entry.representativePhone(),
                entry.representativeEmail()
            );
        }
    }

    record StoreProfileResponse(String loginId, String storeDisplayName) {
    }

    static Long initialPrepaidBalanceMinor(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new StorePartnerValidationException();
        }
        return value.longValue();
    }

    static Long optionalPrepaidBalanceMinor(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return initialPrepaidBalanceMinor(value);
    }
}
