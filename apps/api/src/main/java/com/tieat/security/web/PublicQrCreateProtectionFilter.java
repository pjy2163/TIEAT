package com.tieat.security.web;

import com.tieat.config.PublicQrCreateProtectionProperties;
import com.tieat.security.application.RateLimiter;
import com.tieat.web.ProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class PublicQrCreateProtectionFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Logger log = LoggerFactory.getLogger(PublicQrCreateProtectionFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final PathPatternRequestMatcher CREATE_REQUEST = PathPatternRequestMatcher.pathPattern(
        HttpMethod.POST, "/api/v1/public/meal-usage-qr/{token}/meal-usages"
    );

    private final RateLimiter rateLimiter;
    private final ProblemDetailFactory problemDetailFactory;
    private final PublicQrCreateProtectionProperties properties;
    private final ClientIpResolver clientIpResolver;

    public PublicQrCreateProtectionFilter(
        RateLimiter rateLimiter,
        ProblemDetailFactory problemDetailFactory,
        PublicQrCreateProtectionProperties properties
    ) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.problemDetailFactory = Objects.requireNonNull(problemDetailFactory);
        this.properties = Objects.requireNonNull(properties);
        this.clientIpResolver = new ClientIpResolver(properties.trustedProxyCidrs());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CREATE_REQUEST.matches(request);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.enabled()) {
            log.warn("security_event=public_qr_create_disabled");
            problemDetailFactory.write(
                request,
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                "PUBLIC_QR_CREATION_DISABLED",
                "Public QR creation is temporarily unavailable"
            );
            return;
        }

        RateLimiter.Decision decision = rateLimiter.consume(
            RateLimitKeys.publicQrCreateIp(clientIpResolver.resolve(request)),
            properties.requestsPerMinute(),
            WINDOW
        );
        if (!decision.allowed()) {
            log.warn(
                "security_event=public_qr_create_rate_limited retryAfterSeconds={}",
                decision.retryAfter().toSeconds()
            );
            problemDetailFactory.writeRateLimited(request, response, decision.retryAfter().toSeconds());
            return;
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            writeBodyTooLarge(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            writeBodyTooLarge(request, response);
            return;
        }
        filterChain.doFilter(new BufferedRequest(request, body), response);
    }

    private void writeBodyTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problemDetailFactory.write(
            request,
            response,
            HttpStatus.CONTENT_TOO_LARGE,
            "REQUEST_BODY_TOO_LARGE",
            "Request body is too large"
        );
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Async request body reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
