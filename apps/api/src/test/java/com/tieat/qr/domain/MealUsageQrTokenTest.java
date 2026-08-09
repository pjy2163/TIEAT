package com.tieat.qr.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MealUsageQrTokenTest {

    @Test
    void generatesAUrlSafe256BitTokenAndUsesOnlyItsSha256HashInTheQrContext() {
        String token = MealUsageQrToken.generate();
        String tokenHash = MealUsageQrToken.sha256Hash(token);
        Instant issuedAt = Instant.parse("2026-08-09T00:00:00Z");

        MealUsageQrContext context = MealUsageQrContext.issue(
            new MealUsageQrContextId(UUID.randomUUID()),
            new StoreId(UUID.randomUUID()),
            "강남점",
            tokenHash,
            issuedAt
        );

        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(tokenHash).matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(context.tokenHash()).isEqualTo(tokenHash);
        assertThat(context.expiresAt()).isEqualTo(issuedAt.plus(MealUsageQrContext.DEFAULT_LIFETIME));
        assertThat(context.isActiveAt(issuedAt)).isTrue();
        assertThat(context.isActiveAt(context.expiresAt())).isFalse();
    }
}
