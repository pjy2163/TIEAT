package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorePartnerPaymentTermHttpIntegrationTest extends StorePartnerContextHttpIntegrationSupport {

    @Test
    void paymentTermChangeUsesExpectedTypeAndAppendOnlyAuditWithoutChangingRegistrationSnapshot() throws Exception {
        ReadyStore store = readyStore("payment-term-store", "결제 전환 가게", "결제 협력사");
        Partner partner = createPartner(store, "등록 결제 협력사", "POSTPAID", 0, false, UUID.randomUUID());
        var updated = json(mockMvc.perform(paymentTermRequest(
            store.session, store.csrfToken, partner.id(), "POSTPAID", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 1_500L
        )).andExpect(status().isOk()).andReturn()).body();
        assertThat(updated.get("paymentType").asText()).isEqualTo("PREPAID_WITH_RECEIVABLE_OVERFLOW");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_contract_payment_term_audits where meal_contract_id = ?", Long.class, partner.id()
        )).isEqualTo(1L);
        mockMvc.perform(paymentTermRequest(store.session, store.csrfToken, partner.id(),
            "PREPAID_WITH_RECEIVABLE_OVERFLOW", "POSTPAID", null)).andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
            "select actor_login_id from meal_contract_payment_term_audits where meal_contract_id = ?", String.class, partner.id()
        )).isEqualTo(store.loginId);
        assertThat(jdbcTemplate.queryForObject(
            "select previous_payment_type || ':' || new_payment_type || ':' || prepaid_balance_before || ':' || prepaid_balance_after "
                + "from meal_contract_payment_term_audits where meal_contract_id = ?", String.class, partner.id()
        )).isEqualTo("POSTPAID:PREPAID_WITH_RECEIVABLE_OVERFLOW:0:1500");
        assertThat(jdbcTemplate.queryForObject(
            "select payment_type || ':' || initial_prepaid_balance_minor from store_partner_registrations where meal_contract_id = ?",
            String.class, partner.id()
        )).isEqualTo("POSTPAID:0");
        var stale = json(mockMvc.perform(paymentTermRequest(store.session, store.csrfToken, partner.id(),
            "POSTPAID", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 2_000L)).andExpect(status().isConflict()).andReturn()).body();
        assertThat(stale.get("errorCode").asText()).isEqualTo("STORE_PARTNER_PAYMENT_TERM_STALE");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_contract_payment_term_audits where meal_contract_id = ?", Long.class, partner.id()
        )).isEqualTo(1L);
    }

    @Test
    void paymentTermChangeBlocksPendingAndOutstandingReceivableContracts() throws Exception {
        ReadyStore store = readyStore("payment-term-blocked", "결제 차단 가게", "기본 협력사");
        Partner pending = createPartner(store, "대기 협력사", "POSTPAID", 0, false, UUID.randomUUID());
        mealUsageRepository.save(com.tieat.ledger.domain.MealUsage.pending(
            new com.tieat.ledger.domain.MealUsageId(UUID.randomUUID()), store.storeId,
            new com.tieat.partnership.domain.MealContractId(pending.id()), com.tieat.ledger.domain.EntrySource.STORE_TABLET,
            1_000, java.time.Instant.parse("2026-08-15T01:00:00Z")
        ));
        mockMvc.perform(paymentTermRequest(store.session, store.csrfToken, pending.id(),
            "POSTPAID", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 1_000L)).andExpect(status().isConflict());
        Partner receivable = createPartner(store, "미정산 협력사", "POSTPAID", 0, false, UUID.randomUUID());
        saveConfirmed(store.storeId, receivable.id(), receivable.name(), 1_100, "00000000-0000-0000-0000-000000000013");
        mockMvc.perform(paymentTermRequest(store.session, store.csrfToken, receivable.id(),
            "POSTPAID", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 1_000L)).andExpect(status().isConflict());
    }

    @Test
    void paymentTermChangeKeepsStoreScopeAndAllowsPrepaidToPostpaidAfterBalanceReachesZero() throws Exception {
        ReadyStore store = readyStore("payment-scope-store", "결제 범위 가게", "기본 협력사");
        ReadyStore otherStore = readyStore("payment-scope-other", "다른 결제 범위 가게", "다른 협력사");
        Partner prepaid = createPartner(store, "잔액 소진 협력사", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 1_000, false, UUID.randomUUID());
        Partner foreign = firstPartner(otherStore);
        var pendingUsage = json(mockMvc.perform(mealUsageCreationRequest(store.session, store.csrfToken, prepaid.id(), 1_000))
            .andExpect(status().isCreated()).andReturn()).body();
        UUID usageId = UUID.fromString(pendingUsage.get("mealUsageId").asText());
        mockMvc.perform(confirmMealUsageRequest(store.session, store.csrfToken, usageId)).andExpect(status().isCreated());
        mockMvc.perform(paymentTermRequest(store.session, store.csrfToken, prepaid.id(),
            "PREPAID_WITH_RECEIVABLE_OVERFLOW", "POSTPAID", null)).andExpect(status().isOk());
        mockMvc.perform(paymentTermRequest(store.session, store.csrfToken, foreign.id(),
            "POSTPAID", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 1_000L)).andExpect(status().isNotFound());
        mockMvc.perform(paymentTermRequest(store.session, store.csrfToken, UUID.randomUUID(),
            "POSTPAID", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 1_000L)).andExpect(status().isNotFound());
    }
}
