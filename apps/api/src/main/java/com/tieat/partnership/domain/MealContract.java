package com.tieat.partnership.domain;

import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.Optional;
import java.time.Instant;

public final class MealContract {

    private final MealContractId id;
    private final StoreId storeId;
    private MealContractPaymentType paymentType;
    private final PartnerOrganizationId partnerOrganizationId;
    private final boolean qrSelectable;
    private Instant archivedAt;
    private String archivedByLoginId;
    private long prepaidBalance;

    public MealContract(
        MealContractId id,
        StoreId storeId,
        MealContractPaymentType paymentType,
        long prepaidBalance
    ) {
        this(id, storeId, paymentType, prepaidBalance, null, false);
    }

    public MealContract(
        MealContractId id,
        StoreId storeId,
        MealContractPaymentType paymentType,
        long prepaidBalance,
        PartnerOrganizationId partnerOrganizationId,
        boolean qrSelectable
    ) {
        this(id, storeId, paymentType, prepaidBalance, partnerOrganizationId, qrSelectable, null, null);
    }

    public MealContract(
        MealContractId id,
        StoreId storeId,
        MealContractPaymentType paymentType,
        long prepaidBalance,
        PartnerOrganizationId partnerOrganizationId,
        boolean qrSelectable,
        Instant archivedAt,
        String archivedByLoginId
    ) {
        this.id = Objects.requireNonNull(id, "Meal contract id must be supplied");
        this.storeId = Objects.requireNonNull(storeId, "Store id must be supplied");
        this.paymentType = Objects.requireNonNull(paymentType, "Payment type must be supplied");
        if (prepaidBalance < 0) {
            throw new IllegalArgumentException("Prepaid balance must not be negative");
        }
        if (paymentType == MealContractPaymentType.POSTPAID && prepaidBalance != 0) {
            throw new IllegalArgumentException("Postpaid contracts must have zero prepaid balance");
        }
        if (qrSelectable && partnerOrganizationId == null) {
            throw new IllegalArgumentException("QR-selectable contracts require a partner organization");
        }
        this.prepaidBalance = prepaidBalance;
        this.partnerOrganizationId = partnerOrganizationId;
        this.qrSelectable = qrSelectable;
        if (archivedAt == null && archivedByLoginId != null) {
            throw new IllegalArgumentException("Archived contracts must have an archive timestamp");
        }
        if (archivedAt != null && (archivedByLoginId == null || archivedByLoginId.isBlank())) {
            throw new IllegalArgumentException("Archived contracts must have an archive actor");
        }
        this.archivedAt = archivedAt;
        this.archivedByLoginId = archivedByLoginId;
    }

    public MealContractAllocation allocate(long usageAmount) {
        if (usageAmount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }

        long prepaidApplied = paymentType == MealContractPaymentType.POSTPAID
            ? 0
            : Math.min(prepaidBalance, usageAmount);
        long receivableCreated = usageAmount - prepaidApplied;
        prepaidBalance -= prepaidApplied;
        return new MealContractAllocation(usageAmount, prepaidApplied, receivableCreated, prepaidBalance);
    }

    public PaymentTermTransition changePaymentType(
        MealContractPaymentType expectedPaymentType,
        MealContractPaymentType newPaymentType,
        long newPrepaidBalance
    ) {
        if (expectedPaymentType == null || newPaymentType == null) {
            throw new IllegalArgumentException("Payment types must be supplied");
        }
        if (paymentType != expectedPaymentType) {
            throw new IllegalStateException("Payment type changed since the request was created");
        }
        if (paymentType == newPaymentType) {
            throw new IllegalArgumentException("Payment type must change");
        }
        long previousPrepaidBalance = prepaidBalance;
        if (newPaymentType == MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW) {
            if (newPrepaidBalance <= 0) {
                throw new IllegalArgumentException("Prepaid transition balance must be positive");
            }
            prepaidBalance = newPrepaidBalance;
        } else {
            if (prepaidBalance != 0) {
                throw new IllegalStateException("Prepaid balance must be zero before switching to postpaid");
            }
            prepaidBalance = 0;
        }
        paymentType = newPaymentType;
        return new PaymentTermTransition(
            expectedPaymentType,
            newPaymentType,
            previousPrepaidBalance,
            prepaidBalance
        );
    }

    public MealContractId id() {
        return id;
    }

    public StoreId storeId() {
        return storeId;
    }

    public MealContractPaymentType paymentType() {
        return paymentType;
    }

    public Optional<PartnerOrganizationId> partnerOrganizationId() {
        return Optional.ofNullable(partnerOrganizationId);
    }

    public boolean isQrSelectable() {
        return qrSelectable;
    }

    public long prepaidBalance() {
        return prepaidBalance;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public Optional<Instant> archivedAt() {
        return Optional.ofNullable(archivedAt);
    }

    public Optional<String> archivedByLoginId() {
        return Optional.ofNullable(archivedByLoginId);
    }

    public void archive(String actorLoginId, Instant archivedAt) {
        if (actorLoginId == null || actorLoginId.isBlank()) {
            throw new IllegalArgumentException("Archive actor must be supplied");
        }
        this.archivedAt = Objects.requireNonNull(archivedAt, "Archive timestamp must be supplied");
        this.archivedByLoginId = actorLoginId;
    }

    public record PaymentTermTransition(
        MealContractPaymentType previousPaymentType,
        MealContractPaymentType newPaymentType,
        long prepaidBalanceBefore,
        long prepaidBalanceAfter
    ) {
    }
}
