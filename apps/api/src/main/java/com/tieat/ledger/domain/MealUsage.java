package com.tieat.ledger.domain;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class MealUsage {

    private final MealUsageId id;
    private final StoreId storeId;
    private final MealContractId mealContractId;
    private final EntrySource entrySource;
    private final long amount;
    private final Instant createdAt;
    private final long version;
    private final String partnerDisplayNameSnapshot;
    private String customerNameSnapshot;
    private final MealUsageQrContextId publicQrContextId;
    private MealUsageStatus status;
    private Confirmation confirmation;
    private PrepaidAllocation prepaidAllocation;
    private Rejection rejection;
    private Cancellation cancellation;

    private MealUsage(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        String partnerDisplayNameSnapshot,
        String customerNameSnapshot,
        MealUsageQrContextId publicQrContextId,
        MealUsageStatus status,
        Confirmation confirmation,
        PrepaidAllocation prepaidAllocation,
        Rejection rejection,
        Cancellation cancellation
    ) {
        this.id = Objects.requireNonNull(id, "Meal usage id must be supplied");
        this.storeId = Objects.requireNonNull(storeId, "Store id must be supplied");
        this.mealContractId = Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        this.entrySource = Objects.requireNonNull(entrySource, "Entry source must be supplied");
        if (amount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }
        this.amount = amount;
        this.createdAt = Objects.requireNonNull(createdAt, "Created at must be supplied");
        if (version < 0) {
            throw new IllegalArgumentException("Meal usage version must not be negative");
        }
        this.version = version;
        if (partnerDisplayNameSnapshot != null && partnerDisplayNameSnapshot.isBlank()) {
            throw new IllegalArgumentException("Partner display name snapshot must not be blank");
        }
        this.partnerDisplayNameSnapshot = partnerDisplayNameSnapshot;
        this.customerNameSnapshot = normalizeOptionalCustomerNameSnapshot(customerNameSnapshot);
        this.publicQrContextId = publicQrContextId;
        this.status = Objects.requireNonNull(status, "Meal usage status must be supplied");
        this.confirmation = confirmation;
        this.prepaidAllocation = prepaidAllocation;
        this.rejection = rejection;
        this.cancellation = cancellation;
        validateLifecycleState();
    }

    public static MealUsage pending(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            0,
            null,
            null,
            null,
            MealUsageStatus.PENDING,
            null,
            null,
            null,
            null
        );
    }

    public static MealUsage pendingFromPublicQr(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        MealUsageQrContextId publicQrContextId,
        String partnerDisplayNameSnapshot,
        long amount,
        Instant createdAt
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            EntrySource.PARTNER_MOBILE,
            amount,
            createdAt,
            0,
            partnerDisplayNameSnapshot,
            null,
            Objects.requireNonNull(publicQrContextId, "Public QR context id must be supplied"),
            MealUsageStatus.PENDING,
            null,
            null,
            null,
            null
        );
    }

    public static MealUsage pendingFromPublicQr(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        MealUsageQrContextId publicQrContextId,
        String partnerDisplayNameSnapshot,
        String customerNameSnapshot,
        long amount,
        Instant createdAt
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            EntrySource.PARTNER_MOBILE,
            amount,
            createdAt,
            0,
            partnerDisplayNameSnapshot,
            requireNonBlankCustomerName(customerNameSnapshot),
            Objects.requireNonNull(publicQrContextId, "Public QR context id must be supplied"),
            MealUsageStatus.PENDING,
            null,
            null,
            null,
            null
        );
    }

    public static MealUsage restorePending(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version
    ) {
        return restorePending(id, storeId, mealContractId, entrySource, amount, createdAt, version, null, null, null);
    }

    public static MealUsage restorePending(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        String partnerDisplayNameSnapshot,
        MealUsageQrContextId publicQrContextId
    ) {
        return restorePending(
            id, storeId, mealContractId, entrySource, amount, createdAt, version, partnerDisplayNameSnapshot, publicQrContextId, null
        );
    }

    public static MealUsage restorePending(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        String partnerDisplayNameSnapshot,
        MealUsageQrContextId publicQrContextId,
        String customerNameSnapshot
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            partnerDisplayNameSnapshot,
            customerNameSnapshot,
            publicQrContextId,
            MealUsageStatus.PENDING,
            null,
            null,
            null,
            null
        );
    }

    public static MealUsage restoreConfirmed(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        Confirmation confirmation,
        PrepaidAllocation prepaidAllocation
    ) {
        return restoreConfirmed(
            id, storeId, mealContractId, entrySource, amount, createdAt, version, confirmation, prepaidAllocation, null, null, null
        );
    }

    public static MealUsage restoreConfirmed(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        Confirmation confirmation,
        PrepaidAllocation prepaidAllocation,
        String partnerDisplayNameSnapshot,
        MealUsageQrContextId publicQrContextId
    ) {
        return restoreConfirmed(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            confirmation,
            prepaidAllocation,
            partnerDisplayNameSnapshot,
            publicQrContextId,
            null
        );
    }

    public static MealUsage restoreConfirmed(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        Confirmation confirmation,
        PrepaidAllocation prepaidAllocation,
        String partnerDisplayNameSnapshot,
        MealUsageQrContextId publicQrContextId,
        String customerNameSnapshot
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            partnerDisplayNameSnapshot,
            customerNameSnapshot,
            publicQrContextId,
            MealUsageStatus.CONFIRMED,
            confirmation,
            prepaidAllocation,
            null,
            null
        );
    }

    public static MealUsage restoreRejected(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        Rejection rejection,
        String partnerDisplayNameSnapshot,
        MealUsageQrContextId publicQrContextId
    ) {
        return restoreRejected(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            rejection,
            partnerDisplayNameSnapshot,
            publicQrContextId,
            null
        );
    }

    public static MealUsage restoreRejected(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        Rejection rejection,
        String partnerDisplayNameSnapshot,
        MealUsageQrContextId publicQrContextId,
        String customerNameSnapshot
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            partnerDisplayNameSnapshot,
            customerNameSnapshot,
            publicQrContextId,
            MealUsageStatus.REJECTED,
            null,
            null,
            rejection,
            null
        );
    }

    public static MealUsage restoreCancelled(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        Cancellation cancellation,
        String partnerDisplayNameSnapshot,
        MealUsageQrContextId publicQrContextId
    ) {
        return restoreCancelled(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            cancellation,
            partnerDisplayNameSnapshot,
            publicQrContextId,
            null
        );
    }

    public static MealUsage restoreCancelled(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        Cancellation cancellation,
        String partnerDisplayNameSnapshot,
        MealUsageQrContextId publicQrContextId,
        String customerNameSnapshot
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            partnerDisplayNameSnapshot,
            customerNameSnapshot,
            publicQrContextId,
            MealUsageStatus.CANCELLED,
            null,
            null,
            null,
            cancellation
        );
    }

    public void confirm(String staffInitials, Instant confirmedAt, PrepaidAllocation allocation) {
        if (status != MealUsageStatus.PENDING) {
            throw new IllegalStateException("Only pending meal usages can be confirmed");
        }

        Confirmation nextConfirmation = new Confirmation(staffInitials, confirmedAt);
        PrepaidAllocation nextAllocation = Objects.requireNonNull(allocation, "Prepaid allocation must be supplied");
        if (nextAllocation.usageAmount() != amount) {
            throw new IllegalArgumentException("Prepaid allocation amount must match meal usage amount");
        }

        this.confirmation = nextConfirmation;
        this.prepaidAllocation = nextAllocation;
        this.status = MealUsageStatus.CONFIRMED;
    }

    public void reject(String staffLoginId, Instant rejectedAt) {
        if (status != MealUsageStatus.PENDING) {
            throw new IllegalStateException("Only pending meal usages can be rejected");
        }
        this.rejection = new Rejection(staffLoginId, rejectedAt);
        this.status = MealUsageStatus.REJECTED;
    }

    public void cancelFromPublicQr(Instant cancelledAt) {
        if (status != MealUsageStatus.PENDING || publicQrContextId == null) {
            throw new IllegalStateException("Only public QR pending meal usages can be cancelled");
        }
        this.cancellation = new Cancellation(CancellationReason.PUBLIC_SELF_CORRECTION, cancelledAt);
        this.status = MealUsageStatus.CANCELLED;
    }

    public EntrySource entrySource() {
        return entrySource;
    }

    public MealUsageId id() {
        return id;
    }

    public StoreId storeId() {
        return storeId;
    }

    public MealContractId mealContractId() {
        return mealContractId;
    }

    public long amount() {
        return amount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    public Optional<String> partnerDisplayNameSnapshot() {
        return Optional.ofNullable(partnerDisplayNameSnapshot);
    }

    public Optional<String> customerNameSnapshot() {
        return Optional.ofNullable(customerNameSnapshot);
    }

    public Optional<MealUsageQrContextId> publicQrContextId() {
        return Optional.ofNullable(publicQrContextId);
    }

    public MealUsageStatus status() {
        return status;
    }

    public Optional<Confirmation> confirmation() {
        return Optional.ofNullable(confirmation);
    }

    public Optional<PrepaidAllocation> prepaidAllocation() {
        return Optional.ofNullable(prepaidAllocation);
    }

    public Optional<Rejection> rejection() {
        return Optional.ofNullable(rejection);
    }

    public Optional<Cancellation> cancellation() {
        return Optional.ofNullable(cancellation);
    }

    private void validateLifecycleState() {
        if (publicQrContextId != null && entrySource != EntrySource.PARTNER_MOBILE) {
            throw new IllegalArgumentException("Public QR context requires partner mobile entry source");
        }
        if (publicQrContextId != null && partnerDisplayNameSnapshot == null) {
            throw new IllegalArgumentException("Public QR context requires partner display name snapshot");
        }
        if (status == MealUsageStatus.PENDING && (confirmation != null || prepaidAllocation != null || rejection != null || cancellation != null)) {
            throw new IllegalArgumentException("Pending meal usages must not have terminal data");
        }
        if (status == MealUsageStatus.CONFIRMED && (confirmation == null || prepaidAllocation == null || rejection != null || cancellation != null)) {
            throw new IllegalArgumentException("Confirmed meal usages require confirmation and allocation data");
        }
        if (status == MealUsageStatus.REJECTED && (confirmation != null || prepaidAllocation != null || rejection == null || cancellation != null)) {
            throw new IllegalArgumentException("Rejected meal usages require rejection audit data only");
        }
        if (status == MealUsageStatus.CANCELLED
            && (publicQrContextId == null || confirmation != null || prepaidAllocation != null || rejection != null || cancellation == null)) {
            throw new IllegalArgumentException("Cancelled meal usages require public QR cancellation data only");
        }
        if (prepaidAllocation != null && prepaidAllocation.usageAmount() != amount) {
            throw new IllegalArgumentException("Prepaid allocation amount must match meal usage amount");
        }
    }

    private static String requireNonBlankCustomerName(String customerNameSnapshot) {
        String normalized = normalizeOptionalCustomerNameSnapshot(customerNameSnapshot);
        if (normalized == null) {
            throw new IllegalArgumentException("Customer name snapshot must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptionalCustomerNameSnapshot(String customerNameSnapshot) {
        if (customerNameSnapshot == null) {
            return null;
        }
        String normalized = customerNameSnapshot.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Customer name snapshot must not be blank");
        }
        return normalized;
    }
}
