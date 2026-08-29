package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class StorePartnerRegistrationHttpIntegrationTest extends StorePartnerContextHttpIntegrationSupport {

    @Test
    void provisionsQrWhenAddingFirstQrSelectablePartnerToAStoreWithoutCurrentQr() throws Exception {
        var session = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "qr-on-add-store", "correct-password", "QR 추가 가게"
        );
        String onboardingCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
                session,
                onboardingCsrf,
                partnerBody("QR 비활성 협력사", "POSTPAID", "0", "false")
            ))
            .andExpect(status().isCreated());

        UUID storeId = jdbcTemplate.queryForObject(
            "select store_id from store_accounts where login_id = ?", UUID.class, "qr-on-add-store"
        );
        mockMvc.perform(storePartnerRequest(
                session,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session),
                UUID.randomUUID(),
                partnerBody("QR 활성 협력사", "POSTPAID", "0", "true")
            ))
            .andExpect(status().isCreated());

        JsonNode qr = json(mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(qr.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(qr.get("publicPath").asText()).startsWith("/qr/");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usage_qr_contexts where store_id = ?", Long.class, storeId
        )).isEqualTo(1);
    }

    @Test
    void generalRegistrationCannotBypassFirstPartnerOnboarding() throws Exception {
        var session = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "partner-required-store", "correct-password", "파트너 대기 가게"
        );
        mockMvc.perform(storePartnerRequest(
                session,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session),
                UUID.randomUUID(), partnerBody("일반 협력사", "POSTPAID", "0", "true")
            ))
            .andExpect(status().isBadRequest());
        assertThat(count("partner_organizations")).isZero();
        assertThat(count("meal_contracts")).isZero();
        assertThat(count("store_partner_registrations")).isZero();
    }

    @Test
    void listsOnlyAuthenticatedStorePartnersInNameAndUuidOrder() throws Exception {
        ReadyStore store = readyStore("directory-store", "디렉터리 가게", "Zeta");
        ReadyStore otherStore = readyStore("directory-other-store", "다른 가게", "Foreign");
        Partner foreign = createPartner(otherStore, "Foreign Two", "POSTPAID", 0, false, UUID.randomUUID());
        createPartner(store, "Alpha", "POSTPAID", 0, true, UUID.randomUUID());
        createPartner(store, "Alpha", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 12_000, false, UUID.randomUUID(), "INDIVIDUAL");

        JsonNode items = json(mockMvc.perform(get("/api/v1/store-partners").cookie(store.session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        List<Partner> actual = new ArrayList<>();
        items.forEach(item -> actual.add(new Partner(
            UUID.fromString(item.get("mealContractId").asText()), item.get("partnerDisplayName").asText(),
            item.get("partnerKind").asText(), item.get("paymentType").asText(), item.get("qrSelectable").asBoolean()
        )));
        assertThat(actual).extracting(Partner::name).containsExactly("Alpha", "Alpha", "Zeta");
        assertThat(actual).extracting(Partner::partnerKind)
            .containsExactlyInAnyOrder("INDIVIDUAL", "ORGANIZATION", "ORGANIZATION");
        assertThat(actual).isSortedAccordingTo(Comparator.comparing(Partner::name).thenComparing(partner -> partner.id().toString()));
        assertThat(actual).extracting(Partner::id).doesNotContain(foreign.id());

        JsonNode profile = json(mockMvc.perform(get("/api/v1/store-profile").cookie(store.session.cookie()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andReturn()).body();
        assertThat(profile.get("loginId").asText()).isEqualTo(store.loginId);
        assertThat(profile.get("storeDisplayName").asText()).isEqualTo("디렉터리 가게");
    }

    @Test
    void filtersMonthlyRowsAndTotalOnlyAfterStoreOwnershipCheck() throws Exception {
        ReadyStore store = readyStore("monthly-store", "월별 가게", "Partner A");
        Partner second = createPartner(store, "Partner B", "POSTPAID", 0, true, UUID.randomUUID());
        Partner first = firstPartner(store);
        ReadyStore otherStore = readyStore("monthly-other-store", "다른 월별 가게", "Other");
        Partner foreign = firstPartner(otherStore);
        saveConfirmed(store.storeId, first.id(), "Partner A", 1_000, "00000000-0000-0000-0000-000000000001");
        saveConfirmed(store.storeId, first.id(), "Partner A", 1_500, "00000000-0000-0000-0000-000000000002");
        saveConfirmed(store.storeId, second.id(), "Partner B", 2_000, "00000000-0000-0000-0000-000000000003");

        JsonNode unfiltered = json(mockMvc.perform(monthlyRequest(store.session, "2026-08", null))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(unfiltered.get("items").size()).isEqualTo(3);
        assertThat(unfiltered.get("totalAmountMinor").asLong()).isEqualTo(4_500);
        JsonNode filtered = json(mockMvc.perform(monthlyRequest(store.session, "2026-08", first.id()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(filtered.get("items").size()).isEqualTo(2);
        assertThat(filtered.get("totalAmountMinor").asLong()).isEqualTo(2_500);
        for (JsonNode item : filtered.get("items")) {
            assertThat(item.get("partnerDisplayName").asText()).isEqualTo("Partner A");
        }
        MvcResult foreignResult = mockMvc.perform(monthlyRequest(store.session, "2026-08", foreign.id())).andReturn();
        MvcResult randomResult = mockMvc.perform(monthlyRequest(store.session, "2026-08", UUID.randomUUID())).andReturn();
        assertThat(foreignResult.getResponse().getStatus()).isEqualTo(404);
        assertThat(randomResult.getResponse().getStatus()).isEqualTo(404);
        JsonNode foreignBody = json(foreignResult).body();
        JsonNode randomBody = json(randomResult).body();
        assertThat(foreignBody.get("errorCode").asText()).isEqualTo(randomBody.get("errorCode").asText());
        assertThat(foreignBody.get("title").asText()).isEqualTo(randomBody.get("title").asText());
        assertThat(foreignBody.get("detail").asText()).isEqualTo(randomBody.get("detail").asText());
    }

    @Test
    void rejectsInvalidPaymentInputsWithoutCreatingAnyNewRows() throws Exception {
        ReadyStore store = readyStore("validation-store", "검증 가게", "Existing");
        long partnersBefore = count("partner_organizations");
        long contractsBefore = countForStore("meal_contracts", store.storeId);
        long registrationsBefore = count("store_partner_registrations");
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, store.session);
        String[] bodies = {
            partnerBody("후불 잔액", "POSTPAID", "1", "true"),
            partnerBody("음수 선불", "PREPAID_WITH_RECEIVABLE_OVERFLOW", "-1", "true"),
            "{\"partnerName\":\"누락 잔액\",\"paymentType\":\"POSTPAID\",\"qrSelectable\":true}",
            partnerBody("소수 잔액", "PREPAID_WITH_RECEIVABLE_OVERFLOW", "1.5", "true"),
            partnerBody("오버플로", "PREPAID_WITH_RECEIVABLE_OVERFLOW", "9223372036854775808", "true"),
            "{\"partnerName\":\"잘못된 유형\",\"partnerKind\":\"PERSON\",\"paymentType\":\"POSTPAID\",\"initialPrepaidBalanceMinor\":0,\"qrSelectable\":true}"
        };
        for (int index = 0; index < bodies.length; index++) {
            mockMvc.perform(storePartnerRequest(store.session, csrfToken, UUID.nameUUIDFromBytes(
                ("invalid-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)), bodies[index]
            )).andExpect(status().isBadRequest());
        }
        assertThat(count("partner_organizations")).isEqualTo(partnersBefore);
        assertThat(countForStore("meal_contracts", store.storeId)).isEqualTo(contractsBefore);
        assertThat(count("store_partner_registrations")).isEqualTo(registrationsBefore);
    }

    @Test
    void replaysCanonicalPayloadAndRejectsSequentialConflict() throws Exception {
        ReadyStore store = readyStore("replay-store", "재전송 가게", "Existing");
        UUID key = UUID.randomUUID();
        Partner first = createPartner(store, "  Replay Partner  ", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 25_000, true, key);
        Partner replay = createPartner(store, "Replay Partner", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 25_000, true, key);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.name()).isEqualTo(first.name());
        assertThat(replay.partnerKind()).isEqualTo(first.partnerKind());
        assertThat(replay.paymentType()).isEqualTo(first.paymentType());
        assertThat(replay.qrSelectable()).isEqualTo(first.qrSelectable());
        mockMvc.perform(storePartnerRequest(store.session, store.csrfToken, key,
            partnerBody("Different Payload", "PREPAID_WITH_RECEIVABLE_OVERFLOW", "25000", "true")))
            .andExpect(status().isConflict());
        assertThat(countForStore("meal_contracts", store.storeId)).isEqualTo(2);
        assertThat(count("store_partner_registrations")).isEqualTo(1);
    }

    @Test
    void storesOptionalRepresentativeContactsInTheStoreScopedDirectoryAndComparesThemOnReplay() throws Exception {
        ReadyStore store = readyStore("contact-store", "연락처 가게", "기본 협력사");
        UUID key = UUID.randomUUID();
        String body = partnerBodyWithContacts("연락처 협력사", "POSTPAID", "0", "true", "010-1234-5678", "owner@example.com");
        JsonNode created = json(mockMvc.perform(storePartnerRequest(store.session, store.csrfToken, key, body))
            .andExpect(status().isCreated()).andReturn()).body();
        UUID contractId = UUID.fromString(created.get("mealContractId").asText());
        assertThat(created.get("representativePhone").asText()).isEqualTo("010-1234-5678");
        assertThat(created.get("representativeEmail").asText()).isEqualTo("owner@example.com");
        assertThat(jdbcTemplate.queryForObject(
            "select representative_phone from partner_organizations where id = "
                + "(select partner_organization_id from meal_contracts where id = ?)", String.class, contractId
        )).isEqualTo("010-1234-5678");
        JsonNode replay = json(mockMvc.perform(storePartnerRequest(store.session, store.csrfToken, key, body))
            .andExpect(status().isCreated()).andReturn()).body();
        assertThat(replay.get("mealContractId").asText()).isEqualTo(contractId.toString());
        assertThat(replay.get("representativeEmail").asText()).isEqualTo("owner@example.com");
        mockMvc.perform(storePartnerRequest(store.session, store.csrfToken, key,
            partnerBodyWithContacts("연락처 협력사", "POSTPAID", "0", "true", "010-9999-9999", "owner@example.com")))
            .andExpect(status().isConflict());
        JsonNode blank = json(mockMvc.perform(storePartnerRequest(store.session, store.csrfToken, UUID.randomUUID(),
            partnerBodyWithContacts("빈 연락처 협력사", "POSTPAID", "0", "true", "   ", "  ")))
            .andExpect(status().isCreated()).andReturn()).body();
        assertThat(blank.get("representativePhone").isNull()).isTrue();
        assertThat(blank.get("representativeEmail").isNull()).isTrue();
    }

    @Test
    void rejectsMalformedRepresentativeContactsWithoutCreatingRows() throws Exception {
        ReadyStore store = readyStore("invalid-contact-store", "연락처 검증 가게", "기본 협력사");
        long partnersBefore = count("partner_organizations");
        String[] invalidBodies = {
            partnerBodyWithContacts("잘못된 전화", "POSTPAID", "0", "true", "phone?", "owner@example.com"),
            partnerBodyWithContacts("잘못된 이메일", "POSTPAID", "0", "true", "010-1234-5678", "owner-at-example.com"),
            partnerBodyWithContacts("긴 전화", "POSTPAID", "0", "true", "1".repeat(31), "owner@example.com"),
            partnerBodyWithContacts("긴 이메일", "POSTPAID", "0", "true", "010-1234-5678", "a".repeat(250) + "@example.com")
        };
        for (int index = 0; index < invalidBodies.length; index++) {
            mockMvc.perform(storePartnerRequest(store.session, store.csrfToken,
                UUID.nameUUIDFromBytes(("invalid-contact-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)), invalidBodies[index]
            )).andExpect(status().isBadRequest());
        }
        assertThat(count("partner_organizations")).isEqualTo(partnersBefore);
    }

    @Test
    void concurrentSameKeyRequestsCreateOneContractAndReplayTheSameResponse() throws Exception {
        ReadyStore store = readyStore("concurrent-store", "동시 가게", "Existing");
        List<MvcResult> results = runConcurrentCreates(store, UUID.randomUUID(),
            partnerBody("Concurrent Partner", "POSTPAID", "0", "false"),
            partnerBody("Concurrent Partner", "POSTPAID", "0", "false"));
        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactly(201, 201);
        List<String> contractIds = new ArrayList<>();
        for (MvcResult result : results) contractIds.add(json(result).body().get("mealContractId").asText());
        assertThat(contractIds.stream().distinct()).hasSize(1);
        assertThat(contractIds).hasSize(2);
        assertThat(countForStore("meal_contracts", store.storeId)).isEqualTo(2);
        assertThat(count("partner_organizations")).isEqualTo(2);
        assertThat(count("store_partner_registrations")).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyDifferentPayloadReturnsOneConflictWithoutOrphans() throws Exception {
        ReadyStore store = readyStore("concurrent-conflict-store", "동시 충돌 가게", "Existing");
        List<MvcResult> results = runConcurrentCreates(store, UUID.randomUUID(),
            partnerBody("Concurrent A", "POSTPAID", "0", "false"),
            partnerBody("Concurrent B", "POSTPAID", "0", "false"));
        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
        assertThat(countForStore("meal_contracts", store.storeId)).isEqualTo(2);
        assertThat(count("partner_organizations")).isEqualTo(2);
        assertThat(count("store_partner_registrations")).isEqualTo(1);
    }
}
