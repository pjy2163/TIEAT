package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tieat.settlement.receipt.application.PosSettlementReceiptExceptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class PosSettlementReceiptExceptionHandlerTest {

    private final PosSettlementReceiptExceptionHandler handler =
        new PosSettlementReceiptExceptionHandler(new ProblemDetailFactory(new ObjectMapper()));

    @Test
    void logsStorageFailuresWithoutSensitiveDetails(CapturedOutput output) {
        String sensitiveMessage = "path=/tmp/receipt token=secret customer=홍길동";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pos-settlements/example/receipt");

        var storageResponse = handler.handleStorageFailure(
            new PosSettlementReceiptExceptions.StorageFailure(sensitiveMessage, new IllegalStateException(sensitiveMessage)),
            request
        );

        assertThat(storageResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(output)
            .contains("operational_event=receipt_storage_failed exceptionType="
                + PosSettlementReceiptExceptions.StorageFailure.class.getName())
            .doesNotContain(sensitiveMessage);
    }
}
