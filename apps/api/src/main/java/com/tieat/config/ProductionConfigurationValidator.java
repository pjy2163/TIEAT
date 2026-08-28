package com.tieat.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
public final class ProductionConfigurationValidator {

    private static final String DATASOURCE_URL = "spring.datasource.url";
    private static final String DATASOURCE_USERNAME = "spring.datasource.username";
    private static final String DATASOURCE_PASSWORD = "spring.datasource.password";
    private static final String SESSION_COOKIE_SECURE = "tieat.session.cookie.secure";
    private static final String QR_ENCRYPTION_REQUIRED = "tieat.qr.token-encryption-required";
    private static final String QR_ENCRYPTION_KEYS = "tieat.qr.token-encryption-keys";
    private static final String QR_LEGACY_ENCRYPTION_KEY = "tieat.qr.token-encryption-key";
    private static final String QR_ENCRYPTION_KEY_VERSION = "tieat.qr.token-encryption-key-version";
    private static final String RECEIPTS_PROVIDER = "tieat.receipts.provider";
    private static final String RECEIPTS_AZURE_ENDPOINT = "tieat.receipts.azure.endpoint";
    private static final String RECEIPTS_AZURE_CONTAINER = "tieat.receipts.azure.container";
    private static final Pattern LOOPBACK_POSTGRES_URL = Pattern.compile(
        "^jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]|\\[0:0:0:0:0:0:0:1\\])"
            + "(?::\\d+)?(?:/|\\?|,|$)",
        Pattern.CASE_INSENSITIVE
    );

    private final Environment environment;

    ProductionConfigurationValidator(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment must be supplied");
    }

    @PostConstruct
    void validate() {
        List<String> failures = new ArrayList<>();
        validateDatabase(failures);
        validateSessionCookie(failures);
        validateQrTokenEncryption(failures);
        validateReceiptStorage(failures);

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                "Production configuration validation failed: " + String.join("; ", failures)
            );
        }
    }

    private void validateDatabase(List<String> failures) {
        String url = property(DATASOURCE_URL);
        if (isMissing(url)) {
            failures.add(DATASOURCE_URL + " is required");
        } else if (isLoopbackPostgresUrl(url)) {
            failures.add(DATASOURCE_URL + " must not use a loopback host");
        }
        requireProperty(DATASOURCE_USERNAME, failures);

        String password = property(DATASOURCE_PASSWORD);
        if (isMissing(password)) {
            failures.add(DATASOURCE_PASSWORD + " is required");
        } else if ("tieat_local".equals(password.trim())) {
            failures.add(DATASOURCE_PASSWORD + " must not use the local fallback");
        }
    }

    private void validateSessionCookie(List<String> failures) {
        String secure = property(SESSION_COOKIE_SECURE);
        if (isMissing(secure)) {
            failures.add(SESSION_COOKIE_SECURE + " is required");
        } else if (!"true".equals(secure.trim().toLowerCase(Locale.ROOT))) {
            failures.add(SESSION_COOKIE_SECURE + " must be true");
        }
    }

    private void validateQrTokenEncryption(List<String> failures) {
        String required = property(QR_ENCRYPTION_REQUIRED);
        if (isMissing(required)) {
            failures.add(QR_ENCRYPTION_REQUIRED + " is required");
        } else if (!"true".equals(required.trim().toLowerCase(Locale.ROOT))) {
            failures.add(QR_ENCRYPTION_REQUIRED + " must be true");
        }

        String keyRing = property(QR_ENCRYPTION_KEYS);
        String legacyKey = property(QR_LEGACY_ENCRYPTION_KEY);
        if (isMissing(keyRing)) {
            if (!isMissing(legacyKey)) {
                failures.add(QR_ENCRYPTION_KEYS + " is required; " + QR_LEGACY_ENCRYPTION_KEY + " is not supported");
            } else {
                failures.add(QR_ENCRYPTION_KEYS + " is required");
            }
        }

        String activeVersion = property(QR_ENCRYPTION_KEY_VERSION);
        if (isMissing(activeVersion)) {
            failures.add(QR_ENCRYPTION_KEY_VERSION + " is required");
            return;
        }
        try {
            if (Integer.parseInt(activeVersion.trim()) <= 0) {
                failures.add(QR_ENCRYPTION_KEY_VERSION + " must be a positive integer");
            }
        } catch (NumberFormatException exception) {
            failures.add(QR_ENCRYPTION_KEY_VERSION + " must be a positive integer");
        }
    }

    private void validateReceiptStorage(List<String> failures) {
        String provider = property(RECEIPTS_PROVIDER);
        if (isMissing(provider)) {
            failures.add(RECEIPTS_PROVIDER + " is required");
        } else if (!"azure".equals(provider)) {
            failures.add(RECEIPTS_PROVIDER + " must be azure");
        }
        requireProperty(RECEIPTS_AZURE_ENDPOINT, failures);
        requireProperty(RECEIPTS_AZURE_CONTAINER, failures);
    }

    private void requireProperty(String propertyName, List<String> failures) {
        if (isMissing(property(propertyName))) {
            failures.add(propertyName + " is required");
        }
    }

    private String property(String propertyName) {
        try {
            return environment.getProperty(propertyName);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isMissing(String value) {
        if (value == null) {
            return true;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || (trimmed.startsWith("${") && trimmed.endsWith("}"));
    }

    private static boolean isLoopbackPostgresUrl(String value) {
        return LOOPBACK_POSTGRES_URL.matcher(value.trim()).find();
    }
}
