package com.tieat.web;

import com.tieat.config.RememberedSessionPolicy;
import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.identity.application.SessionReauthenticationFailedException;
import com.tieat.identity.application.SessionReauthenticationInvalidException;
import com.tieat.identity.application.SessionReauthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SessionReauthenticationController {

    private final SessionReauthenticationService service;
    private final ProblemDetailFactory problemDetailFactory;

    SessionReauthenticationController(
        SessionReauthenticationService service,
        ProblemDetailFactory problemDetailFactory
    ) {
        this.service = Objects.requireNonNull(service);
        this.problemDetailFactory = Objects.requireNonNull(problemDetailFactory);
    }

    @PostMapping("/session-reauthentications")
    ResponseEntity<?> reauthenticate(
        @RequestBody ReauthenticationRequest request,
        @AuthenticationPrincipal StoreAccountPrincipal principal,
        HttpServletRequest servletRequest
    ) {
        if (request == null || request.password() == null) {
            throw new SessionReauthenticationInvalidException();
        }
        if (principal == null) {
            return sessionUnavailable(servletRequest);
        }
        HttpSession session = servletRequest.getSession(false);
        String sessionId = session == null ? null : session.getId();
        SessionReauthenticationService.Result result = service.reauthenticate(
            sessionId,
            principal.loginId(),
            principal.storeId(),
            request.password()
        );
        switch (result.status()) {
            case FAILED -> throw new SessionReauthenticationFailedException();
            case SESSION_UNAVAILABLE -> { return sessionUnavailable(servletRequest); }
            case SUCCESS -> {
                if (result.remainingCookieMaxAgeSeconds() == null) {
                    servletRequest.removeAttribute(RememberedSessionPolicy.COOKIE_MAX_AGE_ATTRIBUTE);
                } else {
                    servletRequest.setAttribute(
                        RememberedSessionPolicy.COOKIE_MAX_AGE_ATTRIBUTE,
                        result.remainingCookieMaxAgeSeconds()
                    );
                }
                servletRequest.changeSessionId();
                return ResponseEntity.noContent()
                    .cacheControl(CacheControl.noStore())
                    .build();
            }
        }
        throw new IllegalStateException("Unknown reauthentication result");
    }

    private ResponseEntity<ProblemDetail> sessionUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .cacheControl(CacheControl.noStore())
            .body(problemDetailFactory.create(
                request,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Authentication is required"
            ));
    }

    record ReauthenticationRequest(String password) {
    }
}
