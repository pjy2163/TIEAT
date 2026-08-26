package com.tieat.web;

import com.tieat.TieatApiApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Compatibility holder for older focused integration tests that reuse the R-032 test application.
 * The endpoint scenarios live in the registration, payment-term, and archive test classes.
 */
final class StorePartnerContextHttpIntegrationTest {

    private StorePartnerContextHttpIntegrationTest() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
    @EntityScan(basePackages = "com.tieat")
    @EnableJpaRepositories(basePackages = "com.tieat")
    @ComponentScan(
        basePackages = "com.tieat",
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TieatApiApplication.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = StorePartnerContextHttpIntegrationSupport.ReceiptPackageTypeFilter.class),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.tieat\\.web\\.PosSettlementController")
        }
    )
    static class R032TestApplication extends StorePartnerContextHttpIntegrationSupport.R032TestApplication {
    }
}
