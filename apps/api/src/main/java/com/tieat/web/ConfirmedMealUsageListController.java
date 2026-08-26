package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.ledger.application.ConfirmedMealUsagePage;
import com.tieat.ledger.application.ListConfirmedMealUsagesQuery;
import com.tieat.ledger.application.ListMonthlyMealUsagesUseCase;
import com.tieat.partnership.domain.MealContractId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meal-usages/confirmed")
class ConfirmedMealUsageListController {

    private static final String TIME_ZONE = "Asia/Seoul";

    private final ListMonthlyMealUsagesUseCase listMonthlyMealUsagesUseCase;

    ConfirmedMealUsageListController(ListMonthlyMealUsagesUseCase listMonthlyMealUsagesUseCase) {
        this.listMonthlyMealUsagesUseCase = listMonthlyMealUsagesUseCase;
    }

    @Operation(summary = "List confirmed meal usages for an authenticated store date range")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Confirmed meal usage ledger", content = @Content(schema = @Schema(implementation = ConfirmedMealUsageListResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid date or page request", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Partner contract was not found in the authenticated store", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping
    ResponseEntity<ConfirmedMealUsageListResponse> list(
        @Parameter(required = true, schema = @Schema(pattern = "^\\d{4}-(0[1-9]|1[0-2])-([0-2]\\d|3[01])$"))
        @RequestParam String fromDate,
        @Parameter(required = true, schema = @Schema(pattern = "^\\d{4}-(0[1-9]|1[0-2])-([0-2]\\d|3[01])$"))
        @RequestParam String toDate,
        @Parameter(required = true, schema = @Schema(minimum = "0"))
        @RequestParam int page,
        @Parameter(required = true, schema = @Schema(minimum = "1", maximum = "100"))
        @RequestParam int size,
        @Parameter(required = false, description = "Optional authenticated-store partner contract filter")
        @RequestParam(required = false) UUID mealContractId,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        ConfirmedMealUsagePage result = listMonthlyMealUsagesUseCase.listConfirmed(
            new ListConfirmedMealUsagesQuery(
                principal.storeId(),
                fromDate,
                toDate,
                page,
                size,
                mealContractId == null ? null : new MealContractId(mealContractId)
            )
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ConfirmedMealUsageListResponse.from(result));
    }

    record ConfirmedMealUsageListResponse(
        String fromDate,
        String toDate,
        String timeZone,
        List<MonthlyMealUsageListController.MonthlyMealUsageItemResponse> items,
        int page,
        int size,
        boolean hasNext,
        long totalAmountMinor
    ) {

        static ConfirmedMealUsageListResponse from(ConfirmedMealUsagePage page) {
            return new ConfirmedMealUsageListResponse(
                page.fromDate(),
                page.toDate(),
                TIME_ZONE,
                page.items().stream().map(MonthlyMealUsageListController.MonthlyMealUsageItemResponse::from).toList(),
                page.page(),
                page.size(),
                page.hasNext(),
                page.totalAmountMinor()
            );
        }
    }
}
