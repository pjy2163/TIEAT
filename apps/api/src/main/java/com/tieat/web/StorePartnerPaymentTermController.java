package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.partnership.application.StorePartnerService;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.StorePartnerDirectoryEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.tieat.web.StorePartnerHttpModels.PaymentTermRequest;
import static com.tieat.web.StorePartnerHttpModels.StorePartnerResponse;
import static com.tieat.web.StorePartnerHttpModels.optionalPrepaidBalanceMinor;

@RestController
@RequestMapping("/api/v1")
class StorePartnerPaymentTermController {

    private final StorePartnerService storePartnerService;

    StorePartnerPaymentTermController(StorePartnerService storePartnerService) {
        this.storePartnerService = Objects.requireNonNull(storePartnerService);
    }

    @Operation(summary = "Change the payment term for a partner contract")
    @SecurityRequirement(name = "sessionCookie")
    @PatchMapping("/store-partners/{mealContractId}/payment-terms")
    ResponseEntity<StorePartnerResponse> updatePaymentType(
        @PathVariable UUID mealContractId,
        @RequestBody PaymentTermRequest request,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        StorePartnerDirectoryEntry result = storePartnerService.updatePaymentType(
            principal.storeId(),
            new MealContractId(mealContractId),
            request.expectedPaymentType(),
            request.paymentType(),
            optionalPrepaidBalanceMinor(request.prepaidBalanceMinor()),
            principal.getUsername()
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(StorePartnerResponse.from(result));
    }
}
