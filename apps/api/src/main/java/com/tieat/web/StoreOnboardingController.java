package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.identity.adapter.in.security.StoreAccountUserDetailsService;
import com.tieat.onboarding.application.StoreOnboardingUseCase;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.store.domain.StoreCatalogEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class StoreOnboardingController {

    private final StoreOnboardingUseCase storeOnboardingUseCase;
    private final StoreAccountUserDetailsService storeAccountUserDetailsService;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;

    StoreOnboardingController(
        StoreOnboardingUseCase storeOnboardingUseCase,
        StoreAccountUserDetailsService storeAccountUserDetailsService,
        SessionAuthenticationStrategy sessionAuthenticationStrategy,
        SecurityContextRepository securityContextRepository
    ) {
        this.storeOnboardingUseCase = Objects.requireNonNull(storeOnboardingUseCase);
        this.storeAccountUserDetailsService = Objects.requireNonNull(storeAccountUserDetailsService);
        this.sessionAuthenticationStrategy = Objects.requireNonNull(sessionAuthenticationStrategy);
        this.securityContextRepository = Objects.requireNonNull(securityContextRepository);
    }

    @GetMapping("/store-catalog")
    ResponseEntity<StoreCatalogResponse> searchCatalog(@RequestParam String query) {
        List<StoreCatalogEntryResponse> entries = storeOnboardingUseCase.searchCatalog(query).stream()
            .map(StoreCatalogEntryResponse::from)
            .toList();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new StoreCatalogResponse(entries));
    }

    @PostMapping("/store-signups")
    ResponseEntity<SignupResponse> signUp(
        @RequestBody SignupRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        StoreOnboardingUseCase.SignupResult result = storeOnboardingUseCase.signUp(
            new StoreOnboardingUseCase.StoreSignupCommand(
                request.inviteCode(),
                request.loginId(),
                request.password(),
                request.catalogEntryId(),
                request.manualStoreName()
            )
        );
        establishAuthenticatedSession(result.loginId(), servletRequest, servletResponse);
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(new SignupResponse(result.onboardingStatus().name()));
    }

    @GetMapping("/store-onboarding")
    ResponseEntity<OnboardingStatusResponse> onboardingStatus(
        @org.springframework.security.core.annotation.AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        StoreOnboardingUseCase.OnboardingStatus status = storeOnboardingUseCase.currentStatus(principal.storeId());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new OnboardingStatusResponse(status.status().name(), status.legacy()));
    }

    @PostMapping("/store-onboarding/partners")
    ResponseEntity<PartnerRegistrationResponse> registerFirstPartner(
        @RequestBody FirstPartnerRegistrationRequest request,
        @org.springframework.security.core.annotation.AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        StoreOnboardingUseCase.PartnerRegistrationResult result = storeOnboardingUseCase.registerFirstPartner(
            new StoreOnboardingUseCase.FirstPartnerRegistrationCommand(
                principal.storeId(),
                request.partnerName(),
                request.paymentType(),
                request.initialPrepaidBalanceMinor(),
                request.qrSelectable()
            )
        );
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .body(new PartnerRegistrationResponse(
                result.onboardingStatus().name(),
                result.created(),
                result.legacy(),
                result.partnerDisplayName(),
                result.paymentType() == null ? null : result.paymentType().name()
            ));
    }

    private void establishAuthenticatedSession(
        String loginId,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        UserDetails userDetails = storeAccountUserDetailsService.loadUserByUsername(loginId);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            userDetails,
            null,
            userDetails.getAuthorities()
        );
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    record SignupRequest(
        String inviteCode,
        String loginId,
        String password,
        UUID catalogEntryId,
        String manualStoreName
    ) {
    }

    record SignupResponse(String onboardingStatus) {
    }

    record StoreCatalogResponse(List<StoreCatalogEntryResponse> items) {
    }

    record StoreCatalogEntryResponse(
        UUID catalogEntryId,
        String storeDisplayName,
        String brandDisplayName,
        String logoPath
    ) {

        static StoreCatalogEntryResponse from(StoreCatalogEntry catalogEntry) {
            return new StoreCatalogEntryResponse(
                catalogEntry.id(),
                catalogEntry.storeDisplayName(),
                catalogEntry.brandDisplayName(),
                catalogEntry.logoPath().orElse(null)
            );
        }
    }

    record OnboardingStatusResponse(String onboardingStatus, boolean legacy) {
    }

    record FirstPartnerRegistrationRequest(
        String partnerName,
        MealContractPaymentType paymentType,
        Long initialPrepaidBalanceMinor,
        Boolean qrSelectable
    ) {
    }

    record PartnerRegistrationResponse(
        String onboardingStatus,
        boolean created,
        boolean legacy,
        String partnerDisplayName,
        String paymentType
    ) {
    }
}
