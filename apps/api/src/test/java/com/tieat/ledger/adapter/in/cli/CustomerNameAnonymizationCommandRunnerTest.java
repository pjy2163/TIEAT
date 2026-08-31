package com.tieat.ledger.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tieat.ledger.application.AnonymizeExpiredCustomerNamesUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CustomerNameAnonymizationCommandRunnerTest {

    @Test
    void logsASafeOperationalEventAndRethrowsASanitizedFailure(CapturedOutput output) {
        AnonymizeExpiredCustomerNamesUseCase anonymization = mock(AnonymizeExpiredCustomerNamesUseCase.class);
        String sensitiveMessage = "jdbc:postgresql://db.example/password=secret customer=홍길동";
        when(anonymization.anonymize()).thenThrow(new IllegalStateException(sensitiveMessage));
        CustomerNameAnonymizationCommandRunner runner = new CustomerNameAnonymizationCommandRunner(anonymization);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(
            "--tieat.customer-name-anonymization.command=anonymize"
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessage("Customer name anonymization failed")
            .hasNoCause();

        verify(anonymization).anonymize();
        assertThat(output)
            .contains("operational_event=customer_name_anonymization_failed")
            .contains("exceptionType=java.lang.IllegalStateException")
            .doesNotContain(sensitiveMessage);
    }
}
