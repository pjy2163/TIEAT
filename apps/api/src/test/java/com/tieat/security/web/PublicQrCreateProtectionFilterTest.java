package com.tieat.security.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tieat.config.PublicQrCreateProtectionProperties;
import com.tieat.security.application.RateLimiter;
import com.tieat.web.ProblemDetailFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

@ExtendWith(OutputCaptureExtension.class)
class PublicQrCreateProtectionFilterTest {

    @Test
    void killSwitchRejectsBeforeRateLimitOrController() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        ProblemDetailFactory problemDetailFactory = mock(ProblemDetailFactory.class);
        PublicQrCreateProtectionFilter filter = filter(false, rateLimiter, problemDetailFactory);
        MockHttpServletRequest request = publicCreateRequest("sensitive-token", "198.51.100.20");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));

        assertThat(continued).isFalse();
        verifyNoInteractions(rateLimiter);
        verify(problemDetailFactory).write(
            eq(request),
            eq(response),
            eq(HttpStatus.SERVICE_UNAVAILABLE),
            eq("PUBLIC_QR_CREATION_DISABLED"),
            eq("Public QR creation is temporarily unavailable")
        );
    }

    @Test
    void rateLimitLogAndResponseDoNotExposeClientOrToken(CapturedOutput output) throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        ProblemDetailFactory problemDetailFactory = mock(ProblemDetailFactory.class);
        when(rateLimiter.consume(any(), eq(15), eq(Duration.ofMinutes(1))))
            .thenReturn(RateLimiter.Decision.limited(Duration.ofSeconds(30)));
        PublicQrCreateProtectionFilter filter = filter(true, rateLimiter, problemDetailFactory);
        MockHttpServletRequest request = publicCreateRequest("sensitive-token", "198.51.100.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        verify(problemDetailFactory).writeRateLimited(request, response, 30);
        verify(problemDetailFactory, never()).write(
            any(), any(), any(), eq("PUBLIC_QR_CREATION_DISABLED"), any()
        );
        assertThat(output).contains("security_event=public_qr_create_rate_limited retryAfterSeconds=30")
            .doesNotContain("198.51.100.20", "sensitive-token");
    }

    private PublicQrCreateProtectionFilter filter(
        boolean enabled,
        RateLimiter rateLimiter,
        ProblemDetailFactory problemDetailFactory
    ) {
        return new PublicQrCreateProtectionFilter(
            rateLimiter,
            problemDetailFactory,
            new PublicQrCreateProtectionProperties(enabled, 15, List.of())
        );
    }

    private MockHttpServletRequest publicCreateRequest(String token, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest(new MockServletContext());
        request.setMethod("POST");
        request.setRequestURI("/api/v1/public/meal-usage-qr/" + token + "/meal-usages");
        request.setRemoteAddr(remoteAddress);
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
}
