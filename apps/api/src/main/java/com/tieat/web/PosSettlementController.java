package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.settlement.application.ListPosSettlementsQuery;
import com.tieat.settlement.application.PosSettlementPage;
import com.tieat.settlement.application.RecordPosSettlementCommand;
import com.tieat.settlement.application.RecordPosSettlementUseCase;
import com.tieat.settlement.domain.PosSettlement;
import com.tieat.settlement.domain.PosSettlementRepository;
import com.tieat.settlement.receipt.application.PosSettlementReceiptService;
import com.tieat.store.domain.StoreId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos-settlements")
class PosSettlementController {

    private final RecordPosSettlementUseCase recordPosSettlementUseCase;
    private final PosSettlementRepository posSettlementRepository;
    private final PosSettlementReceiptService receiptService;

    PosSettlementController(
        RecordPosSettlementUseCase recordPosSettlementUseCase,
        PosSettlementRepository posSettlementRepository,
        ObjectProvider<PosSettlementReceiptService> receiptServiceProvider
    ) {
        this.recordPosSettlementUseCase = recordPosSettlementUseCase;
        this.posSettlementRepository = posSettlementRepository;
        this.receiptService = receiptServiceProvider.getIfAvailable();
    }

    @Operation(summary = "List recorded POS settlements for the authenticated store")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recorded POS settlements", content = @Content(schema = @Schema(implementation = PosSettlementHistoryResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid page request", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping
    ResponseEntity<PosSettlementHistoryResponse> listSettlements(
        @Parameter(required = true, schema = @Schema(minimum = "0"))
        @RequestParam int page,
        @Parameter(required = true, schema = @Schema(minimum = "1", maximum = "100"))
        @RequestParam int size,
        @Parameter(description = "Exact partner display name filter")
        @RequestParam(required = false) String partner,
        @Parameter(description = "Case-insensitive partner display name search")
        @RequestParam(required = false) String search,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        PosSettlementPage settlements = recordPosSettlementUseCase.listSettlements(
            new ListPosSettlementsQuery(principal.storeId(), page, size, partner, search)
        );
        Map<UUID, PosSettlementReceiptService.ReceiptSummary> receiptSummaries = receiptService == null
            ? Map.of()
            : receiptService.summariesFor(
                principal.storeId(),
                settlements.items().stream().map(PosSettlement::id).toList()
            );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(PosSettlementHistoryResponse.from(settlements, this::toResponse, principal.storeId(), receiptSummaries));
    }

    @Operation(
        summary = "List partner receivable summaries and outstanding candidates for the authenticated store",
        description = "Returns partner-visible summaries alongside request-only outstanding receivable candidate items."
    )
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Partner receivable summaries and outstanding candidate items", content = @Content(schema = @Schema(implementation = OutstandingReceivableListResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/receivables")
    ResponseEntity<OutstandingReceivableListResponse> listOutstandingReceivables(
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        var overview = recordPosSettlementUseCase.listOutstandingReceivableOverview(principal.storeId());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(OutstandingReceivableListResponse.from(overview));
    }

    @Operation(summary = "Record an already-completed POS settlement")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Recorded or exact idempotent replay", content = @Content(schema = @Schema(implementation = PosSettlementResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied or invalid CSRF token", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Meal usage or contract was not found", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Settlement conflict", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping
    ResponseEntity<PosSettlementResponse> record(
        @Parameter(required = true, description = "UUID reused only for an exact replay within this store")
        @RequestHeader("Idempotency-Key") UUID idempotencyKey,
        @Valid @RequestBody PosSettlementRequest request,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        PosSettlement settlement = recordPosSettlementUseCase.record(new RecordPosSettlementCommand(
            principal.storeId(),
            principal.getUsername(),
            idempotencyKey,
            new MealContractId(request.mealContractId()),
            request.posBusinessDate(),
            request.submittedTotalMinor(),
            request.mealUsageIds()
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(toResponse(settlement, principal.storeId()));
    }

    private PosSettlementResponse toResponse(PosSettlement settlement, StoreId storeId) {
        return PosSettlementResponse.from(
            settlement,
            posSettlementRepository.findAllocationDisplaysBySettlementIdAndStoreId(settlement.id(), storeId)
        );
    }

    record PosSettlementRequest(
        @NotNull UUID mealContractId,
        @NotNull LocalDate posBusinessDate,
        @Positive long submittedTotalMinor,
        @NotEmpty List<@NotNull UUID> mealUsageIds
    ) {

        @AssertTrue(message = "Meal usage ids must be unique")
        public boolean isMealUsageIdsUnique() {
            return mealUsageIds != null && new HashSet<>(mealUsageIds).size() == mealUsageIds.size();
        }
    }

    record OutstandingReceivableListResponse(
        List<OutstandingReceivableResponse> items,
        List<PartnerReceivableSummaryResponse> partners
    ) {

        static OutstandingReceivableListResponse from(
            RecordPosSettlementUseCase.OutstandingReceivableOverview overview
        ) {
            return new OutstandingReceivableListResponse(
                overview.items().stream().map(OutstandingReceivableResponse::from).toList(),
                overview.partners().stream().map(PartnerReceivableSummaryResponse::from).toList()
            );
        }
    }

    record PosSettlementHistoryResponse(List<PosSettlementHistoryItemResponse> items, int page, int size, boolean hasNext) {

        static PosSettlementHistoryResponse from(
            PosSettlementPage page,
            java.util.function.BiFunction<PosSettlement, StoreId, PosSettlementResponse> responseMapper,
            StoreId storeId,
            Map<UUID, PosSettlementReceiptService.ReceiptSummary> receiptSummaries
        ) {
            return new PosSettlementHistoryResponse(
                page.items().stream().map(settlement -> PosSettlementHistoryItemResponse.from(
                    responseMapper.apply(settlement, storeId),
                    settlement.recordedByLoginId(),
                    receiptSummaries.get(settlement.id())
                )).toList(),
                page.page(),
                page.size(),
                page.hasNext()
            );
        }
    }

    record PosSettlementHistoryItemResponse(
        UUID posSettlementId,
        LocalDate posBusinessDate,
        long submittedTotalMinor,
        Instant recordedAt,
        String recordedByLoginId,
        List<PosSettlementAllocationResponse> allocations,
        PosSettlementReceiptSummaryResponse receipt
    ) {

        static PosSettlementHistoryItemResponse from(
            PosSettlementResponse response,
            String recordedByLoginId,
            PosSettlementReceiptService.ReceiptSummary receiptSummary
        ) {
            return new PosSettlementHistoryItemResponse(
                response.posSettlementId(),
                response.posBusinessDate(),
                response.submittedTotalMinor(),
                response.recordedAt(),
                recordedByLoginId,
                response.allocations(),
                PosSettlementReceiptSummaryResponse.from(receiptSummary)
            );
        }
    }

    record PosSettlementReceiptSummaryResponse(
        PosSettlementReceiptService.ReceiptStatus status,
        String fileName,
        String contentType,
        Long sizeBytes,
        Instant uploadedAt,
        Instant expiresAt
    ) {

        static PosSettlementReceiptSummaryResponse from(
            PosSettlementReceiptService.ReceiptSummary summary
        ) {
            if (summary == null) {
                return new PosSettlementReceiptSummaryResponse(
                    PosSettlementReceiptService.ReceiptStatus.NONE,
                    null,
                    null,
                    null,
                    null,
                    null
                );
            }
            return new PosSettlementReceiptSummaryResponse(
                summary.status(),
                summary.fileName(),
                summary.contentType(),
                summary.sizeBytes(),
                summary.uploadedAt(),
                summary.expiresAt()
            );
        }
    }

    record OutstandingReceivableResponse(
        UUID mealUsageId,
        UUID mealContractId,
        String partnerDisplayName,
        Instant confirmedAt,
        long receivableCreatedMinor
    ) {

        static OutstandingReceivableResponse from(PosSettlementRepository.OutstandingReceivable receivable) {
            return new OutstandingReceivableResponse(
                receivable.mealUsageId(),
                receivable.mealContractId().value(),
                receivable.partnerDisplayName(),
                receivable.confirmedAt(),
                receivable.receivableCreatedMinor()
            );
        }
    }

    record PartnerReceivableSummaryResponse(
        UUID mealContractId,
        UUID partnerOrganizationId,
        String partnerDisplayName,
        LocalDate previousPosBusinessDate,
        long periodConfirmedUsageTotalMinor,
        long periodPrepaidAppliedTotalMinor,
        long outstandingReceivableCount,
        long outstandingReceivableTotalMinor
    ) {

        static PartnerReceivableSummaryResponse from(
            PosSettlementRepository.PartnerReceivableSummary summary
        ) {
            return new PartnerReceivableSummaryResponse(
                summary.mealContractId().value(),
                summary.partnerOrganizationId(),
                summary.partnerDisplayName(),
                summary.previousPosBusinessDate(),
                summary.periodConfirmedUsageTotalMinor(),
                summary.periodPrepaidAppliedTotalMinor(),
                summary.outstandingReceivableCount(),
                summary.outstandingReceivableTotalMinor()
            );
        }
    }

    record PosSettlementResponse(
        UUID posSettlementId,
        LocalDate posBusinessDate,
        long submittedTotalMinor,
        Instant recordedAt,
        List<PosSettlementAllocationResponse> allocations
    ) {

        static PosSettlementResponse from(
            PosSettlement settlement,
            List<PosSettlementRepository.AllocationDisplay> allocationDisplays
        ) {
            Map<UUID, PosSettlementRepository.AllocationDisplay> displaysByUsageId = allocationDisplays.stream()
                .collect(Collectors.toMap(PosSettlementRepository.AllocationDisplay::mealUsageId, Function.identity()));
            return new PosSettlementResponse(
                settlement.id(),
                settlement.posBusinessDate(),
                settlement.submittedTotalMinor(),
                settlement.recordedAt(),
                settlement.allocations().stream()
                    .map(allocation -> PosSettlementAllocationResponse.from(
                        allocation,
                        displaysByUsageId.get(allocation.mealUsageId())
                    ))
                    .toList()
            );
        }
    }

    record PosSettlementAllocationResponse(
        String partnerDisplayName,
        Instant confirmedAt,
        long receivableAmountMinor
    ) {

        static PosSettlementAllocationResponse from(
            PosSettlement.Allocation allocation,
            PosSettlementRepository.AllocationDisplay display
        ) {
            return new PosSettlementAllocationResponse(
                display == null ? null : display.partnerDisplayName(),
                display == null ? null : display.confirmedAt(),
                allocation.receivableAmountMinor()
            );
        }
    }
}
