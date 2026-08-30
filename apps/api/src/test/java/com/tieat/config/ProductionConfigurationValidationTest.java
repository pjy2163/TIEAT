package com.tieat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidationTest {

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

    private static final String QR_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String QR_KEY_RING = "1:" + QR_KEY;

    @Test
    void productionProfileActivatesValidatorAndAcceptsSafeEffectiveConfiguration() {
        new ApplicationContextRunner()
            .withUserConfiguration(ValidatorTestConfiguration.class)
            .withPropertyValues(propertyValues("production", safeProperties()))
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).hasSingleBean(ProductionConfigurationValidator.class);
            });
    }

    @Test
    void noExplicitProfileUsesProductionConfigDataAndResolvesExplicitEnvironmentBindings() {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ValidatorTestConfiguration.class)
            .withSystemProperties(
                "spring.profiles.active=",
                "DB_URL=jdbc:postgresql://config-data.example:5432/tieat",
                "DB_USERNAME=tieat-config-data",
                "DB_PASSWORD=fake-config-data-password",
                "TIEAT_QR_TOKEN_ENCRYPTION_KEYS=" + QR_KEY_RING,
                "TIEAT_QR_TOKEN_ENCRYPTION_KEY_VERSION=1",
                "TIEAT_RECEIPTS_PROVIDER=azure",
                "TIEAT_RECEIPTS_AZURE_ENDPOINT=https://config-data-receipts.invalid",
                "TIEAT_RECEIPTS_AZURE_CONTAINER=config-data-receipts"
            )
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).hasSingleBean(ProductionConfigurationValidator.class);

                Environment environment = context.getEnvironment();
                assertThat(environment.getActiveProfiles()).isEmpty();
                assertThat(environment.getDefaultProfiles()).containsExactly("production");
                assertThat(environment.getProperty(DATASOURCE_URL))
                    .isEqualTo("jdbc:postgresql://config-data.example:5432/tieat");
                assertThat(environment.getProperty(DATASOURCE_USERNAME)).isEqualTo("tieat-config-data");
                assertThat(environment.getProperty(SESSION_COOKIE_SECURE)).isEqualTo("true");
                assertThat(environment.getProperty(QR_ENCRYPTION_REQUIRED)).isEqualTo("true");
                assertThat(environment.getProperty(QR_ENCRYPTION_KEYS)).isEqualTo(QR_KEY_RING);
                assertThat(environment.getProperty(QR_ENCRYPTION_KEY_VERSION)).isEqualTo("1");
                assertThat(environment.getProperty(RECEIPTS_PROVIDER)).isEqualTo("azure");
                assertThat(environment.getProperty(RECEIPTS_AZURE_ENDPOINT))
                    .isEqualTo("https://config-data-receipts.invalid");
                assertThat(environment.getProperty(RECEIPTS_AZURE_CONTAINER)).isEqualTo("config-data-receipts");
            });
    }

    @Test
    void noExplicitProfileRejectsMissingRequiredProductionBinding() {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ValidatorTestConfiguration.class)
            .withSystemProperties(
                "spring.profiles.active=",
                "DB_URL=jdbc:postgresql://config-data.example:5432/tieat",
                "DB_USERNAME=tieat-config-data",
                "DB_PASSWORD=",
                "TIEAT_QR_TOKEN_ENCRYPTION_KEYS=" + QR_KEY_RING,
                "TIEAT_QR_TOKEN_ENCRYPTION_KEY_VERSION=1",
                "TIEAT_RECEIPTS_PROVIDER=azure",
                "TIEAT_RECEIPTS_AZURE_ENDPOINT=https://config-data-receipts.invalid",
                "TIEAT_RECEIPTS_AZURE_CONTAINER=config-data-receipts"
            )
            .run(context -> {
                Throwable startupFailure = context.getStartupFailure();
                assertThat(startupFailure).isNotNull();
                assertThat(rootCause(startupFailure).getMessage()).contains(DATASOURCE_PASSWORD);
            });
    }

    @Test
    void productionProfileFailsDuringContextInitializationForUnsafeEffectiveConfiguration() {
        Map<String, Object> properties = safeProperties();
        properties.put(SESSION_COOKIE_SECURE, "false");

        new ApplicationContextRunner()
            .withUserConfiguration(ValidatorTestConfiguration.class)
            .withPropertyValues(propertyValues("production", properties))
            .run(context -> {
                Throwable startupFailure = context.getStartupFailure();
                assertThat(startupFailure).isNotNull();
                assertThat(rootCause(startupFailure).getMessage()).contains(SESSION_COOKIE_SECURE);
            });
    }

    @Test
    void noProfileAndTestProfileKeepValidatorInactiveWithLocalConfiguration() {
        new ApplicationContextRunner()
            .withUserConfiguration(ValidatorTestConfiguration.class)
            .withSystemProperties("spring.profiles.active=")
            .withPropertyValues(propertyValues(null, localProperties()))
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).doesNotHaveBean(ProductionConfigurationValidator.class);
            });

        new ApplicationContextRunner()
            .withUserConfiguration(ValidatorTestConfiguration.class)
            .withPropertyValues(propertyValues("test", localProperties()))
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).doesNotHaveBean(ProductionConfigurationValidator.class);
            });
    }

    @ParameterizedTest(name = "missing {0}")
    @MethodSource("requiredProperties")
    void rejectsMissingRequiredProperties(String propertyName) {
        assertValidationFails(validatorWithout(propertyName), propertyName);
    }

    @ParameterizedTest(name = "blank {0}")
    @MethodSource("requiredProperties")
    void rejectsBlankRequiredProperties(String propertyName) {
        assertValidationFails(validatorWith(Map.of(propertyName, " \t ")), propertyName);
    }

    @ParameterizedTest(name = "loopback URL {0}")
    @ValueSource(strings = {
        "jdbc:postgresql://localhost:5432/tieat",
        "jdbc:postgresql://127.0.0.1:5432/tieat",
        "jdbc:postgresql://[::1]:5432/tieat",
        "jdbc:postgresql://[0:0:0:0:0:0:0:1]:5432/tieat"
    })
    void rejectsLoopbackDatabaseUrls(String url) {
        assertValidationFails(validatorWith(Map.of(DATASOURCE_URL, url)), DATASOURCE_URL);
    }

    @Test
    void rejectsTheLocalDatabasePasswordFallback() {
        assertValidationFails(validatorWith(Map.of(DATASOURCE_PASSWORD, "tieat_local")), DATASOURCE_PASSWORD);
    }

    @Test
    void rejectsFalseSessionCookieSecure() {
        assertValidationFails(validatorWith(Map.of(SESSION_COOKIE_SECURE, "false")), SESSION_COOKIE_SECURE);
    }

    @Test
    void rejectsInvalidSessionCookieSecure() {
        assertValidationFails(validatorWith(Map.of(SESSION_COOKIE_SECURE, "tru")), SESSION_COOKIE_SECURE);
    }

    @Test
    void rejectsDisabledQrTokenEncryption() {
        assertValidationFails(validatorWith(Map.of(QR_ENCRYPTION_REQUIRED, "false")), QR_ENCRYPTION_REQUIRED);
    }

    @Test
    void rejectsLegacySingleKeyOnlyQrConfiguration() {
        assertValidationFails(
            validatorWith(Map.of(QR_ENCRYPTION_KEYS, " ", QR_LEGACY_ENCRYPTION_KEY, QR_KEY)),
            QR_ENCRYPTION_KEYS
        );
        String message = validationFailureMessage(
            validatorWith(Map.of(QR_ENCRYPTION_KEYS, " ", QR_LEGACY_ENCRYPTION_KEY, QR_KEY))
        );
        assertThat(message).contains(QR_LEGACY_ENCRYPTION_KEY);
    }

    @ParameterizedTest(name = "invalid active QR key version {0}")
    @ValueSource(strings = { "0", "-1", "not-a-number", "2147483648" })
    void rejectsInvalidOrNonPositiveActiveQrKeyVersions(String version) {
        assertValidationFails(validatorWith(Map.of(QR_ENCRYPTION_KEY_VERSION, version)), QR_ENCRYPTION_KEY_VERSION);
    }

    @ParameterizedTest(name = "invalid receipt provider {0}")
    @ValueSource(strings = { "local", "AZURE", "s3" })
    void rejectsNonAzureReceiptProviders(String provider) {
        assertValidationFails(validatorWith(Map.of(RECEIPTS_PROVIDER, provider)), RECEIPTS_PROVIDER);
    }

    @Test
    void rejectsUnsafeEffectiveOverridesWithHigherPropertySourcePrecedence() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("safe", safeProperties()));
        environment.getPropertySources().addFirst(new MapPropertySource(
            "override",
            Map.of(SESSION_COOKIE_SECURE, "false", RECEIPTS_PROVIDER, "local")
        ));

        String message = validationFailureMessage(new ProductionConfigurationValidator(environment));

        assertThat(message)
            .contains(SESSION_COOKIE_SECURE)
            .contains(RECEIPTS_PROVIDER);
    }

    @Test
    void failureMessageDoesNotEchoDatabasePasswordQrKeyOrAzureEndpoint() {
        String fakeDatabasePassword = "fake-db-password-must-not-appear";
        String fakeQrKey = "fake-qr-key-must-not-appear";
        String fakeAzureEndpoint = "https://fake-endpoint-must-not-appear.example";
        Map<String, Object> properties = safeProperties();
        properties.put(DATASOURCE_PASSWORD, fakeDatabasePassword);
        properties.put(QR_ENCRYPTION_KEYS, fakeQrKey);
        properties.put(RECEIPTS_AZURE_ENDPOINT, fakeAzureEndpoint);
        properties.put(SESSION_COOKIE_SECURE, "false");
        properties.put(RECEIPTS_PROVIDER, "local");

        String message = validationFailureMessage(validatorFor(properties));

        assertThat(message)
            .contains(SESSION_COOKIE_SECURE)
            .contains(RECEIPTS_PROVIDER)
            .doesNotContain(fakeDatabasePassword)
            .doesNotContain(fakeQrKey)
            .doesNotContain(fakeAzureEndpoint);
    }

    private static Stream<String> requiredProperties() {
        return Stream.of(
            DATASOURCE_URL,
            DATASOURCE_USERNAME,
            DATASOURCE_PASSWORD,
            SESSION_COOKIE_SECURE,
            QR_ENCRYPTION_REQUIRED,
            QR_ENCRYPTION_KEYS,
            QR_ENCRYPTION_KEY_VERSION,
            RECEIPTS_PROVIDER,
            RECEIPTS_AZURE_ENDPOINT,
            RECEIPTS_AZURE_CONTAINER
        );
    }

    private static ProductionConfigurationValidator validatorWithout(String propertyName) {
        Map<String, Object> properties = safeProperties();
        properties.remove(propertyName);
        return validatorFor(properties);
    }

    private static ProductionConfigurationValidator validatorWith(Map<String, Object> overrides) {
        Map<String, Object> properties = safeProperties();
        overrides.forEach((propertyName, value) -> {
            if (value == null) {
                properties.remove(propertyName);
            } else {
                properties.put(propertyName, value);
            }
        });
        return validatorFor(properties);
    }

    private static ProductionConfigurationValidator validatorFor(Map<String, Object> properties) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("effective", properties));
        return new ProductionConfigurationValidator(environment);
    }

    private static String validationFailureMessage(ProductionConfigurationValidator validator) {
        Throwable failure = catchThrowable(validator::validate);
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure).isNotNull();
        return failure.getMessage();
    }

    private static void assertValidationFails(ProductionConfigurationValidator validator, String propertyName) {
        assertThat(validationFailureMessage(validator)).contains(propertyName);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Map<String, Object> safeProperties() {
        return new LinkedHashMap<>(Map.ofEntries(
            Map.entry(DATASOURCE_URL, "jdbc:postgresql://postgres.internal:5432/tieat"),
            Map.entry(DATASOURCE_USERNAME, "tieat-production"),
            Map.entry(DATASOURCE_PASSWORD, "production-db-password"),
            Map.entry(SESSION_COOKIE_SECURE, "true"),
            Map.entry(QR_ENCRYPTION_REQUIRED, "true"),
            Map.entry(QR_ENCRYPTION_KEYS, QR_KEY_RING),
            Map.entry(QR_ENCRYPTION_KEY_VERSION, "1"),
            Map.entry(RECEIPTS_PROVIDER, "azure"),
            Map.entry(RECEIPTS_AZURE_ENDPOINT, "https://tieat-receipts.blob.core.windows.net"),
            Map.entry(RECEIPTS_AZURE_CONTAINER, "tieat-receipts-private")
        ));
    }

    private static Map<String, Object> localProperties() {
        return new LinkedHashMap<>(Map.ofEntries(
            Map.entry(DATASOURCE_URL, "jdbc:postgresql://localhost:5432/tieat"),
            Map.entry(DATASOURCE_USERNAME, "tieat"),
            Map.entry(DATASOURCE_PASSWORD, "tieat_local"),
            Map.entry(QR_ENCRYPTION_REQUIRED, "false"),
            Map.entry(RECEIPTS_PROVIDER, "local")
        ));
    }

    private static String[] propertyValues(String profile, Map<String, Object> properties) {
        List<String> values = new ArrayList<>();
        if (profile != null) {
            values.add("spring.profiles.active=" + profile);
        }
        properties.forEach((propertyName, value) -> values.add(propertyName + "=" + value));
        return values.toArray(String[]::new);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ProductionConfigurationValidator.class)
    static class ValidatorTestConfiguration {
    }
}
