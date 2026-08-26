package com.tieat.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PosSettlementTest {

    private static final StoreId STORE_ID = new StoreId(
        UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb")
    );
    private static final MealContractId CONTRACT_ID = new MealContractId(
        UUID.fromString("f49a63ea-e09e-4ce6-8e36-e531521cdbcf")
    );
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 11);
    private static final long TOTAL_MINOR = 3_000;
    private static final UUID FIRST_USAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_USAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void matchesRequestCanonicalizesRequestedUsageIdsBeforeExactComparison() {
        PosSettlement settlement = settlement();

        assertThat(settlement.matchesRequest(
            CONTRACT_ID,
            BUSINESS_DATE,
            TOTAL_MINOR,
            List.of(SECOND_USAGE_ID, FIRST_USAGE_ID)
        )).isTrue();
    }

    @Test
    void rejectsDifferentMissingAdditionalAndDuplicateRequestedUsageIds() {
        PosSettlement settlement = settlement();
        UUID differentUsageId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        assertThat(settlement.matchesRequest(
            CONTRACT_ID, BUSINESS_DATE, TOTAL_MINOR, List.of(FIRST_USAGE_ID, differentUsageId)
        )).isFalse();
        assertThat(settlement.matchesRequest(
            CONTRACT_ID, BUSINESS_DATE, TOTAL_MINOR, List.of(FIRST_USAGE_ID)
        )).isFalse();
        assertThat(settlement.matchesRequest(
            CONTRACT_ID, BUSINESS_DATE, TOTAL_MINOR, List.of(FIRST_USAGE_ID, SECOND_USAGE_ID, differentUsageId)
        )).isFalse();
        assertThat(settlement.matchesRequest(
            CONTRACT_ID, BUSINESS_DATE, TOTAL_MINOR, List.of(FIRST_USAGE_ID, FIRST_USAGE_ID)
        )).isFalse();
    }

    @Test
    void rejectsNullRequestedUsageIds() {
        assertThatNullPointerException().isThrownBy(() -> settlement().matchesRequest(
            CONTRACT_ID,
            BUSINESS_DATE,
            TOTAL_MINOR,
            null
        ));
    }

    private PosSettlement settlement() {
        return new PosSettlement(
            UUID.fromString("00000000-0000-0000-0000-000000000010"),
            STORE_ID,
            CONTRACT_ID,
            BUSINESS_DATE,
            TOTAL_MINOR,
            "store-hk",
            Instant.parse("2026-08-12T03:45:00Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000020"),
            List.of(
                new PosSettlement.Allocation(SECOND_USAGE_ID, 2_000),
                new PosSettlement.Allocation(FIRST_USAGE_ID, 1_000)
            )
        );
    }
}
