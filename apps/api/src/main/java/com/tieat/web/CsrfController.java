package com.tieat.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {

    @GetMapping("/api/v1/csrf")
    ResponseEntity<CsrfTokenResponse> csrf(@RequestAttribute("_csrf") CsrfToken csrfToken) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new CsrfTokenResponse(csrfToken.getToken(), csrfToken.getHeaderName(), csrfToken.getParameterName()));
    }

    record CsrfTokenResponse(String token, String headerName, String parameterName) {
    }
}
