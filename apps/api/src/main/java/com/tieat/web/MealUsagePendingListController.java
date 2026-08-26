package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.ledger.application.ListPendingMealUsagesQuery;
import com.tieat.ledger.application.ListPendingMealUsagesUseCase;
import com.tieat.ledger.application.PendingMealUsagePage;
import com.tieat.ledger.domain.MealUsage;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meal-usages")
class MealUsagePendingListController {

    private final ListPendingMealUsagesUseCase listPendingMealUsagesUseCase;

    MealUsagePendingListController(ListPendingMealUsagesUseCase listPendingMealUsagesUseCase) {
        this.listPendingMealUsagesUseCase = listPendingMealUsagesUseCase;
    }

    @Operation(summary = "List pending meal usages for the authenticated store")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pending meal usages", content = @Content(schema = @Schema(implementation = PendingMealUsageListResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping
    PendingMealUsageListResponse list(
        @Parameter(required = true, schema = @Schema(allowableValues = {"PENDING"}))
        @RequestParam String status,
        @Parameter(required = true, schema = @Schema(minimum = "0"))
        @RequestParam int page,
        @Parameter(required = true, schema = @Schema(minimum = "1", maximum = "100"))
        @RequestParam int size,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        PendingMealUsagePage result = listPendingMealUsagesUseCase.list(
            new ListPendingMealUsagesQuery(principal.storeId(), status, page, size)
        );
        return PendingMealUsageListResponse.from(result);
    }

    record PendingMealUsageListResponse(List<PendingMealUsageItemResponse> items, int page, int size, boolean hasNext) {
        static PendingMealUsageListResponse from(PendingMealUsagePage page) {
            return new PendingMealUsageListResponse(
                page.items().stream().map(PendingMealUsageItemResponse::from).toList(),
                page.page(),
                page.size(),
                page.hasNext()
            );
        }
    }

    record PendingMealUsageItemResponse(
        UUID mealUsageId,
        String status,
        String entrySource,
        String partnerDisplayName,
        String customerName,
        long amountMinor,
        Instant createdAt
    ) {
        static PendingMealUsageItemResponse from(MealUsage mealUsage) {
            return new PendingMealUsageItemResponse(
                mealUsage.id().value(),
                mealUsage.status().name(),
                mealUsage.entrySource().name(),
                mealUsage.partnerDisplayNameSnapshot().orElse(null),
                mealUsage.customerNameSnapshot().orElse(null),
                mealUsage.amount(),
                mealUsage.createdAt()
            );
        }
    }
}
