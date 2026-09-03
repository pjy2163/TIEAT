package com.tieat.qr.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tieat.TieatApiApplication;
import com.tieat.identity.application.SessionReauthenticationService;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.PartnerOrganization;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.partnership.domain.PartnerOrganizationRepository;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.store.domain.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class MealUsageQrOperationsCliAndMigrationIntegrationTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final String LEGACY_SCHEMA = "legacy_qr_ops_preflight";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");

    @Autowired
    private MealContractRepository mealContractRepository;

    @Autowired
    private PartnerOrganizationRepository partnerOrganizationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("delete from public_meal_usage_idempotency_keys");
        jdbcTemplate.update("delete from meal_usages");
        jdbcTemplate.update("delete from meal_usage_qr_operation_audits");
        jdbcTemplate.update("delete from meal_usage_qr_contexts");
        jdbcTemplate.update("delete from meal_contracts");
        jdbcTemplate.update("delete from partner_organizations");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void runsTheQrOperationsCommandInANonWebApplicationContextWithoutHttpSecurity() {
        activeFixture();

        try (ConfigurableApplicationContext cliContext = new SpringApplicationBuilder(TieatApiApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.enabled=false",
                "--spring.main.banner-mode=off",
                "--tieat.qr-operations.command=status",
                "--tieat.qr-operations.store-id=" + STORE_ID.value()
            )) {
            assertThat(cliContext).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(cliContext.getBeansOfType(PasswordEncoder.class)).hasSize(1);
            assertThat(cliContext.getBean(SessionReauthenticationService.class)).isNotNull();
            assertThat(cliContext.getBeansOfType(SessionAuthenticationStrategy.class)).hasSize(1);
            assertThat(cliContext.getBeansOfType(SecurityContextRepository.class)).hasSize(1);
            assertThat(cliContext.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        }
    }

    @Test
    void stopsV9MigrationWhenLegacyDataHasTwoNonRevokedContextsForOneStore() {
        jdbcTemplate.execute("drop schema if exists " + LEGACY_SCHEMA + " cascade");
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(LEGACY_SCHEMA)
                .defaultSchema(LEGACY_SCHEMA)
                .createSchemas(true)
                .target(MigrationVersion.fromVersion("8"))
                .load()
                .migrate();
            UUID storeId = UUID.randomUUID();
            Instant now = Instant.now();
            for (int index = 0; index < 2; index++) {
                jdbcTemplate.update(
                    """
                        insert into %s.meal_usage_qr_contexts
                            (id, store_id, store_display_name, token_hash, expires_at, revoked_at, created_at)
                        values (?, ?, ?, ?, ?, null, ?)
                        """.formatted(LEGACY_SCHEMA),
                    UUID.randomUUID(),
                    storeId,
                    "강남점",
                    MealUsageQrToken.sha256Hash(MealUsageQrToken.generate()),
                    Timestamp.from(now.plusSeconds(3_600)),
                    Timestamp.from(now)
                );
            }

            assertThatThrownBy(() -> Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(LEGACY_SCHEMA)
                .defaultSchema(LEGACY_SCHEMA)
                .load()
                .migrate()
            ).isInstanceOf(FlywayException.class);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from " + LEGACY_SCHEMA + ".meal_usage_qr_contexts where revoked_at is null", Long.class
            )).isEqualTo(2);
        } finally {
            jdbcTemplate.execute("drop schema if exists " + LEGACY_SCHEMA + " cascade");
        }
    }

    private void activeFixture() {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            "store-" + STORE_ID.value(),
            "test-password-hash",
            STORE_ID.value()
        );
        PartnerOrganization partner = partnerOrganizationRepository.save(new PartnerOrganization(
            new PartnerOrganizationId(UUID.randomUUID()), "협력사 A"
        ));
        mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            STORE_ID,
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            10_000,
            partner.id(),
            true
        ));
    }
}
