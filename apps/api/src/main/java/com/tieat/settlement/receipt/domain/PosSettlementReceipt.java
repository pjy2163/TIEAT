package com.tieat.settlement.receipt.domain;

import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Metadata for the single private receipt attached to a POS settlement. */
public record PosSettlementReceipt(
    UUID id,
    UUID posSettlementId,
    StoreId storeId,
    String objectKey,
    String fileName,
    String contentType,
    long sizeBytes,
    Instant uploadedAt,
    Instant expiresAt,
    ValidationStatus validationStatus,
    Instant deletedAt
) {

    public PosSettlementReceipt {
        Objects.requireNonNull(id, "Receipt id must be supplied");
        Objects.requireNonNull(posSettlementId, "POS settlement id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Receipt object key must be supplied");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Receipt file name must be supplied");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Receipt content type must be supplied");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Receipt size must be positive");
        }
        Objects.requireNonNull(uploadedAt, "Receipt upload time must be supplied");
        Objects.requireNonNull(expiresAt, "Receipt expiry time must be supplied");
        if (!expiresAt.equals(uploadedAt.plusSeconds(365L * 24 * 60 * 60))) {
            throw new IllegalArgumentException("Receipt expiry must be upload time plus 365 days");
        }
        Objects.requireNonNull(validationStatus, "Receipt validation status must be supplied");
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "Current time must be supplied");
        return !now.isBefore(expiresAt);
    }

    public enum ValidationStatus {
        VALIDATED
    }
}
