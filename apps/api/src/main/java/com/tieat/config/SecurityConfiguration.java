package com.tieat.config;

import com.tieat.web.ProblemDetailFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AuthenticationProvider storeAccountAuthenticationProvider,
        ProblemDetailFactory problemDetailFactory
    ) throws Exception {
        return http
            .authenticationProvider(storeAccountAuthenticationProvider)
            .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
            .sessionManagement(session -> session.sessionAuthenticationStrategy(
                new ChangeSessionIdAuthenticationStrategy()
            ))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/info", "/api/v1/csrf", "/api/v1/sessions", "/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/meal-usages").hasRole("STORE_STAFF")
                .requestMatchers("/api/v1/meal-usages/*/confirmations").hasRole("STORE_STAFF")
                .anyRequest().authenticated()
            )
            .formLogin(login -> login
                .loginProcessingUrl("/api/v1/sessions")
                .usernameParameter("loginId")
                .passwordParameter("password")
                .successHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value()))
                .failureHandler((request, response, exception) -> problemDetailFactory.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_FAILED",
                    "Authentication failed"
                ))
                .permitAll()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint(problemDetailFactory))
                .accessDeniedHandler(accessDeniedHandler(problemDetailFactory))
            )
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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
