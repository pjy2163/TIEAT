package com.tieat.qr.domain;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record QrOperationAudit(
    UUID id,
    Action action,
    String operatorId,
    StoreId storeId,
    MealUsageQrContextId qrContextId,
    MealContractId mealContractId,
    Boolean qrSelectableBefore,
    Boolean qrSelectableAfter,
    Instant occurredAt
) {

    public QrOperationAudit {
        Objects.requireNonNull(id, "QR operation audit id must be supplied");
        Objects.requireNonNull(action, "QR operation action must be supplied");
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("QR operator id must not be blank");
        }
        operatorId = operatorId.trim();
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(occurredAt, "QR operation time must be supplied");

        if (action == Action.QR_ISSUED || action == Action.QR_REVOKED) {
            if (qrContextId == null || mealContractId != null || qrSelectableBefore != null || qrSelectableAfter != null) {
                throw new IllegalArgumentException("QR context audit must only identify a QR context");
            }
        } else if (mealContractId == null || qrContextId != null || qrSelectableBefore == null || qrSelectableAfter == null
            || qrSelectableBefore.equals(qrSelectableAfter)) {
            throw new IllegalArgumentException("Partner selection audit must record a changed contract selection");
        }
    }

    public static QrOperationAudit qrIssued(
        String operatorId,
        StoreId storeId,
        MealUsageQrContextId contextId,
        Instant occurredAt
    ) {
        return new QrOperationAudit(UUID.randomUUID(), Action.QR_ISSUED, operatorId, storeId, contextId, null, null, null, occurredAt);
    }

    public static QrOperationAudit qrRevoked(
        String operatorId,
        StoreId storeId,
        MealUsageQrContextId contextId,
        Instant occurredAt
    ) {
        return new QrOperationAudit(UUID.randomUUID(), Action.QR_REVOKED, operatorId, storeId, contextId, null, null, null, occurredAt);
    }

    public static QrOperationAudit partnerSelectionChanged(
        String operatorId,
        StoreId storeId,
        MealContractId mealContractId,
        boolean before,
        boolean after,
        Instant occurredAt
    ) {
        Action action = after ? Action.PARTNER_QR_ENABLED : Action.PARTNER_QR_DISABLED;
        return new QrOperationAudit(
            UUID.randomUUID(), action, operatorId, storeId, null, mealContractId, before, after, occurredAt
        );
    }

    public enum Action {
        QR_ISSUED,
        QR_REVOKED,
        PARTNER_QR_ENABLED,
        PARTNER_QR_DISABLED
    }
}
