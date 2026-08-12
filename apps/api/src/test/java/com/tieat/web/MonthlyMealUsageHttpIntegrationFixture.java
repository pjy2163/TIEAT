package com.tieat.web;

import com.tieat.ledger.domain.Cancellation;
import com.tieat.ledger.domain.CancellationReason;
import com.tieat.ledger.domain.Confirmation;
import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.PrepaidAllocation;
import com.tieat.ledger.domain.Rejection;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.store.domain.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

final class MonthlyMealUsageHttpIntegrationFixture {

    static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    static final StoreId OTHER_STORE_ID = new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1"));
    static final MealContractId MEAL_CONTRACT_ID = new MealContractId(
        UUID.fromString("8cb73a47-d5c5-4f7a-8db0-b61e171c4f0a")
    );

    private MonthlyMealUsageHttpIntegrationFixture() {
    }

    static void seedAccount(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, String loginId, String password, StoreId storeId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId,
            passwordEncoder.encode(password),
            storeId.value()
        );
    }

    static MealUsage pending(StoreId storeId, String id, String createdAt) {
        return MealUsage.pending(
            new MealUsageId(UUID.fromString(id)),
            storeId,
            MEAL_CONTRACT_ID,
            EntrySource.STORE_TABLET,
            12_000,
            Instant.parse(createdAt)
        );
    }

    static MealUsage confirmed(StoreId storeId, String id, String createdAt, String partnerDisplayName) {
        return MealUsage.restoreConfirmed(
            new MealUsageId(UUID.fromString(id)),
            storeId,
            MEAL_CONTRACT_ID,
            EntrySource.PARTNER_MOBILE,
            12_000,
            Instant.parse(createdAt),
            0,
            new Confirmation("HK", Instant.parse("2026-08-15T02:00:00Z")),
            new PrepaidAllocation(12_000, 0, 12_000, 0),
            partnerDisplayName,
            null
        );
    }

    static MealUsage rejected(StoreId storeId, String id, String createdAt, String partnerDisplayName) {
        return MealUsage.restoreRejected(
            new MealUsageId(UUID.fromString(id)),
            storeId,
            MEAL_CONTRACT_ID,
            EntrySource.STORE_TABLET,
            12_000,
            Instant.parse(createdAt),
            0,
            new Rejection("store-hk", Instant.parse("2026-08-15T02:00:00Z")),
            partnerDisplayName,
            null
        );
    }

    static MealUsage cancelled(JdbcTemplate jdbcTemplate, StoreId storeId, String id, String createdAt, String cancelledAt) {
        UUID qrContextId = UUID.randomUUID();
        Instant issuedAt = Instant.parse(createdAt);
        jdbcTemplate.update(
            """
                insert into meal_usage_qr_contexts
                    (id, store_id, store_display_name, token_hash, expires_at, revoked_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
            qrContextId,
            storeId.value(),
            "TIEAT Store",
            "a".repeat(64),
            Timestamp.from(issuedAt.plusSeconds(86_400)),
            null,
            Timestamp.from(issuedAt)
        );
        return MealUsage.restoreCancelled(
            new MealUsageId(UUID.fromString(id)),
            storeId,
            MEAL_CONTRACT_ID,
            EntrySource.PARTNER_MOBILE,
            12_000,
            issuedAt,
            0,
            new Cancellation(CancellationReason.PUBLIC_SELF_CORRECTION, Instant.parse(cancelledAt)),
            "협력사 취소",
            new MealUsageQrContextId(qrContextId)
        );
    }
}
