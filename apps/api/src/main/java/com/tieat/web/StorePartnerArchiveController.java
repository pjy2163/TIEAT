package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.partnership.application.StorePartnerArchivePinRequiredException;
import com.tieat.partnership.application.StorePartnerService;
import com.tieat.partnership.domain.MealContractId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.tieat.web.StorePartnerHttpModels.ArchivePinRequest;
import static com.tieat.web.StorePartnerHttpModels.ArchivePinResponse;
import static com.tieat.web.StorePartnerHttpModels.ArchivePinSettingsRequest;

@RestController
@RequestMapping("/api/v1")
class StorePartnerArchiveController {

    private final StorePartnerService storePartnerService;
    private final RecentPasswordAuthenticationGuard recentPasswordAuthenticationGuard;

    StorePartnerArchiveController(
        StorePartnerService storePartnerService,
        RecentPasswordAuthenticationGuard recentPasswordAuthenticationGuard
    ) {
        this.storePartnerService = Objects.requireNonNull(storePartnerService);
        this.recentPasswordAuthenticationGuard = Objects.requireNonNull(recentPasswordAuthenticationGuard);
    }

    @Operation(summary = "Legacy archive endpoint denied; use the PIN-verified archive route")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Legacy archive is denied; use the PIN-verified route", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @DeleteMapping("/store-partners/{mealContractId}")
    ResponseEntity<Void> archive(
        @PathVariable UUID mealContractId,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        throw new StorePartnerArchivePinRequiredException();
    }

    @Operation(summary = "Archive a partner contract after PIN verification")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Archived"),
        @ApiResponse(responseCode = "403", description = "Recent password reauthentication and a valid archive PIN are required", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping("/store-partners/{mealContractId}/archive")
    ResponseEntity<Void> archiveWithPin(
        @PathVariable UUID mealContractId,
        @RequestBody ArchivePinRequest request,
        @AuthenticationPrincipal StoreAccountPrincipal principal,
        HttpServletRequest servletRequest
    ) {
        recentPasswordAuthenticationGuard.requireRecent(servletRequest.getSession(false));
        storePartnerService.archiveWithPin(
            principal.storeId(),
            new MealContractId(mealContractId),
            request.pin(),
            principal.getUsername()
        );
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Operation(summary = "Read whether the store archive PIN is configured")
    @SecurityRequirement(name = "sessionCookie")
    @GetMapping("/store-archive-pin")
    ResponseEntity<ArchivePinResponse> archivePinStatus(
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new ArchivePinResponse(storePartnerService.archivePinStatus(principal.storeId()).configured()));
    }

    @Operation(summary = "Set or change the store archive PIN")
    @SecurityRequirement(name = "sessionCookie")
    @PutMapping("/store-archive-pin")
    ResponseEntity<ArchivePinResponse> setArchivePin(
        @RequestBody ArchivePinSettingsRequest request,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        storePartnerService.setArchivePin(
            principal.storeId(),
            request.currentPin(),
            request.accountPassword(),
            request.newPin(),
            request.newPinConfirmation(),
            principal.getUsername()
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new ArchivePinResponse(true));
    }
}
