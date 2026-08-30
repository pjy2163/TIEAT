package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.ledger.application.CreateMealUsageCommand;
import com.tieat.ledger.application.CreateMealUsageUseCase;
import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.partnership.domain.MealContractId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/meal-usages")
class MealUsageCreationController {

    private final CreateMealUsageUseCase createMealUsageUseCase;

    MealUsageCreationController(CreateMealUsageUseCase createMealUsageUseCase) {
        this.createMealUsageUseCase = createMealUsageUseCase;
    }

    @Operation(summary = "Create a pending meal usage from a store tablet")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = CreationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied or invalid CSRF token", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Meal contract not found", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "429", description = "Store pending meal usage capacity reached", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping
    ResponseEntity<CreationResponse> create(
        @Valid @RequestBody CreationRequest request,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        MealUsage created = createMealUsageUseCase.create(new CreateMealUsageCommand(
            principal.storeId(), new MealContractId(request.mealContractId()), EntrySource.STORE_TABLET, request.amountMinor()
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.LOCATION, mealUsageLocation(created))
            .body(CreationResponse.from(created));
    }

    private String mealUsageLocation(MealUsage mealUsage) {
        return ServletUriComponentsBuilder.fromCurrentRequestUri()
            .path("/{mealUsageId}")
            .buildAndExpand(mealUsage.id().value())
            .toUriString();
    }

    record CreationRequest(@NotNull UUID mealContractId, @Positive long amountMinor) {
    }

    record CreationResponse(UUID mealUsageId, String status, long amountMinor, Instant createdAt) {
        static CreationResponse from(MealUsage mealUsage) {
            return new CreationResponse(
                mealUsage.id().value(), mealUsage.status().name(), mealUsage.amount(), mealUsage.createdAt()
            );
        }
    }
}
