package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.ledger.domain.Cancellation;
import com.tieat.ledger.domain.CancellationReason;
import com.tieat.ledger.domain.Confirmation;
import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.PrepaidAllocation;
import com.tieat.ledger.domain.Rejection;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.settlement.application.PosSettlementConflictException;
import com.tieat.settlement.application.RecordPosSettlementCommand;
import com.tieat.settlement.application.RecordPosSettlementUseCase;
import com.tieat.store.domain.StoreId;
import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PosSettlementHttpIntegrationTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final StoreId OTHER_STORE_ID = new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1"));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MealUsageRepository mealUsageRepository;

    @Autowired
    private MealContractRepository mealContractRepository;

    @Autowired
    private RecordPosSettlementUseCase recordPosSettlementUseCase;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("delete from pos_settlement_receipts");
        jdbcTemplate.update("delete from pos_settlement_allocations");
        jdbcTemplate.update("delete from pos_settlements");
        jdbcTemplate.update("delete from public_meal_usage_idempotency_keys");
        jdbcTemplate.update("delete from meal_usages");
        jdbcTemplate.update("delete from meal_usage_qr_operation_audits");
        jdbcTemplate.update("delete from meal_usage_qr_contexts");
        jdbcTemplate.update("delete from meal_contracts");
        jdbcTemplate.update("delete from partner_organizations");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void listsOnlyStrictlyOutstandingReceivablesThenRecordsExactPosAttestationWithoutChangingPrepaidBalance() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        PartnerOrganizationId partnerOrganizationId = new PartnerOrganizationId(UUID.randomUUID());
        jdbcTemplate.update(
            "insert into partner_organizations (id, display_name) values (?, ?)",
            partnerOrganizationId.value(),
            "협력사 계약"
        );
        MealContract contract = new MealContract(
            new MealContractId(UUID.randomUUID()),
            STORE_ID,
            MealContractPaymentType.POSTPAID,
            0,
            partnerOrganizationId,
            false
        );
        MealContract prepaidCoveredContract = prepaidContract(STORE_ID, 0);
        MealContract otherStoreContract = postpaidContract(OTHER_STORE_ID);
        mealContractRepository.save(contract);
        mealContractRepository.save(prepaidCoveredContract);
        mealContractRepository.save(otherStoreContract);
        MealUsage first = confirmedUsage(contract.id(), STORE_ID, 1_000, 0, 1_000, 0);
        MealUsage second = confirmedUsage(contract.id(), STORE_ID, 2_000, 0, 2_000, 0, "협력사 B");
        MealUsage partiallyAllocated = confirmedUsage(contract.id(), STORE_ID, 1_000, 0, 1_000, 0);
        MealUsage covered = confirmedUsage(prepaidCoveredContract.id(), STORE_ID, 1_000, 1_000, 0, 0);
        MealUsage inconsistentContractStore = confirmedUsage(otherStoreContract.id(), STORE_ID, 1_000, 0, 1_000, 0);
        MealUsage otherStore = confirmedUsage(otherStoreContract.id(), OTHER_STORE_ID, 1_000, 0, 1_000, 0);
        MealUsage pending = MealUsage.pending(
            new MealUsageId(UUID.randomUUID()), STORE_ID, contract.id(), EntrySource.STORE_TABLET, 700, Instant.parse("2026-08-12T02:00:00Z")
        );
        MealUsage rejected = MealUsage.restoreRejected(
            new MealUsageId(UUID.randomUUID()), STORE_ID, contract.id(), EntrySource.STORE_TABLET, 700,
            Instant.parse("2026-08-12T02:01:00Z"), 0,
            new Rejection("store-hk", Instant.parse("2026-08-12T02:02:00Z")), null, null
        );
        MealUsage cancelled = cancelledUsage(contract.id(), STORE_ID);
        mealUsageRepository.save(first);
        mealUsageRepository.save(second);
        mealUsageRepository.save(partiallyAllocated);
        mealUsageRepository.save(covered);
        mealUsageRepository.save(inconsistentContractStore);
        mealUsageRepository.save(otherStore);
        mealUsageRepository.save(pending);
        mealUsageRepository.save(rejected);
        mealUsageRepository.save(cancelled);
        insertSettlement(
            UUID.randomUUID(),
            STORE_ID,
            contract.id(),
            LocalDate.of(2026, 8, 10),
            "store-hk",
            Instant.parse("2026-08-12T00:30:00Z"),
            new SettlementAllocationFixture(partiallyAllocated.id().value(), 1_000)
        );
        SessionHandle session = authenticatedSession("store-hk", "correct-password");

        MvcResult receivables = mockMvc.perform(get("/api/v1/pos-settlements/receivables").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].mealContractId").value(contract.id().value().toString()))
            .andExpect(jsonPath("$.items[*].mealUsageId").value(org.hamcrest.Matchers.containsInAnyOrder(
                first.id().value().toString(), second.id().value().toString()
            )))
            .andExpect(jsonPath("$.items[*].partnerDisplayName").value(org.hamcrest.Matchers.containsInAnyOrder(
                "협력사 계약", "협력사 B"
            )))
            .andReturn();
        JsonNode receivableOverview = objectMapper.readTree(receivables.getResponse().getContentAsString());
        JsonNode receivableItems = receivableOverview.get("items");
        assertThat(fieldNames(receivableItems.get(0))).containsExactlyInAnyOrder(
            "mealUsageId", "mealContractId", "partnerDisplayName", "confirmedAt", "receivableCreatedMinor"
        );
        JsonNode contractSummary = partnerSummary(receivableOverview.get("partners"), contract.id());
        assertThat(fieldNames(contractSummary)).containsExactlyInAnyOrder(
            "mealContractId", "partnerOrganizationId", "partnerDisplayName", "previousPosBusinessDate",
            "periodConfirmedUsageTotalMinor", "periodPrepaidAppliedTotalMinor",
            "outstandingReceivableCount", "outstandingReceivableTotalMinor"
        );
        assertThat(contractSummary.get("partnerOrganizationId").asText())
            .isEqualTo(partnerOrganizationId.value().toString());
        JsonNode legacyContractSummary = partnerSummary(receivableOverview.get("partners"), prepaidCoveredContract.id());
        assertThat(legacyContractSummary.get("partnerOrganizationId").isNull()).isTrue();
        assertThat(contractSummary.get("previousPosBusinessDate").asText()).isEqualTo("2026-08-10");
        assertThat(contractSummary.get("outstandingReceivableCount").asLong()).isEqualTo(2);
        assertThat(contractSummary.get("outstandingReceivableTotalMinor").asLong()).isEqualTo(3_000);

        UUID idempotencyKey = UUID.randomUUID();
        MvcResult result = mockMvc.perform(recordRequest(
                session,
                csrfToken(session),
                idempotencyKey,
                settlementBody(contract.id(), List.of(second.id().value(), first.id().value()), 3_000)
            ))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.posBusinessDate").value("2026-08-11"))
            .andExpect(jsonPath("$.submittedTotalMinor").value(3_000))
            .andExpect(jsonPath("$.recordedAt").isNotEmpty())
            .andExpect(jsonPath("$.allocations.length()").value(2))
            .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(response)).containsExactlyInAnyOrder(
            "posSettlementId", "posBusinessDate", "submittedTotalMinor", "recordedAt", "allocations"
        );
        assertThat(fieldNames(response.get("allocations").get(0))).containsExactlyInAnyOrder(
            "partnerDisplayName", "confirmedAt", "receivableAmountMinor"
        );
        for (JsonNode allocation : response.get("allocations")) {
            String partnerDisplayName = allocation.get("partnerDisplayName").asText();
            assertThat(partnerDisplayName).isIn("협력사 계약", "협력사 B");
            assertThat(allocation.get("confirmedAt").asText()).isEqualTo("2026-08-12T01:01:00Z");
            assertThat(allocation.get("receivableAmountMinor").asLong())
                .isEqualTo("협력사 계약".equals(partnerDisplayName) ? 1_000L : 2_000L);
        }
        UUID settlementId = jdbcTemplate.queryForObject(
            "select id from pos_settlements where idempotency_key = ?",
            UUID.class,
            idempotencyKey
        );
        assertThat(jdbcTemplate.queryForObject("select count(*) from pos_settlements", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from pos_settlement_allocations", Long.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
            "select prepaid_balance from meal_contracts where id = ?", Long.class, contract.id().value()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select sum(receivable_amount_minor) from pos_settlement_allocations where pos_settlement_id = ?",
            Long.class,
            settlementId
        )).isEqualTo(3_000);

        mockMvc.perform(recordRequest(
                session,
                csrfToken(session),
                idempotencyKey,
                settlementBody(contract.id(), List.of(first.id().value(), second.id().value()), 3_000)
            ))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.posBusinessDate").value("2026-08-11"))
            .andExpect(jsonPath("$.submittedTotalMinor").value(3_000));
        assertThat(jdbcTemplate.queryForObject("select count(*) from pos_settlements", Long.class)).isEqualTo(2);
        mockMvc.perform(recordRequest(
                session,
                csrfToken(session),
                idempotencyKey,
                settlementBody(contract.id(), List.of(first.id().value(), second.id().value()), 3_001)
            ))
            .andExpect(problem(HttpStatus.CONFLICT.value(), "IDEMPOTENCY_KEY_REUSED"));

        MvcResult noCandidatesAfterAllocation = mockMvc.perform(get("/api/v1/pos-settlements/receivables").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andReturn();
        JsonNode allocatedOverview = objectMapper.readTree(noCandidatesAfterAllocation.getResponse().getContentAsString());
        JsonNode allocatedContractSummary = partnerSummary(allocatedOverview.get("partners"), contract.id());
        assertThat(allocatedContractSummary.get("outstandingReceivableCount").asLong()).isZero();
        assertThat(allocatedContractSummary.get("outstandingReceivableTotalMinor").asLong()).isZero();
    }

    @Test
    void returnsThePersistedPosSettlementSnapshotAndCompleteAllocationsInHistory() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealContract contract = postpaidContract(STORE_ID);
        mealContractRepository.save(contract);
        MealUsage first = confirmedUsage(contract.id(), STORE_ID, 1_000, 0, 1_000, 0, "협력사 A");
        MealUsage second = confirmedUsage(contract.id(), STORE_ID, 2_000, 0, 2_000, 0, "협력사 B");
        mealUsageRepository.save(first);
        mealUsageRepository.save(second);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");

        MvcResult recorded = mockMvc.perform(recordRequest(
                session,
                csrfToken(session),
                UUID.randomUUID(),
                settlementBody(contract.id(), List.of(first.id().value(), second.id().value()), 3_000)
            ))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode recordedBody = objectMapper.readTree(recorded.getResponse().getContentAsString());

        MvcResult history = mockMvc.perform(get("/api/v1/pos-settlements?page=0&size=20").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].posBusinessDate").value("2026-08-11"))
            .andExpect(jsonPath("$.items[0].submittedTotalMinor").value(3_000))
            .andExpect(jsonPath("$.items[0].recordedAt").value(recordedBody.get("recordedAt").asText()))
            .andExpect(jsonPath("$.items[0].allocations.length()").value(2))
            .andReturn();
        JsonNode historyBody = objectMapper.readTree(history.getResponse().getContentAsString());
        assertThat(fieldNames(historyBody)).containsExactlyInAnyOrder("items", "page", "size", "hasNext");
        assertThat(fieldNames(historyBody.get("items").get(0))).containsExactlyInAnyOrder(
            "posSettlementId", "posBusinessDate", "submittedTotalMinor", "recordedAt", "allocations", "receipt"
        );
        assertThat(historyBody.get("items").get(0).get("posSettlementId").asText())
            .isEqualTo(recordedBody.get("posSettlementId").asText());
        assertThat(fieldNames(historyBody.get("items").get(0).get("receipt")))
            .containsExactlyInAnyOrder("status", "fileName", "contentType", "sizeBytes", "uploadedAt", "expiresAt");
        assertThat(historyBody.get("items").get(0).get("receipt").get("status").asText()).isEqualTo("NONE");
        assertThat(historyBody.get("items").get(0).get("receipt").get("fileName").isNull()).isTrue();
        assertThat(fieldNames(historyBody.get("items").get(0).get("allocations").get(0)))
            .containsExactlyInAnyOrder("partnerDisplayName", "confirmedAt", "receivableAmountMinor");
        for (JsonNode allocation : historyBody.get("items").get(0).get("allocations")) {
            String partnerDisplayName = allocation.get("partnerDisplayName").asText();
            assertThat(partnerDisplayName).isIn("협력사 A", "협력사 B");
            assertThat(allocation.get("confirmedAt").asText()).isEqualTo("2026-08-12T01:01:00Z");
            assertThat(allocation.get("receivableAmountMinor").asLong())
                .isEqualTo("협력사 A".equals(partnerDisplayName) ? 1_000L : 2_000L);
        }
    }

    @Test
    void paginatesStoreScopedSettlementHeadersWithoutSplittingTheirAllocations() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealContract contract = postpaidContract(STORE_ID);
        MealContract otherStoreContract = postpaidContract(OTHER_STORE_ID);
        mealContractRepository.save(contract);
        mealContractRepository.save(otherStoreContract);
        MealUsage latestFirst = confirmedUsage(contract.id(), STORE_ID, 1_000, 0, 1_000, 0);
        MealUsage latestSecond = confirmedUsage(contract.id(), STORE_ID, 2_000, 0, 2_000, 0);
        MealUsage tieHigher = confirmedUsage(contract.id(), STORE_ID, 3_000, 0, 3_000, 0);
        MealUsage tieLower = confirmedUsage(contract.id(), STORE_ID, 4_000, 0, 4_000, 0);
        MealUsage otherStoreUsage = confirmedUsage(otherStoreContract.id(), OTHER_STORE_ID, 5_000, 0, 5_000, 0);
        mealUsageRepository.save(latestFirst);
        mealUsageRepository.save(latestSecond);
        mealUsageRepository.save(tieHigher);
        mealUsageRepository.save(tieLower);
        mealUsageRepository.save(otherStoreUsage);
        UUID latestSettlementId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID tieHigherSettlementId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        UUID tieLowerSettlementId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        insertSettlement(
            latestSettlementId,
            STORE_ID,
            contract.id(),
            LocalDate.of(2026, 8, 11),
            "store-hk",
            Instant.parse("2026-08-12T03:00:00Z"),
            new SettlementAllocationFixture(latestFirst.id().value(), 1_000),
            new SettlementAllocationFixture(latestSecond.id().value(), 2_000)
        );
        insertSettlement(
            tieHigherSettlementId,
            STORE_ID,
            contract.id(),
            LocalDate.of(2026, 8, 11),
            "store-hk",
            Instant.parse("2026-08-12T02:00:00Z"),
            new SettlementAllocationFixture(tieHigher.id().value(), 3_000)
        );
        insertSettlement(
            tieLowerSettlementId,
            STORE_ID,
            contract.id(),
            LocalDate.of(2026, 8, 11),
            "store-hk",
            Instant.parse("2026-08-12T02:00:00Z"),
            new SettlementAllocationFixture(tieLower.id().value(), 4_000)
        );
        insertSettlement(
            UUID.fromString("00000000-0000-0000-0000-000000000040"),
            OTHER_STORE_ID,
            otherStoreContract.id(),
            LocalDate.of(2026, 8, 11),
            "other-store",
            Instant.parse("2026-08-12T04:00:00Z"),
            new SettlementAllocationFixture(otherStoreUsage.id().value(), 5_000)
        );
        UUID availableReceiptId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID expiredReceiptId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        Instant availableUploadedAt = Instant.now().minusSeconds(24L * 60 * 60);
        Instant expiredUploadedAt = Instant.now().minusSeconds(366L * 24 * 60 * 60);
        insertReceipt(
            availableReceiptId,
            latestSettlementId,
            STORE_ID,
            "settlement.pdf",
            "application/pdf",
            availableUploadedAt
        );
        insertReceipt(
            expiredReceiptId,
            tieHigherSettlementId,
            STORE_ID,
            "expired-receipt.jpg",
            "image/jpeg",
            expiredUploadedAt
        );
        SessionHandle session = authenticatedSession("store-hk", "correct-password");

        MvcResult firstHistoryPage = mockMvc.perform(get("/api/v1/pos-settlements?page=0&size=2").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].recordedAt").value("2026-08-12T03:00:00Z"))
            .andExpect(jsonPath("$.items[0].submittedTotalMinor").value(3_000))
            .andExpect(jsonPath("$.items[0].allocations.length()").value(2))
            .andExpect(jsonPath("$.items[0].allocations[*].receivableAmountMinor").value(org.hamcrest.Matchers.containsInAnyOrder(
                1_000, 2_000
            )))
            .andExpect(jsonPath("$.items[1].recordedAt").value("2026-08-12T02:00:00Z"))
            .andExpect(jsonPath("$.items[1].submittedTotalMinor").value(3_000))
            .andExpect(jsonPath("$.items[1].allocations.length()").value(1))
            .andExpect(jsonPath("$.items[1].allocations[0].receivableAmountMinor").value(3_000))
            .andExpect(jsonPath("$.items[0].receipt.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.items[0].receipt.fileName").value("settlement.pdf"))
            .andExpect(jsonPath("$.items[0].receipt.contentType").value("application/pdf"))
            .andExpect(jsonPath("$.items[0].receipt.sizeBytes").value(1))
            .andExpect(jsonPath("$.items[1].receipt.status").value("EXPIRED"))
            .andExpect(jsonPath("$.items[1].receipt.fileName").value("expired-receipt.jpg"))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn();
        String firstHistoryJson = firstHistoryPage.getResponse().getContentAsString();
        assertThat(firstHistoryJson).contains("posSettlementId");
        assertThat(firstHistoryJson).doesNotContain(
            "opaque-object-key",
            availableReceiptId.toString(),
            expiredReceiptId.toString(),
            STORE_ID.value().toString()
        );
        MvcResult secondHistoryPage = mockMvc.perform(get("/api/v1/pos-settlements?page=1&size=2").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].recordedAt").value("2026-08-12T02:00:00Z"))
            .andExpect(jsonPath("$.items[0].submittedTotalMinor").value(4_000))
            .andExpect(jsonPath("$.items[0].allocations.length()").value(1))
            .andExpect(jsonPath("$.items[0].allocations[0].receivableAmountMinor").value(4_000))
            .andExpect(jsonPath("$.items[0].receipt.status").value("NONE"))
            .andExpect(jsonPath("$.items[0].receipt.fileName").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andReturn();
        assertThat(secondHistoryPage.getResponse().getContentAsString()).doesNotContain(STORE_ID.value().toString());
    }

    @Test
    void rejectsScopeStatusContractAndTotalFailuresWithoutCreatingAnAllocation() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealContract contract = postpaidContract(STORE_ID);
        MealContract secondContract = postpaidContract(STORE_ID);
        MealContract otherStoreContract = postpaidContract(OTHER_STORE_ID);
        mealContractRepository.save(contract);
        mealContractRepository.save(secondContract);
        mealContractRepository.save(otherStoreContract);
        MealUsage valid = confirmedUsage(contract.id(), STORE_ID, 1_000, 0, 1_000, 0);
        MealUsage crossStore = confirmedUsage(otherStoreContract.id(), OTHER_STORE_ID, 1_000, 0, 1_000, 0);
        MealUsage pending = MealUsage.pending(
            new MealUsageId(UUID.randomUUID()), STORE_ID, contract.id(), EntrySource.STORE_TABLET, 1_000, Instant.parse("2026-08-12T02:00:00Z")
        );
        MealUsage fullyPrepaid = confirmedUsage(contract.id(), STORE_ID, 1_000, 1_000, 0, 0);
        mealUsageRepository.save(valid);
        mealUsageRepository.save(crossStore);
        mealUsageRepository.save(pending);
        mealUsageRepository.save(fullyPrepaid);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");

        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(),
                settlementBody(otherStoreContract.id(), List.of(valid.id().value()), 1_000)))
            .andExpect(problem(HttpStatus.NOT_FOUND.value(), "MEAL_CONTRACT_NOT_FOUND"));
        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(),
                settlementBody(contract.id(), List.of(crossStore.id().value()), 1_000)))
            .andExpect(problem(HttpStatus.NOT_FOUND.value(), "MEAL_USAGE_NOT_FOUND"));
        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(),
                settlementBody(secondContract.id(), List.of(valid.id().value()), 1_000)))
            .andExpect(problem(HttpStatus.CONFLICT.value(), "POS_SETTLEMENT_USAGE_CONTRACT_MISMATCH"));
        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(),
                settlementBody(contract.id(), List.of(pending.id().value()), 1_000)))
            .andExpect(problem(HttpStatus.CONFLICT.value(), "POS_SETTLEMENT_USAGE_NOT_OUTSTANDING"));
        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(),
                settlementBody(contract.id(), List.of(fullyPrepaid.id().value()), 1_000)))
            .andExpect(problem(HttpStatus.CONFLICT.value(), "POS_SETTLEMENT_USAGE_NOT_OUTSTANDING"));
        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(),
                settlementBody(contract.id(), List.of(valid.id().value(), pending.id().value()), 2_000)))
            .andExpect(problem(HttpStatus.CONFLICT.value(), "POS_SETTLEMENT_USAGE_NOT_OUTSTANDING"));
        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(),
                settlementBody(contract.id(), List.of(valid.id().value()), 999)))
            .andExpect(problem(HttpStatus.CONFLICT.value(), "POS_SETTLEMENT_TOTAL_MISMATCH"));
        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(),
                settlementBody(contract.id(), List.of(valid.id().value(), valid.id().value()), 2_000)))
            .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from pos_settlements", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from pos_settlement_allocations", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select prepaid_balance from meal_contracts where id = ?", Long.class, contract.id().value()
        )).isZero();
    }

    @Test
    void requiresAuthenticatedStoreStaffAndRejectsClientControlledStoreFields() throws Exception {
        String body = "{" +
            "\"mealContractId\":\"" + UUID.randomUUID() + "\"," +
            "\"posBusinessDate\":\"2026-08-11\"," +
            "\"submittedTotalMinor\":1000," +
            "\"mealUsageIds\":[\"" + UUID.randomUUID() + "\"]," +
            "\"storeId\":\"" + OTHER_STORE_ID.value() + "\"}";
        mockMvc.perform(get("/api/v1/pos-settlements/receivables"))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/pos-settlements?page=0&size=1"))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/pos-settlements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/pos-settlements?page=0&size=1")
                .with(user("partner").roles("PARTNER")))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/pos-settlements")
                .with(user("partner").roles("PARTNER"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED"));

        seedAccount("store-hk", "correct-password", STORE_ID);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");
        for (String query : new String[] {
            "page=-1&size=1",
            "page=0&size=0",
            "page=0&size=101",
            "page=0&size=not-a-number",
            "size=1",
            "page=0"
        }) {
            mockMvc.perform(get("/api/v1/pos-settlements?" + query).cookie(session.cookie()))
                .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
        }
        mockMvc.perform(get("/api/v1/pos-settlements?page=0&size=1").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(recordRequest(session, csrfToken(session), UUID.randomUUID(), body))
            .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
    }

    @Test
    void serializesConcurrentExactRetriesAndBlocksASecondIntentForTheSameUsage() throws Exception {
        MealContract retryContract = postpaidContract(STORE_ID);
        mealContractRepository.save(retryContract);
        MealUsage retryUsage = confirmedUsage(retryContract.id(), STORE_ID, 1_000, 0, 1_000, 0);
        mealUsageRepository.save(retryUsage);
        UUID retryKey = UUID.randomUUID();
        RecordPosSettlementCommand retryCommand = command(retryContract.id(), retryKey, List.of(retryUsage.id().value()), 1_000);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> awaitAndRecord(start, retryCommand));
            Future<?> second = executor.submit(() -> awaitAndRecord(start, retryCommand));
            start.countDown();
            Object firstResult = first.get(10, TimeUnit.SECONDS);
            Object secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult).isEqualTo(secondResult);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from pos_settlements", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from pos_settlement_allocations", Long.class)).isEqualTo(1);

        MealContract overlapContract = postpaidContract(STORE_ID);
        mealContractRepository.save(overlapContract);
        MealUsage overlapUsage = confirmedUsage(overlapContract.id(), STORE_ID, 2_000, 0, 2_000, 0);
        mealUsageRepository.save(overlapUsage);
        CountDownLatch overlapStart = new CountDownLatch(1);
        ExecutorService overlapExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = overlapExecutor.submit(() -> awaitAndRecord(
                overlapStart,
                command(overlapContract.id(), UUID.randomUUID(), List.of(overlapUsage.id().value()), 2_000)
            ));
            Future<?> second = overlapExecutor.submit(() -> awaitAndRecord(
                overlapStart,
                command(overlapContract.id(), UUID.randomUUID(), List.of(overlapUsage.id().value()), 2_000)
            ));
            overlapStart.countDown();
            int firstSucceeded = completedSuccessfully(first);
            int secondSucceeded = completedSuccessfully(second);
            int successCount = firstSucceeded + secondSucceeded;
            assertThat(successCount).isEqualTo(1);
            Future<?> rejected = firstSucceeded == 0 ? first : second;
            assertThat(conflictFrom(rejected).reason())
                .isEqualTo(PosSettlementConflictException.Reason.USAGE_ALREADY_ALLOCATED);
        } finally {
            overlapStart.countDown();
            overlapExecutor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from pos_settlement_allocations where meal_usage_id = ?", Long.class, overlapUsage.id().value()))
            .isEqualTo(1);
    }

    @Test
    void exposesSettlementAndReceivableContractsInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements'].get.parameters[?(@.name == 'page')].schema.minimum").value(0))
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements'].get.parameters[?(@.name == 'size')].schema.minimum").value(1))
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements'].get.parameters[?(@.name == 'size')].schema.maximum").value(100))
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements'].get.responses['200']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements'].get.responses['400']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements/receivables'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements'].post").exists())
            .andExpect(jsonPath("$.components.schemas.PosSettlementRequest.properties.mealContractId").exists())
            .andExpect(jsonPath("$.components.schemas.PosSettlementRequest.properties.posBusinessDate").exists())
            .andExpect(jsonPath("$.components.schemas.PosSettlementRequest.properties.submittedTotalMinor").exists())
            .andExpect(jsonPath("$.components.schemas.PosSettlementRequest.properties.mealUsageIds").exists())
            .andExpect(jsonPath("$.paths['/api/v1/pos-settlements'].post.responses['409']").exists())
            .andExpect(jsonPath("$.components.schemas.ProblemResponse.properties.errorCode").exists());
    }

    private Object awaitAndRecord(CountDownLatch latch, RecordPosSettlementCommand command) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start POS settlement");
            }
            return recordPosSettlementUseCase.record(command);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while recording POS settlement", exception);
        }
    }

    private int completedSuccessfully(Future<?> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
            return 1;
        } catch (ExecutionException exception) {
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for POS settlement", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Timed out waiting for POS settlement", exception);
        }
    }

    private PosSettlementConflictException conflictFrom(Future<?> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
            throw new AssertionError("Expected concurrent POS settlement to fail");
        } catch (ExecutionException exception) {
            assertThat(exception.getCause()).isInstanceOf(PosSettlementConflictException.class);
            return (PosSettlementConflictException) exception.getCause();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for POS settlement", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Timed out waiting for POS settlement", exception);
        }
    }

    private RecordPosSettlementCommand command(MealContractId contractId, UUID key, List<UUID> usageIds, long total) {
        return new RecordPosSettlementCommand(
            STORE_ID,
            "store-hk",
            key,
            contractId,
            LocalDate.of(2026, 8, 11),
            total,
            usageIds
        );
    }

    private SessionHandle authenticatedSession(String loginId, String password) throws Exception {
        SessionHandle session = csrfSession();
        MvcResult login = mockMvc.perform(post("/api/v1/sessions")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", csrfToken(session))
                .param("loginId", loginId)
                .param("password", password))
            .andExpect(status().isNoContent())
            .andReturn();
        return StoreOnboardingHttpIntegrationSupport.authenticatedSession(mockMvc, objectMapper, login);
    }

    private SessionHandle csrfSession() throws Exception {
        return StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
    }

    private String csrfToken(SessionHandle session) throws Exception {
        return StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder recordRequest(
        SessionHandle session,
        String csrfToken,
        UUID idempotencyKey,
        String body
    ) {
        return post("/api/v1/pos-settlements")
            .cookie(session.cookie())
            .header("X-CSRF-TOKEN", csrfToken)
            .header("Idempotency-Key", idempotencyKey.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private void insertSettlement(
        UUID settlementId,
        StoreId storeId,
        MealContractId contractId,
        LocalDate posBusinessDate,
        String recorder,
        Instant recordedAt,
        SettlementAllocationFixture... allocations
    ) {
        long total = 0;
        for (SettlementAllocationFixture allocation : allocations) {
            total = Math.addExact(total, allocation.receivableAmountMinor());
        }
        jdbcTemplate.update(
            """
                insert into pos_settlements
                    (id, store_id, meal_contract_id, pos_business_date, submitted_total_minor,
                     recorded_by_login_id, recorded_at, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            settlementId,
            storeId.value(),
            contractId.value(),
            java.sql.Date.valueOf(posBusinessDate),
            total,
            recorder,
            java.sql.Timestamp.from(recordedAt),
            UUID.randomUUID()
        );
        for (SettlementAllocationFixture allocation : allocations) {
            jdbcTemplate.update(
                """
                    insert into pos_settlement_allocations
                        (pos_settlement_id, meal_usage_id, receivable_amount_minor)
                    values (?, ?, ?)
                    """,
                settlementId,
                allocation.mealUsageId(),
                allocation.receivableAmountMinor()
            );
        }
    }

    private void insertReceipt(
        UUID receiptId,
        UUID settlementId,
        StoreId storeId,
        String fileName,
        String contentType,
        Instant uploadedAt
    ) {
        jdbcTemplate.update(
            """
                insert into pos_settlement_receipts
                    (id, pos_settlement_id, store_id, object_key, file_name, content_type,
                     size_bytes, uploaded_at, expires_at, scan_status, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLEAN', null)
                """,
            receiptId,
            settlementId,
            storeId.value(),
            "opaque-object-key-" + receiptId,
            fileName,
            contentType,
            1L,
            java.sql.Timestamp.from(uploadedAt),
            java.sql.Timestamp.from(uploadedAt.plusSeconds(365L * 24 * 60 * 60))
        );
    }

    private String settlementBody(MealContractId contractId, List<UUID> usageIds, long total) {
        return "{" +
            "\"mealContractId\":\"" + contractId.value() + "\"," +
            "\"posBusinessDate\":\"2026-08-11\"," +
            "\"submittedTotalMinor\":" + total + "," +
            "\"mealUsageIds\":[" + usageIds.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    private org.springframework.test.web.servlet.ResultMatcher problem(int expectedStatus, String errorCode) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.status").value(expectedStatus).match(result);
            jsonPath("$.errorCode").value(errorCode).match(result);
            header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")).match(result);
        };
    }

    private Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    private JsonNode partnerSummary(JsonNode summaries, MealContractId mealContractId) {
        for (JsonNode summary : summaries) {
            if (mealContractId.value().toString().equals(summary.get("mealContractId").asText())) {
                return summary;
            }
        }
        throw new AssertionError("Partner receivable summary was not found");
    }

    private record SettlementAllocationFixture(UUID mealUsageId, long receivableAmountMinor) {
    }

    private void seedAccount(String loginId, String password, StoreId storeId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId,
            passwordEncoder.encode(password),
            storeId.value()
        );
    }

    private MealContract postpaidContract(StoreId storeId) {
        return new MealContract(new MealContractId(UUID.randomUUID()), storeId, MealContractPaymentType.POSTPAID, 0);
    }

    private MealContract prepaidContract(StoreId storeId, long prepaidBalance) {
        return new MealContract(
            new MealContractId(UUID.randomUUID()), storeId, MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW, prepaidBalance
        );
    }

    private MealUsage confirmedUsage(
        MealContractId contractId,
        StoreId storeId,
        long amount,
        long prepaidApplied,
        long receivableCreated,
        long remainingPrepaid
    ) {
        return confirmedUsage(
            contractId,
            storeId,
            amount,
            prepaidApplied,
            receivableCreated,
            remainingPrepaid,
            null
        );
    }

    private MealUsage confirmedUsage(
        MealContractId contractId,
        StoreId storeId,
        long amount,
        long prepaidApplied,
        long receivableCreated,
        long remainingPrepaid,
        String partnerDisplayName
    ) {
        return MealUsage.restoreConfirmed(
            new MealUsageId(UUID.randomUUID()),
            storeId,
            contractId,
            EntrySource.STORE_TABLET,
            amount,
            Instant.parse("2026-08-12T01:00:00Z"),
            0,
            new Confirmation("HK", Instant.parse("2026-08-12T01:01:00Z")),
            new PrepaidAllocation(amount, prepaidApplied, receivableCreated, remainingPrepaid),
            partnerDisplayName,
            null
        );
    }

    private MealUsage cancelledUsage(MealContractId contractId, StoreId storeId) {
        UUID contextId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-12T00:00:00Z");
        jdbcTemplate.update(
            """
                insert into meal_usage_qr_contexts
                    (id, store_id, store_display_name, token_hash, expires_at, revoked_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
            contextId,
            storeId.value(),
            "TIEAT Store",
            "a".repeat(64),
            java.sql.Timestamp.from(createdAt.plusSeconds(86_400)),
            null,
            java.sql.Timestamp.from(createdAt)
        );
        return MealUsage.restoreCancelled(
            new MealUsageId(UUID.randomUUID()),
            storeId,
            contractId,
            EntrySource.PARTNER_MOBILE,
            700,
            createdAt,
            0,
            new Cancellation(CancellationReason.PUBLIC_SELF_CORRECTION, createdAt.plusSeconds(60)),
            "협력사 A",
            new MealUsageQrContextId(contextId)
        );
    }
}
