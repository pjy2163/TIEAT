package com.tieat.qr.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.application.ManageMealUsageQrOperationsUseCase;
import com.tieat.qr.application.MealUsageQrTokenProtector;
import com.tieat.qr.application.QrOperationException;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.qr.domain.MealUsageQrOperationsRepository;
import com.tieat.qr.domain.QrOperationAudit;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class QrOperationsCommandRunnerTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final MealContractId CONTRACT_ID = new MealContractId(
        UUID.fromString("39ca4eb7-2810-4b82-8613-e5cf5d4df3a8")
    );

    @Test
    void issuesAWebQrUrlOnceAndDoesNotAcceptARawTokenOption(CapturedOutput output) throws Exception {
        IssueOnlyRepository repository = new IssueOnlyRepository();
        QrOperationsCommandRunner runner = new QrOperationsCommandRunner(new ManageMealUsageQrOperationsUseCase(
            repository,
            Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC),
            testProtector()
        ));

        runner.run(new DefaultApplicationArguments(
            "--tieat.qr-operations.command=issue",
            "--tieat.qr-operations.store-id=" + STORE_ID.value(),
            "--tieat.qr-operations.store-display-name=강남점",
            "--tieat.qr-operations.operator=owner-parang",
            "--tieat.qr-operations.web-origin=https://pilot.example.test"
        ));

        Matcher url = Pattern.compile("QR_URL=https://pilot\\.example\\.test/qr/([A-Za-z0-9_-]{43})")
            .matcher(output.getOut());
        assertThat(url.find()).isTrue();
        String rawToken = url.group(1);
        assertThat(countOccurrences(output.getOut(), rawToken)).isEqualTo(1);
        assertThat(output.getOut()).contains("QR_CONTEXT_ID=").doesNotContain("token_hash");
        assertThat(repository.contexts).hasSize(1);
        assertThat(repository.contexts.getFirst().protectedToken()).isPresent();
        assertThat(repository.audits).hasSize(1);

        Throwable error = org.assertj.core.api.Assertions.catchThrowable(() -> runner.run(new DefaultApplicationArguments(
            "--tieat.qr-operations.command=issue",
            "--tieat.qr-operations.store-id=" + STORE_ID.value(),
            "--tieat.qr-operations.store-display-name=강남점",
            "--tieat.qr-operations.operator=owner-parang",
            "--tieat.qr-operations.web-origin=https://pilot.example.test",
            "--tieat.qr-operations.raw-token=must-not-be-an-input"
        )));
        assertThat(error).isInstanceOf(QrOperationException.class)
            .hasMessageContaining("Unsupported QR operations option");
        assertThat(error.getMessage()).doesNotContain("must-not-be-an-input");
        assertThat(output.getOut()).doesNotContain("must-not-be-an-input");
    }

    @Test
    void rejectsNonOriginOrInsecureWebOriginsBeforeIssuing(CapturedOutput output) {
        for (String origin : List.of(
            "http://localhost:3000",
            "https://pilot.example.test/qr",
            "https://pilot.example.test?source=operator",
            "https://owner@pilot.example.test",
            "https://pilot.example.test/#fragment"
        )) {
            IssueOnlyRepository repository = new IssueOnlyRepository();
            QrOperationsCommandRunner runner = new QrOperationsCommandRunner(new ManageMealUsageQrOperationsUseCase(
                repository,
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC),
                testProtector()
            ));

            assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(
                "--tieat.qr-operations.command=issue",
                "--tieat.qr-operations.store-id=" + STORE_ID.value(),
                "--tieat.qr-operations.store-display-name=강남점",
                "--tieat.qr-operations.operator=owner-parang",
                "--tieat.qr-operations.web-origin=" + origin
            ))).isInstanceOf(QrOperationException.class)
                .hasMessageContaining("HTTPS origin");
            assertThat(repository.contexts).isEmpty();
            assertThat(repository.audits).isEmpty();
        }
        assertThat(output.getOut()).doesNotContain("QR_URL=");
    }

    private int countOccurrences(String text, String value) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(value, from)) >= 0) {
            count++;
            from += value.length();
        }
        return count;
    }

    private static MealUsageQrTokenProtector testProtector() {
        return new MealUsageQrTokenProtector("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1);
    }

    private static final class IssueOnlyRepository implements MealUsageQrOperationsRepository {

        private final List<MealUsageQrContext> contexts = new ArrayList<>();
        private final List<QrOperationAudit> audits = new ArrayList<>();

        @Override
        public void lockStoreForOperations(StoreId storeId) {
        }

        @Override
        public boolean enabledStoreAccountExists(StoreId storeId) {
            return STORE_ID.equals(storeId);
        }

        @Override
        public Optional<MealUsageQrContext> findCurrentByStoreId(StoreId storeId) {
            return contexts.stream().filter(context -> context.storeId().equals(storeId)).findFirst();
        }

        @Override
        public Optional<MealUsageQrContext> findCurrentByStoreIdForUpdate(StoreId storeId) {
            return findCurrentByStoreId(storeId);
        }

        @Override
        public void insert(MealUsageQrContext context) {
            contexts.add(context);
        }

        @Override
        public void revoke(MealUsageQrContextId contextId, Instant revokedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<QrPartnerSelection> findPartnerSelectionsByStoreId(StoreId storeId) {
            return List.of(new QrPartnerSelection(CONTRACT_ID, "협력사 A", true));
        }

        @Override
        public Optional<QrPartnerSelection> findPartnerSelectionByIdAndStoreIdForUpdate(
            MealContractId mealContractId,
            StoreId storeId
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateQrSelectable(MealContractId mealContractId, StoreId storeId, boolean qrSelectable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendAudit(QrOperationAudit audit) {
            audits.add(audit);
        }
    }
}
