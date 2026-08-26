package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class StorePartnerArchivePinHttpIntegrationTest extends StorePartnerContextHttpIntegrationSupport {

    @Test
    void archiveRequiresConfiguredPinAndLegacyDeleteCannotBypassIt() throws Exception {
        ReadyStore store = readyStore("pin-store", "PIN 가게", "PIN 협력사");
        Partner partner = firstPartner(store);
        JsonNode initialStatus = json(mockMvc.perform(get("/api/v1/store-archive-pin").cookie(store.session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(initialStatus.get("configured").asBoolean()).isFalse();
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, partner.id(), "1234"))
            .andExpect(status().isConflict());
        mockMvc.perform(archivePinSettingsRequest(store, null, null, "1234", "1234"))
            .andExpect(status().isForbidden());
        assertThat(count("store_archive_pin_security")).isZero();
        mockMvc.perform(archivePinSettingsRequest(store, null, "wrong-password", "1234", "1234"))
            .andExpect(status().isForbidden());
        assertThat(count("store_archive_pin_security")).isZero();
        configureArchivePin(store);
        String firstPinHash = jdbcTemplate.queryForObject(
            "select pin_hash from store_archive_pin_security where store_id = ?", String.class, store.storeId.value()
        );
        assertThat(firstPinHash).startsWith("$2").isNotEqualTo("1234").doesNotContain("correct-password");
        mockMvc.perform(archivePinSettingsRequest(store, null, "correct-password", "9999", "9999"))
            .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
            "select pin_hash from store_archive_pin_security where store_id = ?", String.class, store.storeId.value()
        )).isEqualTo(firstPinHash);
        mockMvc.perform(archivePinSettingsRequest(store, "1234", null, "5678", "5678"))
            .andExpect(status().isOk());
        JsonNode configuredStatus = json(mockMvc.perform(get("/api/v1/store-archive-pin").cookie(store.session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(configuredStatus.get("configured").asBoolean()).isTrue();
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, partner.id(), "0000"))
            .andExpect(status().isForbidden());
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, partner.id(), "1234"))
            .andExpect(status().isForbidden());
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, partner.id(), "5678"))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/store-partners/{mealContractId}", partner.id())
            .cookie(store.session.cookie()).header("X-CSRF-TOKEN", store.csrfToken)).andExpect(status().isForbidden());
        Partner lockTarget = createPartner(store, "PIN 잠금 협력사", "POSTPAID", 0, false, UUID.randomUUID());
        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, lockTarget.id(), "0000"))
                .andExpect(status().isForbidden());
        }
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, lockTarget.id(), "0000"))
            .andExpect(status().isTooManyRequests());
        jdbcTemplate.update("update store_archive_pin_security set locked_until = now() - interval '1 second' where store_id = ?", store.storeId.value());
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, lockTarget.id(), "5678"))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
            "select failed_attempts from store_archive_pin_security where store_id = ?", Integer.class, store.storeId.value()
        )).isZero();
    }

    @Test
    void serializesConcurrentWrongArchivePinAttemptsForTheSameStore() throws Exception {
        ReadyStore store = readyStore("pin-concurrent-store", "동시 PIN 가게", "첫 번째 잠금 대상");
        Partner first = firstPartner(store);
        Partner second = createPartner(store, "두 번째 잠금 대상", "POSTPAID", 0, false, UUID.randomUUID());
        configureArchivePin(store);
        SessionHandle firstSession = StoreOnboardingHttpIntegrationSupport.authenticatedSession(mockMvc, objectMapper, store.loginId, "correct-password");
        SessionHandle secondSession = StoreOnboardingHttpIntegrationSupport.authenticatedSession(mockMvc, objectMapper, store.loginId, "correct-password");
        String firstCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, firstSession);
        String secondCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, secondSession);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> firstResult = executor.submit(() -> {
                ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(archiveWithPinRequest(firstSession, firstCsrf, first.id(), "0000")).andReturn();
            });
            Future<MvcResult> secondResult = executor.submit(() -> {
                ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(archiveWithPinRequest(secondSession, secondCsrf, second.id(), "0000")).andReturn();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS)))
                .extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(403, 403);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("select failed_attempts from store_archive_pin_security where store_id = ?", Integer.class, store.storeId.value())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_contracts where store_id = ? and archived_at is not null", Long.class, store.storeId.value())).isZero();
    }

    @Test
    void acceptsAWhitespaceOnlyExistingAccountPasswordForInitialArchivePinSetup() throws Exception {
        String whitespacePassword = "          ";
        ReadyStore store = readyStore("pin-whitespace-store", "공백 PIN 가게", "공백 비밀번호 협력사", whitespacePassword);
        mockMvc.perform(archivePinSettingsRequest(store, null, whitespacePassword, "1234", "1234"))
            .andExpect(status().isOk());
        String pinHash = jdbcTemplate.queryForObject("select pin_hash from store_archive_pin_security where store_id = ?", String.class, store.storeId.value());
        assertThat(pinHash).startsWith("$2").doesNotContain(whitespacePassword);
    }

    @Test
    void archivesPartnerIdempotentlyExcludesItFromActiveDirectoryAndKeepsConfirmedLedger() throws Exception {
        ReadyStore store = readyStore("archive-store", "보관 가게", "보관 협력사");
        Partner partner = firstPartner(store);
        configureArchivePin(store);
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, partner.id(), "1234"))
            .andExpect(status().isNoContent());
        JsonNode activeDirectory = json(mockMvc.perform(get("/api/v1/store-partners").cookie(store.session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(activeDirectory.size()).isZero();
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, partner.id(), "1234"))
            .andExpect(status().isNoContent());
        saveConfirmed(store.storeId, partner.id(), partner.name(), 1_500, "00000000-0000-0000-0000-000000000011");
        JsonNode historicalLedger = json(mockMvc.perform(monthlyRequest(store.session, "2026-08", null))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(historicalLedger.get("items").size()).isEqualTo(1);
        assertThat(historicalLedger.get("items").get(0).get("partnerDisplayName").asText()).isEqualTo(partner.name());
        mockMvc.perform(mealUsageCreationRequest(store.session, store.csrfToken, partner.id(), 1_200))
            .andExpect(status().isNotFound());
    }

    @Test
    void returnsTheSameNotFoundForUnknownAndCrossStoreArchiveTargets() throws Exception {
        ReadyStore store = readyStore("archive-owner", "보관 주인", "내 협력사");
        ReadyStore otherStore = readyStore("archive-foreign", "다른 주인", "남의 협력사");
        Partner foreign = firstPartner(otherStore);
        configureArchivePin(store);
        MvcResult unknown = mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, UUID.randomUUID(), "1234")).andReturn();
        MvcResult crossStore = mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, foreign.id(), "1234")).andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(crossStore.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(unknown).body().get("errorCode").asText()).isEqualTo(json(crossStore).body().get("errorCode").asText());
    }

    @Test
    void blocksArchiveForPendingUsageOutstandingReceivableAndRemainingPrepaidBalance() throws Exception {
        ReadyStore store = readyStore("archive-blocked", "차단 가게", "기본 협력사");
        configureArchivePin(store);
        Partner pending = createPartner(store, "대기 협력사", "POSTPAID", 0, false, UUID.randomUUID());
        mealUsageRepository.save(MealUsage.pending(new MealUsageId(UUID.randomUUID()), store.storeId,
            new MealContractId(pending.id()), EntrySource.STORE_TABLET, 1_000, Instant.parse("2026-08-15T01:00:00Z")));
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, pending.id(), "1234"))
            .andExpect(status().isConflict());
        Partner receivable = createPartner(store, "미정산 협력사", "POSTPAID", 0, false, UUID.randomUUID());
        saveConfirmed(store.storeId, receivable.id(), receivable.name(), 1_100, "00000000-0000-0000-0000-000000000012");
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, receivable.id(), "1234"))
            .andExpect(status().isConflict());
        Partner prepaid = createPartner(store, "선불 협력사", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 500, false, UUID.randomUUID());
        mockMvc.perform(archiveWithPinRequest(store.session, store.csrfToken, prepaid.id(), "1234"))
            .andExpect(status().isConflict());
    }
}
