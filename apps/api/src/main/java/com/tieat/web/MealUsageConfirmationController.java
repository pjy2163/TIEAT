package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.ledger.application.ConfirmMealUsageCommand;
import com.tieat.ledger.application.ConfirmMealUsageUseCase;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/meal-usages")
class MealUsageConfirmationController {

    private final ConfirmMealUsageUseCase confirmMealUsageUseCase;

    MealUsageConfirmationController(ConfirmMealUsageUseCase confirmMealUsageUseCase) {
        this.confirmMealUsageUseCase = confirmMealUsageUseCase;
    }

    @Operation(summary = "Confirm a pending meal usage")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Confirmed", content = @Content(schema = @Schema(implementation = ConfirmationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied or invalid CSRF token", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Meal usage not found", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Confirmation conflict", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping("/{mealUsageId}/confirmations")
    ResponseEntity<ConfirmationResponse> confirm(
        @PathVariable UUID mealUsageId,
        @Valid @RequestBody ConfirmationRequest request,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        MealUsage confirmed = confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(
            new MealUsageId(mealUsageId), principal.storeId(), request.confirmerInitials()
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.LOCATION, currentRequestLocation())
            .body(ConfirmationResponse.from(confirmed));
    }

    private String currentRequestLocation() {
        return ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString();
    }

    record ConfirmationRequest(@NotBlank String confirmerInitials) {
    }

    record ConfirmationResponse(
        UUID mealUsageId,
        String status,
        String confirmerInitials,
        Instant confirmedAt,
        long version,
        long amountMinor,
        long prepaidAppliedMinor,
        long receivableCreatedMinor,
        long prepaidRemainingMinor
    ) {
        static ConfirmationResponse from(MealUsage mealUsage) {
            var confirmation = mealUsage.confirmation().orElseThrow();
            var allocation = mealUsage.prepaidAllocation().orElseThrow();
            return new ConfirmationResponse(
                mealUsage.id().value(),
                mealUsage.status().name(),
                confirmation.staffInitials(),
                confirmation.confirmedAt(),
                mealUsage.version(),
                mealUsage.amount(),
                allocation.prepaidApplied(),
                allocation.receivableCreated(),
                allocation.remainingPrepaid()
            );
        }
    }

}
