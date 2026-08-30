package com.tieat.ledger.adapter.in.scheduler;

import com.tieat.ledger.application.AnonymizeExpiredCustomerNamesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@Profile("retention")
@ConditionalOnNotWebApplication
@EnableScheduling
public class CustomerNameAnonymizationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CustomerNameAnonymizationScheduler.class);

    private final AnonymizeExpiredCustomerNamesUseCase anonymization;

    public CustomerNameAnonymizationScheduler(AnonymizeExpiredCustomerNamesUseCase anonymization) {
        this.anonymization = anonymization;
    }

    @Scheduled(cron = "${tieat.customer-name-anonymization.cron}", zone = "Asia/Seoul")
    void anonymizeCustomerNames() {
        try {
            AnonymizeExpiredCustomerNamesUseCase.Result result = anonymization.anonymize();
            log.info(
                "operation=customer_name_anonymization executedAt={} cutoffExclusive={} affectedCount={}",
                result.executedAt(),
                result.cutoffExclusive(),
                result.affectedCount()
            );
        } catch (Exception exception) {
            log.error(
                "customer_name_anonymization failed exceptionType={}",
                exception.getClass().getName()
            );
        }
    }
}
