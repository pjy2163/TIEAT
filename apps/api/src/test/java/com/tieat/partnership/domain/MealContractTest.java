package com.tieat.partnership.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tieat.store.domain.StoreId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MealContractTest {

    @Test
    void allocatesPrepaidBeforeReceivableAndKeepsTheContractBalance() {
        MealContract mealContract = prepaid(10_000);

        MealContractAllocation first = mealContract.allocate(8_000);
        MealContractAllocation second = mealContract.allocate(8_000);

        assertThat(first).isEqualTo(new MealContractAllocation(8_000, 8_000, 0, 2_000));
        assertThat(second).isEqualTo(new MealContractAllocation(8_000, 2_000, 6_000, 0));
        assertThat(mealContract.prepaidBalance()).isZero();
    }

    @Test
    void allocatesPostpaidUsageEntirelyAsReceivable() {
        MealContract mealContract = new MealContract(
            contractId(), storeId(), MealContractPaymentType.POSTPAID, 0
        );

        assertThat(mealContract.allocate(8_000)).isEqualTo(
            new MealContractAllocation(8_000, 0, 8_000, 0)
        );
        assertThat(mealContract.prepaidBalance()).isZero();
    }

    @Test
    void rejectsNegativeBalanceAndNonzeroPostpaidBalance() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MealContract(
            contractId(), storeId(), MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW, -1
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new MealContract(
            contractId(), storeId(), MealContractPaymentType.POSTPAID, 1
        ));
    }

    @Test
    void rejectsNonpositiveUsageAmount() {
        MealContract mealContract = prepaid(10_000);

        assertThatIllegalArgumentException().isThrownBy(() -> mealContract.allocate(0));
        assertThatIllegalArgumentException().isThrownBy(() -> mealContract.allocate(-1));
        assertThat(mealContract.prepaidBalance()).isEqualTo(10_000);
    }

    private MealContract prepaid(long prepaidBalance) {
        return new MealContract(
            contractId(), storeId(), MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW, prepaidBalance
        );
    }

    private MealContractId contractId() {
        return new MealContractId(UUID.fromString("019c0f9c-6d58-7d37-b0e3-1af21f7124b9"));
    }

    private StoreId storeId() {
        return new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    }
}
