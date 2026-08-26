package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.partnership.application.StorePartnerService;
import com.tieat.partnership.domain.StorePartnerDirectoryEntry;
import com.tieat.store.application.StoreProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import static com.tieat.web.StorePartnerHttpModels.initialPrepaidBalanceMinor;
import static com.tieat.web.StorePartnerHttpModels.StorePartnerRequest;
import static com.tieat.web.StorePartnerHttpModels.StorePartnerResponse;

@RestController
@RequestMapping("/api/v1")
class StorePartnerDirectoryController {

    private final StorePartnerService storePartnerService;
    private final StoreProfileService storeProfileService;

    StorePartnerDirectoryController(
        StorePartnerService storePartnerService,
        StoreProfileService storeProfileService
    ) {
        this.storePartnerService = Objects.requireNonNull(storePartnerService);
        this.storeProfileService = Objects.requireNonNull(storeProfileService);
    }

    @Operation(summary = "List partner contracts for the authenticated store")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Store partner directory", content = @Content(schema = @Schema(implementation = StorePartnerResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/store-partners")
    ResponseEntity<List<StorePartnerResponse>> list(@AuthenticationPrincipal StoreAccountPrincipal principal) {
        List<StorePartnerResponse> entries = storePartnerService.list(principal.storeId()).stream()
            .map(StorePartnerResponse::from)
            .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(entries);
    }

    @Operation(summary = "Create a partner contract for the authenticated store")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created or exact idempotent replay", content = @Content(schema = @Schema(implementation = StorePartnerResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied or invalid CSRF token", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different request", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping("/store-partners")
    ResponseEntity<StorePartnerResponse> create(
        @RequestHeader("Idempotency-Key") UUID idempotencyKey,
        @RequestBody StorePartnerRequest request,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        StorePartnerDirectoryEntry result = storePartnerService.create(new StorePartnerService.CreateStorePartnerCommand(
            principal.storeId(),
            idempotencyKey,
            request.partnerName(),
            request.partnerKind(),
            request.paymentType(),
            initialPrepaidBalanceMinor(request.initialPrepaidBalanceMinor()),
            request.qrSelectable(),
            request.representativePhone(),
            request.representativeEmail()
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(StorePartnerResponse.from(result));
    }

    @Operation(summary = "Read minimal account and store display information")
    @SecurityRequirement(name = "sessionCookie")
    @GetMapping("/store-profile")
    ResponseEntity<StorePartnerHttpModels.StoreProfileResponse> profile(
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        StoreProfileService.StoreProfile profile = storeProfileService.profile(principal.storeId(), principal.getUsername());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new StorePartnerHttpModels.StoreProfileResponse(profile.loginId(), profile.storeDisplayName()));
    }
}
