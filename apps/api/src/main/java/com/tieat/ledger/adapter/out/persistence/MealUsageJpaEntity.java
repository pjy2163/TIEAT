package com.tieat.ledger.adapter.out.persistence;

import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.CancellationReason;
import com.tieat.ledger.domain.MealUsageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meal_usages")
class MealUsageJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "meal_contract_id", nullable = false)
    private UUID mealContractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_source", nullable = false, length = 32)
    private EntrySource entrySource;

    @Column(nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "partner_display_name", columnDefinition = "TEXT")
    private String partnerDisplayName;

    @Column(name = "public_qr_context_id")
    private UUID publicQrContextId;

    @Version
    @Column(nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MealUsageStatus status;

    @Column(name = "confirmed_staff_initials", columnDefinition = "TEXT")
    private String confirmedStaffInitials;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "prepaid_applied")
    private Long prepaidApplied;

    @Column(name = "receivable_created")
    private Long receivableCreated;

    @Column(name = "remaining_prepaid")
    private Long remainingPrepaid;

    @Column(name = "rejected_staff_login_id", length = 120)
    private String rejectedStaffLoginId;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 64)
    private CancellationReason cancellationReason;

    protected MealUsageJpaEntity() {
    }

    MealUsageJpaEntity(
        UUID id,
        UUID storeId,
        UUID mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        String partnerDisplayName,
        UUID publicQrContextId,
        long version,
        MealUsageStatus status,
        String confirmedStaffInitials,
        Instant confirmedAt,
        Long prepaidApplied,
        Long receivableCreated,
        Long remainingPrepaid,
        String rejectedStaffLoginId,
        Instant rejectedAt,
        Instant cancelledAt,
        CancellationReason cancellationReason
    ) {
        this.id = id;
        this.storeId = storeId;
        this.mealContractId = mealContractId;
        this.entrySource = entrySource;
        this.amount = amount;
        this.createdAt = createdAt;
        this.partnerDisplayName = partnerDisplayName;
        this.publicQrContextId = publicQrContextId;
        this.version = version;
        this.status = status;
        this.confirmedStaffInitials = confirmedStaffInitials;
        this.confirmedAt = confirmedAt;
        this.prepaidApplied = prepaidApplied;
        this.receivableCreated = receivableCreated;
        this.remainingPrepaid = remainingPrepaid;
        this.rejectedStaffLoginId = rejectedStaffLoginId;
        this.rejectedAt = rejectedAt;
        this.cancelledAt = cancelledAt;
        this.cancellationReason = cancellationReason;
    }

    UUID id() {
        return id;
    }

    UUID storeId() {
        return storeId;
    }

    UUID mealContractId() {
        return mealContractId;
    }

    EntrySource entrySource() {
        return entrySource;
    }

    long amount() {
        return amount;
    }

    Instant createdAt() {
        return createdAt;
    }

    String partnerDisplayName() {
        return partnerDisplayName;
    }

    UUID publicQrContextId() {
        return publicQrContextId;
    }

    long version() {
        return version;
    }

    MealUsageStatus status() {
        return status;
    }

    String confirmedStaffInitials() {
        return confirmedStaffInitials;
    }

    Instant confirmedAt() {
        return confirmedAt;
    }

    Long prepaidApplied() {
        return prepaidApplied;
    }

    Long receivableCreated() {
        return receivableCreated;
    }

    Long remainingPrepaid() {
        return remainingPrepaid;
    }

    String rejectedStaffLoginId() {
        return rejectedStaffLoginId;
    }

    Instant rejectedAt() {
        return rejectedAt;
    }

    Instant cancelledAt() {
        return cancelledAt;
    }

    CancellationReason cancellationReason() {
        return cancellationReason;
    }
}
