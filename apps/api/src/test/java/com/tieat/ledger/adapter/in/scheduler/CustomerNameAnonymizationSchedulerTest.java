package com.tieat.ledger.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tieat.ledger.application.AnonymizeExpiredCustomerNamesUseCase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CustomerNameAnonymizationSchedulerTest {

    @Test
    void logsTheSafeResultAfterSuccessfulAnonymization(CapturedOutput output) {
        AnonymizeExpiredCustomerNamesUseCase anonymization = mock(AnonymizeExpiredCustomerNamesUseCase.class);
        Instant executedAt = Instant.parse("2026-08-30T18:00:00Z");
        Instant cutoffExclusive = Instant.parse("2026-05-30T18:00:00Z");
        when(anonymization.anonymize()).thenReturn(
            new AnonymizeExpiredCustomerNamesUseCase.Result(executedAt, cutoffExclusive, 4)
        );

        new CustomerNameAnonymizationScheduler(anonymization).anonymizeCustomerNames();

        verify(anonymization).anonymize();
        assertThat(output).contains(
            "operation=customer_name_anonymization",
            "executedAt=" + executedAt,
            "cutoffExclusive=" + cutoffExclusive,
            "affectedCount=4"
        );
    }

    @Test
    void logsOnlyTheExceptionTypeWhenAnonymizationFails(CapturedOutput output) {
        AnonymizeExpiredCustomerNamesUseCase anonymization = mock(AnonymizeExpiredCustomerNamesUseCase.class);
        String sensitiveMessage = "jdbc:postgresql://db.example/password=secret customer=홍길동";
        when(anonymization.anonymize()).thenThrow(new IllegalStateException(sensitiveMessage));

        new CustomerNameAnonymizationScheduler(anonymization).anonymizeCustomerNames();

        verify(anonymization).anonymize();
        assertThat(output)
            .contains("operational_event=customer_name_anonymization_failed")
            .contains("exceptionType=java.lang.IllegalStateException")
            .doesNotContain(sensitiveMessage)
            .doesNotContain("operation=customer_name_anonymization executedAt=");
    }
}
