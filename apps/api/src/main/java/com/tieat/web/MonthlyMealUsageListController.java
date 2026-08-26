package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.ledger.application.ListMonthlyMealUsagesQuery;
import com.tieat.ledger.application.ListMonthlyMealUsagesUseCase;
import com.tieat.ledger.application.MonthlyMealUsagePage;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.ledger.domain.MonthlyMealUsageRow;
import com.tieat.partnership.domain.MealContractId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meal-usages/months")
class MonthlyMealUsageListController {

    private static final String TIME_ZONE = "Asia/Seoul";

    private final ListMonthlyMealUsagesUseCase listMonthlyMealUsagesUseCase;

    MonthlyMealUsageListController(ListMonthlyMealUsagesUseCase listMonthlyMealUsagesUseCase) {
        this.listMonthlyMealUsagesUseCase = listMonthlyMealUsagesUseCase;
    }

    @Operation(summary = "List confirmed meal usages for an authenticated store calendar range")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Monthly meal usage ledger", content = @Content(schema = @Schema(implementation = MonthlyMealUsageListResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid month or page request", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Partner contract was not found in the authenticated store", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/{month}")
    ResponseEntity<MonthlyMealUsageListResponse> list(
        @Parameter(required = true, schema = @Schema(pattern = "^\\d{4}-(0[1-9]|1[0-2])$"))
        @PathVariable String month,
        @Parameter(required = false, schema = @Schema(pattern = "^\\d{4}-(0[1-9]|1[0-2])$"))
        @RequestParam(required = false) String to,
        @Parameter(required = true, schema = @Schema(minimum = "0"))
        @RequestParam int page,
        @Parameter(required = true, schema = @Schema(minimum = "1", maximum = "100"))
        @RequestParam int size,
        @Parameter(required = false, description = "Optional authenticated-store partner contract filter")
        @RequestParam(required = false) UUID mealContractId,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        MonthlyMealUsagePage result = listMonthlyMealUsagesUseCase.list(
            new ListMonthlyMealUsagesQuery(
                principal.storeId(),
                month,
                to,
                page,
                size,
                mealContractId == null ? null : new MealContractId(mealContractId)
            )
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(MonthlyMealUsageListResponse.from(result));
    }

    record MonthlyMealUsageListResponse(
        String month,
        String fromMonth,
        String toMonth,
        String timeZone,
        List<MonthlyMealUsageItemResponse> items,
        int page,
        int size,
        boolean hasNext,
        long totalAmountMinor
    ) {

        static MonthlyMealUsageListResponse from(MonthlyMealUsagePage page) {
            return new MonthlyMealUsageListResponse(
                page.month(),
                page.fromMonth(),
                page.toMonth(),
                TIME_ZONE,
                page.items().stream().map(MonthlyMealUsageItemResponse::from).toList(),
                page.page(),
                page.size(),
                page.hasNext(),
                page.totalAmountMinor()
            );
        }
    }

    record MonthlyMealUsageItemResponse(
        UUID id,
        UUID mealContractId,
        String status,
        String partnerDisplayName,
        long amountMinor,
        Instant createdAt,
        String confirmedStaffInitials,
        SettlementStatus settlementStatus
    ) {

        static MonthlyMealUsageItemResponse from(MonthlyMealUsageRow row) {
            MealUsage mealUsage = row.mealUsage();
            if (mealUsage.status() != MealUsageStatus.CONFIRMED) {
                throw new IllegalStateException("Monthly ledger accepts confirmed meal usages only");
            }
            String confirmedStaffInitials = mealUsage.confirmation()
                .map(confirmation -> confirmation.staffInitials())
                .filter(initials -> !initials.isBlank())
                .orElseThrow(() -> new IllegalStateException("Confirmed meal usage requires staff initials"));
            return new MonthlyMealUsageItemResponse(
                mealUsage.id().value(),
                mealUsage.mealContractId().value(),
                "CONFIRMED",
                mealUsage.partnerDisplayNameSnapshot().orElse(null),
                mealUsage.amount(),
                mealUsage.createdAt(),
                confirmedStaffInitials,
                settlementStatus(mealUsage, row.settlementAllocationExists())
            );
        }

        private static SettlementStatus settlementStatus(MealUsage mealUsage, boolean allocationExists) {
            var allocation = mealUsage.prepaidAllocation()
                .orElseThrow(() -> new IllegalStateException("Confirmed meal usage requires allocation data"));
            if (allocation.receivableCreated() > 0) {
                return allocationExists ? SettlementStatus.PAYMENT_RECORDED : SettlementStatus.PAYMENT_DUE;
            }
            if (allocation.prepaidApplied() > 0) {
                return SettlementStatus.PREPAID_SETTLED;
            }
            return null;
        }
    }

    enum SettlementStatus {
        PAYMENT_DUE,
        PAYMENT_RECORDED,
        PREPAID_SETTLED
    }
}
