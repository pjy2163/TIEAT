package com.tieat.ledger.adapter.in.cli;

import com.tieat.ledger.application.AnonymizeExpiredCustomerNamesUseCase;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnNotWebApplication
@ConditionalOnProperty(prefix = "tieat.customer-name-anonymization", name = "command", havingValue = "anonymize")
public class CustomerNameAnonymizationCommandRunner implements ApplicationRunner {

    private static final String PREFIX = "tieat.customer-name-anonymization.";
    private static final String COMMAND = PREFIX + "command";
    private final AnonymizeExpiredCustomerNamesUseCase anonymization;

    public CustomerNameAnonymizationCommandRunner(AnonymizeExpiredCustomerNamesUseCase anonymization) {
        this.anonymization = Objects.requireNonNull(anonymization);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        boolean hasUnsupportedAnonymizationOption = arguments.getOptionNames().stream()
            .filter(option -> option.startsWith(PREFIX))
            .anyMatch(option -> !COMMAND.equals(option));
        if (!arguments.getNonOptionArgs().isEmpty() || hasUnsupportedAnonymizationOption) {
            throw new IllegalArgumentException("Customer name anonymization accepts only --" + COMMAND + "=anonymize");
        }
        List<String> values = arguments.getOptionValues(COMMAND);
        if (values == null || values.size() != 1 || !"anonymize".equals(values.getFirst())) {
            throw new IllegalArgumentException("Customer name anonymization command must be anonymize");
        }
        AnonymizeExpiredCustomerNamesUseCase.Result result = anonymization.anonymize();
        System.out.println(
            "CUSTOMER_NAME_ANONYMIZATION_EXECUTED_AT=" + result.executedAt()
                + "\tCUTOFF_EXCLUSIVE=" + result.cutoffExclusive()
                + "\tAFFECTED_COUNT=" + result.affectedCount()
        );
    }
}
