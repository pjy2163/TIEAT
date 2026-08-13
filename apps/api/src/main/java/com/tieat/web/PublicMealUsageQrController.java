package com.tieat.web;

import com.tieat.ledger.application.CreatePublicMealUsageCommand;
import com.tieat.ledger.application.CreatePublicMealUsageUseCase;
import com.tieat.ledger.application.PublicMealUsageRequestUseCase;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.application.GetPublicMealUsageQrContextUseCase;
import com.tieat.qr.application.PublicMealUsageQrContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/meal-usage-qr/{token}")
class PublicMealUsageQrController {

    private final GetPublicMealUsageQrContextUseCase getPublicMealUsageQrContextUseCase;
    private final CreatePublicMealUsageUseCase createPublicMealUsageUseCase;
    private final PublicMealUsageRequestUseCase publicMealUsageRequestUseCase;

    PublicMealUsageQrController(
        GetPublicMealUsageQrContextUseCase getPublicMealUsageQrContextUseCase,
        CreatePublicMealUsageUseCase createPublicMealUsageUseCase,
        PublicMealUsageRequestUseCase publicMealUsageRequestUseCase
    ) {
        this.getPublicMealUsageQrContextUseCase = getPublicMealUsageQrContextUseCase;
        this.createPublicMealUsageUseCase = createPublicMealUsageUseCase;
        this.publicMealUsageRequestUseCase = publicMealUsageRequestUseCase;
    }

    @Operation(summary = "Load the minimal public meal usage QR context")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "QR context", content = @Content(schema = @Schema(implementation = PublicQrContextResponse.class))),
        @ApiResponse(responseCode = "404", description = "Unknown, revoked, or expired QR", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicQrContextResponse> get(@PathVariable String token) {
        PublicMealUsageQrContext context = getPublicMealUsageQrContextUseCase.get(token);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(PublicQrContextResponse.from(context));
    }

    @Operation(summary = "Create a pending meal usage from a public QR")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pending meal usage created or replayed", content = @Content(schema = @Schema(implementation = PublicCreationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid JSON, amount, or idempotency key", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Unknown, revoked, expired QR, or unavailable contract", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency key payload conflict", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "429", description = "Public QR rate limit exceeded", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping(value = "/meal-usages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicCreationResponse> create(
        @PathVariable String token,
        @RequestHeader("Idempotency-Key") UUID idempotencyKey,
        @RequestHeader("Public-Request-Key") String publicRequestKey,
        @Valid @RequestBody PublicCreationRequest request
    ) {
        MealUsage created = createPublicMealUsageUseCase.create(new CreatePublicMealUsageCommand(
            token,
            idempotencyKey,
            new MealContractId(request.mealContractId()),
            request.amountMinor().longValueExact(),
            publicRequestKey,
            request.customerName()
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(PublicCreationResponse.fromPublicPendingRequest(created));
    }

    @Operation(summary = "Read the current status of one public meal usage request")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Public request status", content = @Content(schema = @Schema(implementation = PublicRequestResponse.class))),
        @ApiResponse(responseCode = "404", description = "Unknown, expired, or unauthorized public request", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping(value = "/meal-usages/{mealUsageId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicRequestResponse> getRequest(
        @PathVariable String token,
        @PathVariable String mealUsageId,
        @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Parameter(required = true) @RequestHeader(value = "Public-Request-Key", required = false) String publicRequestKey
    ) {
        MealUsage mealUsage = publicMealUsageRequestUseCase.get(
            requestCommand(token, mealUsageId, idempotencyKey, publicRequestKey)
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PublicRequestResponse.from(mealUsage));
    }

    @Operation(summary = "Cancel a pending public meal usage request")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cancelled request or cancellation replay", content = @Content(schema = @Schema(implementation = PublicRequestResponse.class))),
        @ApiResponse(responseCode = "404", description = "Unknown, expired, unauthorized, or terminal public request", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping(value = "/meal-usages/{mealUsageId}/cancellations", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicRequestResponse> cancel(
        @PathVariable String token,
        @PathVariable String mealUsageId,
        @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Parameter(required = true) @RequestHeader(value = "Public-Request-Key", required = false) String publicRequestKey
    ) {
        MealUsage cancelled = publicMealUsageRequestUseCase.cancel(
            requestCommand(token, mealUsageId, idempotencyKey, publicRequestKey)
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PublicRequestResponse.from(cancelled));
    }

    record PublicCreationRequest(
        @NotNull UUID mealContractId,
        @NotBlank @Size(max = CreatePublicMealUsageCommand.MAX_CUSTOMER_NAME_LENGTH) String customerName,
        @NotNull @Positive @Max(CreatePublicMealUsageCommand.MAX_AMOUNT_MINOR) @Digits(integer = 7, fraction = 0) BigDecimal amountMinor
    ) {
    }

    record PublicQrContextResponse(String storeDisplayName, List<PartnerResponse> partners, Instant qrExpiresAt) {
        static PublicQrContextResponse from(PublicMealUsageQrContext context) {
            return new PublicQrContextResponse(
                context.storeDisplayName(),
                context.partners().stream().map(PartnerResponse::from).toList(),
                context.qrExpiresAt()
            );
        }
    }

    record PartnerResponse(UUID mealContractId, String partnerDisplayName) {
        static PartnerResponse from(PublicMealUsageQrContext.PartnerOption option) {
            return new PartnerResponse(option.mealContractId().value(), option.partnerDisplayName());
        }
    }

    record PublicCreationResponse(UUID mealUsageId, String status, long amountMinor, Instant createdAt) {
        static PublicCreationResponse fromPublicPendingRequest(MealUsage mealUsage) {
            return new PublicCreationResponse(
                mealUsage.id().value(), "PENDING", mealUsage.amount(), mealUsage.createdAt()
            );
        }
    }

    record PublicRequestResponse(UUID mealUsageId, String status, long amountMinor, Instant createdAt) {
        static PublicRequestResponse from(MealUsage mealUsage) {
            return new PublicRequestResponse(
                mealUsage.id().value(), mealUsage.status().name(), mealUsage.amount(), mealUsage.createdAt()
            );
        }
    }

    private PublicMealUsageRequestUseCase.RequestCommand requestCommand(
        String token,
        String rawMealUsageId,
        String rawIdempotencyKey,
        String rawRequestKey
    ) {
        if (rawIdempotencyKey == null || rawRequestKey == null) {
            throw new com.tieat.qr.application.PublicMealUsageQrNotFoundException();
        }
        try {
            return new PublicMealUsageRequestUseCase.RequestCommand(
                token,
                UUID.fromString(rawIdempotencyKey),
                new MealUsageId(UUID.fromString(rawMealUsageId)),
                rawRequestKey
            );
        } catch (IllegalArgumentException exception) {
            throw new com.tieat.qr.application.PublicMealUsageQrNotFoundException();
        }
    }
}
