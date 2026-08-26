package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.identity.adapter.in.security.StoreAccountUserDetailsService;
import com.tieat.onboarding.application.StoreOnboardingUseCase;
import com.tieat.onboarding.application.StorePlaceSearchGateway;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.PartnerKind;
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
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PostMapping("/store-place-searches")
    ResponseEntity<StorePlaceSearchResponse> searchPlaces(@RequestBody StorePlaceSearchRequest request) {
        List<StorePlaceSearchItemResponse> entries = storeOnboardingUseCase.searchPlaces(
                request.inviteCode(), request.query()
            ).stream()
            .map(StorePlaceSearchItemResponse::from)
            .toList();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new StorePlaceSearchResponse("KAKAO", entries));
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
                request.partnerKind(),
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
                result.partnerKind() == null ? null : result.partnerKind().name(),
                result.paymentType() == null ? null : result.paymentType().name(),
                result.mealContractId()
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
        if (authentication instanceof CredentialsContainer credentialsContainer) {
            credentialsContainer.eraseCredentials();
        }
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
        String manualStoreName
    ) {
    }

    record SignupResponse(String onboardingStatus) {
    }

    record StorePlaceSearchRequest(String inviteCode, String query) {
    }

    record StorePlaceSearchResponse(String source, List<StorePlaceSearchItemResponse> items) {
    }

    record StorePlaceSearchItemResponse(
        String placeId,
        String storeDisplayName,
        String address,
        String category
    ) {

        static StorePlaceSearchItemResponse from(StorePlaceSearchGateway.PlaceSearchResult result) {
            return new StorePlaceSearchItemResponse(
                result.placeId(),
                result.storeDisplayName(),
                result.address(),
                result.category()
            );
        }
    }

    record OnboardingStatusResponse(String onboardingStatus, boolean legacy) {
    }

    record FirstPartnerRegistrationRequest(
        String partnerName,
        PartnerKind partnerKind,
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
        String partnerKind,
        String paymentType,
        UUID mealContractId
    ) {
    }
}
