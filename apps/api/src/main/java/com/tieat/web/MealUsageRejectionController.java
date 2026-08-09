package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.ledger.application.RejectMealUsageCommand;
import com.tieat.ledger.application.RejectMealUsageUseCase;
import com.tieat.ledger.domain.MealUsage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/meal-usages")
class MealUsageRejectionController {

    private final RejectMealUsageUseCase rejectMealUsageUseCase;

    MealUsageRejectionController(RejectMealUsageUseCase rejectMealUsageUseCase) {
        this.rejectMealUsageUseCase = rejectMealUsageUseCase;
    }

    @Operation(summary = "Reject a pending meal usage without ledger allocation")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Rejected", content = @Content(schema = @Schema(implementation = RejectionResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied or invalid CSRF token", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Meal usage not found", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Meal usage is already terminal", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping("/{mealUsageId}/rejections")
    ResponseEntity<RejectionResponse> reject(
        @PathVariable UUID mealUsageId,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        MealUsage rejected = rejectMealUsageUseCase.reject(new RejectMealUsageCommand(
            new com.tieat.ledger.domain.MealUsageId(mealUsageId), principal.storeId(), principal.getUsername()
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.LOCATION, ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString())
            .body(RejectionResponse.from(rejected));
    }

    record RejectionResponse(UUID mealUsageId, String status, Instant rejectedAt, long version) {
        static RejectionResponse from(MealUsage mealUsage) {
            return new RejectionResponse(
                mealUsage.id().value(),
                mealUsage.status().name(),
                mealUsage.rejection().orElseThrow().rejectedAt(),
                mealUsage.version()
            );
        }
    }
}
