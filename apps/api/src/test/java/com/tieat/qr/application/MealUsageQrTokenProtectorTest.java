package com.tieat.qr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.store.domain.StoreId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MealUsageQrTokenProtectorTest {

    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String NEXT_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final MealUsageQrContextId CONTEXT_ID = new MealUsageQrContextId(
        UUID.fromString("39ca4eb7-2810-4b82-8613-e5cf5d4df3a8")
    );

    @Test
    void rejectsRequiredEncryptionWithoutAConfiguredKey() {
        assertThatThrownBy(() -> new MealUsageQrTokenProtector("", "", 1, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("QR token encryption is required but no valid key or key ring is configured");
    }

    @Test
    void protectsAndRevealsWithRequiredValidKey() {
        MealUsageQrTokenProtector protector = new MealUsageQrTokenProtector("", KEY, 1, true);
        String rawToken = MealUsageQrToken.generate();

        MealUsageQrContext.ProtectedToken protectedToken = protector.protect(rawToken, CONTEXT_ID, STORE_ID);

        assertThat(protector.reveal(context(protectedToken))).isEqualTo(rawToken);
    }

    @Test
    void encryptsWithRandomNonceAndRevealsOnlyWithTheSameContextAndStore() {
        MealUsageQrTokenProtector protector = new MealUsageQrTokenProtector(KEY, 1);
        String rawToken = MealUsageQrToken.generate();

        MealUsageQrContext.ProtectedToken first = protector.protect(rawToken, CONTEXT_ID, STORE_ID);
        MealUsageQrContext.ProtectedToken second = protector.protect(rawToken, CONTEXT_ID, STORE_ID);

        assertThat(protector.reveal(context(first))).isEqualTo(rawToken);
        assertThat(Arrays.equals(first.nonce(), second.nonce())).isFalse();
        assertThat(Arrays.equals(first.ciphertext(), second.ciphertext())).isFalse();
        assertThat(new String(first.ciphertext(), StandardCharsets.UTF_8)).doesNotContain(rawToken);
        assertThatThrownBy(() -> protector.reveal(context(first, new StoreId(UUID.randomUUID()))))
            .isInstanceOf(QrTokenProtectionException.class);
    }

    @Test
    void rejectsTamperingContextMismatchKeyMismatchAndMissingKey() {
        MealUsageQrTokenProtector protector = new MealUsageQrTokenProtector(KEY, 1);
        String rawToken = MealUsageQrToken.generate();
        MealUsageQrContext.ProtectedToken protectedToken = protector.protect(rawToken, CONTEXT_ID, STORE_ID);

        byte[] tamperedCiphertext = protectedToken.ciphertext();
        tamperedCiphertext[0] ^= 1;
        MealUsageQrContext tampered = context(new MealUsageQrContext.ProtectedToken(
            tamperedCiphertext, protectedToken.nonce(), protectedToken.keyVersion()
        ));

        assertThatThrownBy(() -> protector.reveal(tampered)).isInstanceOf(QrTokenProtectionException.class);
        assertThatThrownBy(() -> protector.reveal(context(protectedToken, new StoreId(UUID.randomUUID()))))
            .isInstanceOf(QrTokenProtectionException.class);
        assertThatThrownBy(() -> new MealUsageQrTokenProtector(KEY, 2).reveal(context(protectedToken)))
            .isInstanceOf(QrTokenProtectionException.class);
        assertThatThrownBy(() -> new MealUsageQrTokenProtector("", 1).reveal(context(protectedToken)))
            .isInstanceOf(QrTokenProtectionException.class);
    }

    @Test
    void refusesToIssueWhenEncryptionKeyIsNotConfigured() {
        MealUsageQrTokenProtector protector = new MealUsageQrTokenProtector("", 1);

        assertThatThrownBy(() -> protector.protect(MealUsageQrToken.generate(), CONTEXT_ID, STORE_ID))
            .isInstanceOf(QrTokenProtectionException.class);
    }

    @Test
    void revealsWithTheStoredOlderKeyAndProtectsWithTheActiveRingVersion() {
        String rawToken = MealUsageQrToken.generate();
        MealUsageQrTokenProtector oldKeyProtector = new MealUsageQrTokenProtector(KEY, 1);
        MealUsageQrContext.ProtectedToken oldProtected = oldKeyProtector.protect(rawToken, CONTEXT_ID, STORE_ID);
        MealUsageQrTokenProtector ringProtector = new MealUsageQrTokenProtector(
            "1:" + KEY + ",2:" + NEXT_KEY,
            "",
            2
        );

        assertThat(ringProtector.reveal(context(oldProtected))).isEqualTo(rawToken);
        assertThat(ringProtector.protect(rawToken, CONTEXT_ID, STORE_ID).keyVersion()).isEqualTo(2);
    }

    @Test
    void failsClosedForUnknownStoredVersionAndInvalidRings() {
        String rawToken = MealUsageQrToken.generate();
        MealUsageQrTokenProtector ringProtector = new MealUsageQrTokenProtector(
            "1:" + KEY + ",2:" + NEXT_KEY,
            "",
            2
        );
        MealUsageQrContext.ProtectedToken protectedToken = ringProtector.protect(rawToken, CONTEXT_ID, STORE_ID);
        MealUsageQrContext unknownVersion = context(new MealUsageQrContext.ProtectedToken(
            protectedToken.ciphertext(), protectedToken.nonce(), 3
        ));

        assertThatThrownBy(() -> ringProtector.reveal(unknownVersion))
            .isInstanceOf(QrTokenProtectionException.class);
        for (String invalidRing : new String[] {
            "not-a-version",
            "1:" + KEY + ",1:" + NEXT_KEY,
            "0:" + KEY,
            "1:AAAA"
        }) {
            assertThatThrownBy(() -> new MealUsageQrTokenProtector(invalidRing, "", 1))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    private MealUsageQrContext context(MealUsageQrContext.ProtectedToken protectedToken) {
        return context(protectedToken, STORE_ID);
    }

    private MealUsageQrContext context(MealUsageQrContext.ProtectedToken protectedToken, StoreId storeId) {
        Instant issuedAt = Instant.parse("2026-08-10T00:00:00Z");
        return new MealUsageQrContext(
            CONTEXT_ID,
            storeId,
            "강남점",
            MealUsageQrToken.sha256Hash("placeholder"),
            issuedAt,
            issuedAt.plus(MealUsageQrContext.DEFAULT_LIFETIME),
            null,
            protectedToken
        );
    }
}
