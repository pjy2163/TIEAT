package com.tieat.ledger.adapter.out.persistence;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageSlice;
import com.tieat.ledger.domain.MonthlyMealUsageRow;
import com.tieat.ledger.domain.MonthlyMealUsageSlice;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.ledger.domain.PrepaidAllocation;
import com.tieat.ledger.domain.Confirmation;
import com.tieat.ledger.domain.Rejection;
import com.tieat.ledger.domain.Cancellation;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import com.tieat.qr.domain.MealUsageQrContextId;
import java.time.Instant;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class MealUsagePersistenceAdapter implements MealUsageRepository {

    private final MealUsageJpaRepository repository;
    private final CustomerNameAnonymizationAuditJpaRepository anonymizationAuditRepository;

    public MealUsagePersistenceAdapter(
        MealUsageJpaRepository repository,
        CustomerNameAnonymizationAuditJpaRepository anonymizationAuditRepository
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.anonymizationAuditRepository = Objects.requireNonNull(anonymizationAuditRepository);
    }

    @Override
    public MealUsage save(MealUsage mealUsage) {
        Objects.requireNonNull(mealUsage, "Meal usage must be supplied");
        return toDomain(repository.saveAndFlush(toEntity(mealUsage)));
    }

    @Override
    public Optional<MealUsage> findById(MealUsageId id) {
        Objects.requireNonNull(id, "Meal usage id must be supplied");
        return repository.findById(id.value()).map(this::toDomain);
    }

    @Override
    public Optional<MealUsage> findByIdForUpdate(MealUsageId id) {
        Objects.requireNonNull(id, "Meal usage id must be supplied");
        return repository.findByIdForUpdate(id.value()).map(this::toDomain);
    }

    @Override
    public MealUsageSlice findPendingByStoreId(StoreId storeId, int page, int size) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        var pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"))
        );
        var result = repository.findByStoreIdAndStatus(storeId.value(), MealUsageStatus.PENDING, pageable);
        return new MealUsageSlice(result.getContent().stream().map(this::toDomain).toList(), result.hasNext());
    }

    @Override
    public MonthlyMealUsageSlice findConfirmedByStoreIdAndCreatedAtBetween(
        StoreId storeId,
        Instant startInclusive,
        Instant endExclusive,
        int page,
        int size
    ) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(startInclusive, "Monthly ledger start time must be supplied");
        Objects.requireNonNull(endExclusive, "Monthly ledger end time must be supplied");
        var pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))
        );
        var result = repository.findByStoreIdAndStatusAndCreatedAtBetween(
            storeId.value(), MealUsageStatus.CONFIRMED, startInclusive, endExclusive, pageable
        );
        var content = result.getContent();
        Set<java.util.UUID> allocatedUsageIds = content.isEmpty()
            ? Set.of()
            : Set.copyOf(repository.findSettlementAllocationUsageIds(
                storeId.value(), content.stream().map(MealUsageJpaEntity::id).toList()
            ));
        return new MonthlyMealUsageSlice(
            content.stream()
                .map(entity -> new MonthlyMealUsageRow(toDomain(entity), allocatedUsageIds.contains(entity.id())))
                .toList(),
            result.hasNext()
        );
    }

    @Override
    public long sumConfirmedByStoreIdAndCreatedAtBetween(
        StoreId storeId,
        Instant startInclusive,
        Instant endExclusive
    ) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(startInclusive, "Monthly ledger start time must be supplied");
        Objects.requireNonNull(endExclusive, "Monthly ledger end time must be supplied");
        return repository.sumByStoreIdAndStatusAndCreatedAtBetween(
            storeId.value(), MealUsageStatus.CONFIRMED, startInclusive, endExclusive
        );
    }

    @Override
    public long countPublicQrCreatedSince(MealUsageQrContextId qrContextId, Instant since) {
        Objects.requireNonNull(qrContextId, "Meal usage QR context id must be supplied");
        Objects.requireNonNull(since, "Public QR rate limit time must be supplied");
        return repository.countByPublicQrContextIdAndCreatedAtGreaterThanEqual(qrContextId.value(), since);
    }

    @Override
    public int anonymizeCustomerNamesCreatedBefore(Instant cutoffExclusive, Instant executedAt) {
        Objects.requireNonNull(cutoffExclusive, "Customer name cutoff must be supplied");
        Objects.requireNonNull(executedAt, "Customer name anonymization time must be supplied");
        int affectedCount = repository.anonymizeCustomerNamesCreatedBefore(cutoffExclusive);
        anonymizationAuditRepository.save(new CustomerNameAnonymizationAuditJpaEntity(
            java.util.UUID.randomUUID(), cutoffExclusive, executedAt, affectedCount
        ));
        return affectedCount;
    }

    private MealUsageJpaEntity toEntity(MealUsage mealUsage) {
        Confirmation confirmation = mealUsage.confirmation().orElse(null);
        PrepaidAllocation prepaidAllocation = mealUsage.prepaidAllocation().orElse(null);
        Rejection rejection = mealUsage.rejection().orElse(null);
        Cancellation cancellation = mealUsage.cancellation().orElse(null);
        return new MealUsageJpaEntity(
            mealUsage.id().value(),
            mealUsage.storeId().value(),
            mealUsage.mealContractId().value(),
            mealUsage.entrySource(),
            mealUsage.amount(),
            mealUsage.createdAt(),
            mealUsage.partnerDisplayNameSnapshot().orElse(null),
            mealUsage.customerNameSnapshot().orElse(null),
            mealUsage.publicQrContextId().map(MealUsageQrContextId::value).orElse(null),
            mealUsage.version(),
            mealUsage.status(),
            confirmation == null ? null : confirmation.staffInitials(),
            confirmation == null ? null : confirmation.confirmedAt(),
            prepaidAllocation == null ? null : prepaidAllocation.prepaidApplied(),
            prepaidAllocation == null ? null : prepaidAllocation.receivableCreated(),
            prepaidAllocation == null ? null : prepaidAllocation.remainingPrepaid(),
            rejection == null ? null : rejection.staffLoginId(),
            rejection == null ? null : rejection.rejectedAt(),
            cancellation == null ? null : cancellation.cancelledAt(),
            cancellation == null ? null : cancellation.reason()
        );
    }

    private MealUsage toDomain(MealUsageJpaEntity entity) {
        if (entity.status() == MealUsageStatus.PENDING) {
            return MealUsage.restorePending(
                new MealUsageId(entity.id()),
                new StoreId(entity.storeId()),
                new MealContractId(entity.mealContractId()),
                entity.entrySource(),
                entity.amount(),
                entity.createdAt(),
                entity.version(),
                entity.partnerDisplayName(),
                qrContextId(entity),
                entity.customerName()
            );
        }
        if (entity.status() == MealUsageStatus.CONFIRMED) {
            return MealUsage.restoreConfirmed(
                new MealUsageId(entity.id()),
                new StoreId(entity.storeId()),
                new MealContractId(entity.mealContractId()),
                entity.entrySource(),
                entity.amount(),
                entity.createdAt(),
                entity.version(),
                new Confirmation(entity.confirmedStaffInitials(), entity.confirmedAt()),
                new PrepaidAllocation(
                    entity.amount(),
                    requiredAllocationValue(entity.prepaidApplied(), "prepaid applied"),
                    requiredAllocationValue(entity.receivableCreated(), "receivable created"),
                    requiredAllocationValue(entity.remainingPrepaid(), "remaining prepaid")
                ),
                entity.partnerDisplayName(),
                qrContextId(entity),
                entity.customerName()
            );
        }
        if (entity.status() == MealUsageStatus.REJECTED) {
            return MealUsage.restoreRejected(
                new MealUsageId(entity.id()),
                new StoreId(entity.storeId()),
                new MealContractId(entity.mealContractId()),
                entity.entrySource(),
                entity.amount(),
                entity.createdAt(),
                entity.version(),
                new Rejection(entity.rejectedStaffLoginId(), entity.rejectedAt()),
                entity.partnerDisplayName(),
                qrContextId(entity),
                entity.customerName()
            );
        }
        if (entity.status() == MealUsageStatus.CANCELLED) {
            return MealUsage.restoreCancelled(
                new MealUsageId(entity.id()),
                new StoreId(entity.storeId()),
                new MealContractId(entity.mealContractId()),
                entity.entrySource(),
                entity.amount(),
                entity.createdAt(),
                entity.version(),
                new Cancellation(entity.cancellationReason(), entity.cancelledAt()),
                entity.partnerDisplayName(),
                qrContextId(entity),
                entity.customerName()
            );
        }
        throw new IllegalStateException("Unsupported meal usage status: " + entity.status());
    }

    private long requiredAllocationValue(Long value, String fieldName) {
        if (value == null) {
            throw new IllegalStateException("Confirmed meal usage is missing " + fieldName);
        }
        return value;
    }

    private MealUsageQrContextId qrContextId(MealUsageJpaEntity entity) {
        return entity.publicQrContextId() == null ? null : new MealUsageQrContextId(entity.publicQrContextId());
    }
}
