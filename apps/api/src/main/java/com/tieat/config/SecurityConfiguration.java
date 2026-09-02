package com.tieat.config;

import com.tieat.security.application.RateLimiter;
import com.tieat.security.web.PublicQrCreateProtectionFilter;
import com.tieat.security.web.RateLimitFilter;
import com.tieat.security.web.RateLimitKeys;
import com.tieat.web.ProblemDetailFactory;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(PublicQrCreateProtectionProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AuthenticationProvider storeAccountAuthenticationProvider,
        SessionAuthenticationStrategy sessionAuthenticationStrategy,
        SecurityContextRepository securityContextRepository,
        Clock clock,
        ProblemDetailFactory problemDetailFactory,
        RateLimiter rateLimiter,
        PublicQrCreateProtectionProperties publicQrCreateProtectionProperties
    ) throws Exception {
        return http
            .authenticationProvider(storeAccountAuthenticationProvider)
            .securityContext(securityContext -> securityContext.securityContextRepository(securityContextRepository))
            .addFilterBefore(new RememberedSessionExpiryFilter(clock), SecurityContextHolderFilter.class)
            .addFilterBefore(
                new PublicQrCreateProtectionFilter(
                    rateLimiter, problemDetailFactory, publicQrCreateProtectionProperties
                ),
                UsernamePasswordAuthenticationFilter.class
            )
            .addFilterBefore(new RateLimitFilter(rateLimiter, problemDetailFactory), UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf
                .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, "/api/v1/public/meal-usage-qr/{token}/meal-usages"
                ), PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, "/api/v1/public/meal-usage-qr/{token}/meal-usages/{mealUsageId}/cancellations"
                ))
            )
            .sessionManagement(session -> session.sessionAuthenticationStrategy(sessionAuthenticationStrategy))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/v1/csrf", "/api/v1/sessions").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/store-place-searches").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/store-signups").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/session-reauthentications").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/public/meal-usage-qr/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/public/meal-usage-qr/*/meal-usages").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/meal-usage-qr/*/meal-usages/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/public/meal-usage-qr/*/meal-usages/*/cancellations").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/meal-usages").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/meal-usages").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/meal-usages/months/*").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/meal-usages/confirmed").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/meal-usages/confirmed/export").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/store-meal-usage-qr").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.POST, "/api/v1/store-meal-usage-qr/renewals").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.POST, "/api/v1/store-meal-usage-qr/request-pauses").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/store-meal-usage-qr/request-pauses").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/store-partners").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.POST, "/api/v1/store-partners").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/store-partners/*/payment-terms").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.POST, "/api/v1/store-partners/*/archive").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/store-partners/*").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/store-archive-pin").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.PUT, "/api/v1/store-archive-pin").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/store-profile").hasRole("STORE_STAFF")
                .requestMatchers("/api/v1/meal-usages/*/confirmations").hasRole("STORE_STAFF")
                .requestMatchers("/api/v1/meal-usages/*/rejections").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/pos-settlements").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/pos-settlements/receivables").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.POST, "/api/v1/pos-settlements").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.GET, "/api/v1/pos-settlements/*/receipt").hasRole("STORE_STAFF")
                .requestMatchers(HttpMethod.POST, "/api/v1/pos-settlements/*/receipt").hasRole("STORE_STAFF")
                .anyRequest().authenticated()
            )
            .formLogin(login -> login
                .loginProcessingUrl("/api/v1/sessions")
                .usernameParameter("loginId")
                .passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    rateLimiter.clear(List.of(RateLimitKeys.loginIdentity(authentication.getName())));
                    response.setStatus(HttpStatus.NO_CONTENT.value());
                })
                .failureHandler((request, response, exception) -> {
                    var decision = rateLimiter.recordFailure(
                        RateLimitKeys.login(request.getRemoteAddr(), request.getParameter("loginId"))
                    );
                    if (!decision.allowed()) {
                        problemDetailFactory.writeRateLimited(request, response, decision.retryAfter().toSeconds());
                        return;
                    }
                    problemDetailFactory.write(
                        request, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Authentication failed"
                    );
                })
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/api/v1/sessions/logout")
                .addLogoutHandler(new CookieClearingLogoutHandler("TIEAT_SESSION"))
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(HttpStatus.NO_CONTENT.value());
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                })
                .permitAll()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint(problemDetailFactory))
                .accessDeniedHandler(accessDeniedHandler(problemDetailFactory))
            )
            .build();
    }

    @Bean
    AuthenticationProvider storeAccountAuthenticationProvider(
        UserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    private AuthenticationEntryPoint authenticationEntryPoint(ProblemDetailFactory problemDetailFactory) {
        return (request, response, exception) -> problemDetailFactory.write(
            request,
            response,
            HttpStatus.UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "Authentication is required"
        );
    }

    private AccessDeniedHandler accessDeniedHandler(ProblemDetailFactory problemDetailFactory) {
        return (request, response, exception) -> {
            if (hasCause(exception, InvalidCsrfTokenException.class) || hasCause(exception, MissingCsrfTokenException.class)) {
                if (!"/api/v1/sessions".equals(request.getRequestURI())
                    && !"/api/v1/store-signups".equals(request.getRequestURI())
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                    problemDetailFactory.write(
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED",
                        "Authentication is required"
                    );
                    return;
                }
                problemDetailFactory.write(
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    "CSRF_TOKEN_INVALID",
                    "CSRF token is missing or invalid"
                );
                return;
            }
            problemDetailFactory.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "Access is denied"
            );
        };
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> causeType) {
        Throwable current = exception;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
