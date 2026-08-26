package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.qr.application.GetStoreMealUsageQrViewUseCase;
import com.tieat.qr.application.GetStoreMealUsageQrViewUseCase.StoreMealUsageQrView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store-meal-usage-qr")
class StoreMealUsageQrController {

    private final GetStoreMealUsageQrViewUseCase getStoreMealUsageQrViewUseCase;

    StoreMealUsageQrController(GetStoreMealUsageQrViewUseCase getStoreMealUsageQrViewUseCase) {
        this.getStoreMealUsageQrViewUseCase = getStoreMealUsageQrViewUseCase;
    }

    @Operation(summary = "Read the authenticated store's current meal usage QR view")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Current QR view", content = @Content(schema = @Schema(implementation = StoreMealUsageQrResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping
    ResponseEntity<StoreMealUsageQrResponse> get(@AuthenticationPrincipal StoreAccountPrincipal principal) {
        StoreMealUsageQrView view = getStoreMealUsageQrViewUseCase.get(principal.storeId());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(StoreMealUsageQrResponse.from(view));
    }

    record StoreMealUsageQrResponse(
        StoreMealUsageQrView.Status status,
        String publicPath,
        Instant issuedAt,
        Instant expiresAt
    ) {

        static StoreMealUsageQrResponse from(StoreMealUsageQrView view) {
            return new StoreMealUsageQrResponse(view.status(), view.publicPath(), view.issuedAt(), view.expiresAt());
        }
    }
}
