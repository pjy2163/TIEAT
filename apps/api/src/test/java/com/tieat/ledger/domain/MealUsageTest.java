package com.tieat.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MealUsageTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-05T09:15:30Z");

    @Test
    void createsPartnerMobileUsageAsPending() {
        MealUsage usage = pending(EntrySource.PARTNER_MOBILE);

        assertThat(usage.id()).isEqualTo(id());
        assertThat(usage.storeId()).isEqualTo(storeId());
        assertThat(usage.mealContractId()).isEqualTo(mealContractId());
        assertThat(usage.entrySource()).isEqualTo(EntrySource.PARTNER_MOBILE);
        assertThat(usage.createdAt()).isEqualTo(CONFIRMED_AT.minusSeconds(60));
        assertThat(usage.version()).isZero();
        assertThat(usage.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(usage.confirmation()).isEmpty();
        assertThat(usage.prepaidAllocation()).isEmpty();
    }

    @Test
    void createsStoreTabletUsageAsPending() {
        MealUsage usage = pending(EntrySource.STORE_TABLET);

        assertThat(usage.entrySource()).isEqualTo(EntrySource.STORE_TABLET);
        assertThat(usage.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(usage.confirmation()).isEmpty();
        assertThat(usage.prepaidAllocation()).isEmpty();
    }

    @Test
    void confirmsWithSufficientPrepaidAndStoresAuditAndAllocation() {
        MealUsage usage = pending(EntrySource.PARTNER_MOBILE);

        usage.confirm("HK", CONFIRMED_AT, 20_000);

        assertThat(usage.status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(usage.confirmation()).contains(new Confirmation("HK", CONFIRMED_AT));
        assertThat(usage.prepaidAllocation()).contains(
            new PrepaidAllocation(12_000, 12_000, 0, 8_000)
        );
        assertAllocationInvariant(usage);
        assertThat(usage.storeId()).isEqualTo(storeId());
        assertThat(usage.mealContractId()).isEqualTo(mealContractId());
        assertThat(usage.createdAt()).isEqualTo(CONFIRMED_AT.minusSeconds(60));
    }

    @Test
    void confirmsWithInsufficientPrepaidAndCreatesReceivable() {
        MealUsage usage = pending(EntrySource.STORE_TABLET);

        usage.confirm("HK", CONFIRMED_AT, 5_000);

        assertThat(usage.status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(usage.prepaidAllocation()).contains(
            new PrepaidAllocation(12_000, 5_000, 7_000, 0)
        );
        assertAllocationInvariant(usage);
    }

    @Test
    void confirmsWithZeroPrepaidAndCreatesFullReceivable() {
        MealUsage usage = pending(EntrySource.PARTNER_MOBILE);

        usage.confirm("HK", CONFIRMED_AT, 0);

        assertThat(usage.prepaidAllocation()).contains(
            new PrepaidAllocation(12_000, 0, 12_000, 0)
        );
        assertAllocationInvariant(usage);
    }

    @Test
    void rejectsRepeatedConfirmation() {
        MealUsage usage = pending(EntrySource.PARTNER_MOBILE);
        usage.confirm("HK", CONFIRMED_AT, 12_000);

        assertThatIllegalStateException()
            .isThrownBy(() -> usage.confirm("HK", CONFIRMED_AT.plusSeconds(1), 12_000));
        assertThat(usage.confirmation()).contains(new Confirmation("HK", CONFIRMED_AT));
        assertThat(usage.prepaidAllocation()).contains(
            new PrepaidAllocation(12_000, 12_000, 0, 0)
        );
    }

    @Test
    void rejectsBlankStaffInitials() {
        MealUsage usage = pending(EntrySource.PARTNER_MOBILE);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> usage.confirm("  ", CONFIRMED_AT, 12_000));
        assertThat(usage.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(usage.confirmation()).isEmpty();
        assertThat(usage.prepaidAllocation()).isEmpty();
    }

    @Test
    void rejectsMissingServerSuppliedConfirmationTime() {
        MealUsage usage = pending(EntrySource.PARTNER_MOBILE);

        assertThatNullPointerException()
            .isThrownBy(() -> usage.confirm("HK", null, 12_000));
        assertThat(usage.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(usage.confirmation()).isEmpty();
        assertThat(usage.prepaidAllocation()).isEmpty();
    }

    @Test
    void rejectsZeroOrNegativeUsageAmounts() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> MealUsage.pending(
                id(), storeId(), mealContractId(), EntrySource.PARTNER_MOBILE, 0, createdAt()
            ));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> MealUsage.pending(
                id(), storeId(), mealContractId(), EntrySource.PARTNER_MOBILE, -1, createdAt()
            ));
    }

    @Test
    void rejectsMissingEntrySource() {
        assertThatNullPointerException()
            .isThrownBy(() -> MealUsage.pending(id(), storeId(), mealContractId(), null, 12_000, createdAt()));
    }

    @Test
    void rejectsMissingScopeAndServerCreatedTime() {
        assertThatNullPointerException().isThrownBy(
            () -> MealUsage.pending(id(), null, mealContractId(), EntrySource.PARTNER_MOBILE, 12_000, createdAt())
        );
        assertThatNullPointerException().isThrownBy(
            () -> MealUsage.pending(id(), storeId(), null, EntrySource.PARTNER_MOBILE, 12_000, createdAt())
        );
        assertThatNullPointerException().isThrownBy(
            () -> MealUsage.pending(id(), storeId(), mealContractId(), EntrySource.PARTNER_MOBILE, 12_000, null)
        );
    }

    @Test
    void rejectsNegativeAvailablePrepaid() {
        MealUsage usage = pending(EntrySource.PARTNER_MOBILE);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> usage.confirm("HK", CONFIRMED_AT, -1));
        assertThat(usage.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(usage.confirmation()).isEmpty();
        assertThat(usage.prepaidAllocation()).isEmpty();
    }

    @Test
    void restoresConfirmedUsageWithoutReplayingConfirmation() {
        Confirmation confirmation = new Confirmation("HK", CONFIRMED_AT);
        PrepaidAllocation allocation = new PrepaidAllocation(12_000, 5_000, 7_000, 0);

        MealUsage restored = MealUsage.restoreConfirmed(
            id(),
            storeId(),
            mealContractId(),
            EntrySource.PARTNER_MOBILE,
            12_000,
            createdAt(),
            3,
            confirmation,
            allocation
        );

        assertThat(restored.status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(restored.version()).isEqualTo(3);
        assertThat(restored.confirmation()).contains(confirmation);
        assertThat(restored.prepaidAllocation()).contains(allocation);
    }

    @Test
    void rejectsInvalidRestoredLifecycleState() {
        assertThatIllegalArgumentException().isThrownBy(() -> MealUsage.restoreConfirmed(
            id(),
            storeId(),
            mealContractId(),
            EntrySource.PARTNER_MOBILE,
            12_000,
            createdAt(),
            0,
            null,
            new PrepaidAllocation(12_000, 12_000, 0, 0)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> MealUsage.restorePending(
            id(), storeId(), mealContractId(), EntrySource.PARTNER_MOBILE, 12_000, createdAt(), -1
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> MealUsage.restoreConfirmed(
            id(),
            storeId(),
            mealContractId(),
            EntrySource.PARTNER_MOBILE,
            12_000,
            createdAt(),
            0,
            new Confirmation("HK", CONFIRMED_AT),
            new PrepaidAllocation(10_000, 10_000, 0, 0)
        ));
    }

    private void assertAllocationInvariant(MealUsage usage) {
        PrepaidAllocation allocation = usage.prepaidAllocation().orElseThrow();
        assertThat(allocation.prepaidApplied() + allocation.receivableCreated())
            .isEqualTo(usage.amount());
        assertThat(allocation.prepaidApplied()).isGreaterThanOrEqualTo(0);
        assertThat(allocation.receivableCreated()).isGreaterThanOrEqualTo(0);
        assertThat(allocation.remainingPrepaid()).isGreaterThanOrEqualTo(0);
    }

    private MealUsageId id() {
        return new MealUsageId(UUID.fromString("d9ef1fd5-2a3c-4fce-bb20-0d1b26a634ab"));
    }

    private StoreId storeId() {
        return new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    }

    private MealContractId mealContractId() {
        return new MealContractId(UUID.fromString("019c0f9c-6d58-7d37-b0e3-1af21f7124b9"));
    }

    private Instant createdAt() {
        return CONFIRMED_AT.minusSeconds(60);
    }

    private MealUsage pending(EntrySource entrySource) {
        return MealUsage.pending(id(), storeId(), mealContractId(), entrySource, 12_000, createdAt());
    }
}
