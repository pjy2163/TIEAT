package com.tieat.qr.application;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.qr.domain.MealUsageQrOperationsRepository;
import com.tieat.qr.domain.QrOperationAudit;
import com.tieat.qr.domain.MealUsageQrOperationsRepository.QrPartnerSelection;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManageMealUsageQrOperationsUseCase {

    private final MealUsageQrOperationsRepository repository;
    private final Clock clock;
    private final MealUsageQrTokenProtector tokenProtector;

    public ManageMealUsageQrOperationsUseCase(
        MealUsageQrOperationsRepository repository,
        Clock clock,
        MealUsageQrTokenProtector tokenProtector
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.tokenProtector = Objects.requireNonNull(tokenProtector);
    }

    @Transactional
    public IssuedQr issue(IssueCommand command) {
        Objects.requireNonNull(command, "QR issue command must be supplied");
        ensureEnabledStoreAccount(command.storeId());
        repository.lockStoreForOperations(command.storeId());
        if (repository.findCurrentByStoreIdForUpdate(command.storeId()).isPresent()) {
            throw new QrOperationException("A current QR context already exists for this store; use reissue instead");
        }
        ensureQrSelectablePartnerExists(command.storeId());
        return issueNewContext(command.storeId(), command.storeDisplayName(), command.operatorId(), Instant.now(clock));
    }

    @Transactional
    public IssuedQr reissue(ReissueCommand command) {
        Objects.requireNonNull(command, "QR reissue command must be supplied");
        repository.lockStoreForOperations(command.storeId());
        MealUsageQrContext current = repository.findCurrentByStoreIdForUpdate(command.storeId())
            .orElseThrow(() -> new QrOperationException("No current QR context exists for this store; use issue instead"));
        ensureQrSelectablePartnerExists(command.storeId());
        Instant now = Instant.now(clock);
        repository.revoke(current.id(), now);
        repository.appendAudit(QrOperationAudit.qrRevoked(command.operatorId(), command.storeId(), current.id(), now));
        return issueNewContext(command.storeId(), current.storeDisplayName(), command.operatorId(), now);
    }

    @Transactional
    public void revoke(RevokeCommand command) {
        Objects.requireNonNull(command, "QR revoke command must be supplied");
        repository.lockStoreForOperations(command.storeId());
        MealUsageQrContext current = repository.findCurrentByStoreIdForUpdate(command.storeId())
            .orElseThrow(() -> new QrOperationException("No current QR context exists for this store"));
        Instant now = Instant.now(clock);
        repository.revoke(current.id(), now);
        repository.appendAudit(QrOperationAudit.qrRevoked(command.operatorId(), command.storeId(), current.id(), now));
    }

    @Transactional
    public PartnerSelectionResult changePartnerSelection(ChangePartnerSelectionCommand command) {
        Objects.requireNonNull(command, "QR partner selection command must be supplied");
        repository.lockStoreForOperations(command.storeId());
        QrPartnerSelection before = repository.findPartnerSelectionByIdAndStoreIdForUpdate(
            command.mealContractId(), command.storeId()
        ).orElseThrow(() -> new QrOperationException("QR partner contract was not found for this store"));
        if (before.qrSelectable() == command.qrSelectable()) {
            return new PartnerSelectionResult(before, false);
        }
        if (!command.qrSelectable() && repository.findCurrentByStoreId(command.storeId()).isPresent()
            && repository.findPartnerSelectionsByStoreId(command.storeId()).stream()
                .filter(QrPartnerSelection::qrSelectable)
                .count() == 1) {
            throw new QrOperationException("Revoke the current QR before disabling its last selectable partner");
        }

        repository.updateQrSelectable(command.mealContractId(), command.storeId(), command.qrSelectable());
        QrPartnerSelection after = new QrPartnerSelection(
            before.mealContractId(), before.partnerDisplayName(), command.qrSelectable()
        );
        Instant now = Instant.now(clock);
        repository.appendAudit(QrOperationAudit.partnerSelectionChanged(
            command.operatorId(), command.storeId(), command.mealContractId(), before.qrSelectable(), after.qrSelectable(), now
        ));
        return new PartnerSelectionResult(after, true);
    }

    @Transactional(readOnly = true)
    public Optional<MealUsageQrContext> currentQrContext(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.findCurrentByStoreId(storeId);
    }

    @Transactional(readOnly = true)
    public List<QrPartnerSelection> partnerSelections(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.findPartnerSelectionsByStoreId(storeId);
    }

    private IssuedQr issueNewContext(StoreId storeId, String storeDisplayName, String operatorId, Instant now) {
        String rawToken = MealUsageQrToken.generate();
        MealUsageQrContextId contextId = new MealUsageQrContextId(UUID.randomUUID());
        MealUsageQrContext context = MealUsageQrContext.issue(
            contextId,
            storeId,
            storeDisplayName,
            MealUsageQrToken.sha256Hash(rawToken),
            now,
            tokenProtector.protect(rawToken, contextId, storeId)
        );
        repository.insert(context);
        repository.appendAudit(QrOperationAudit.qrIssued(operatorId, storeId, context.id(), now));
        return new IssuedQr(context, rawToken);
    }

    private void ensureEnabledStoreAccount(StoreId storeId) {
        if (!repository.enabledStoreAccountExists(storeId)) {
            throw new QrOperationException("No enabled shared store account exists for this store");
        }
    }

    private void ensureQrSelectablePartnerExists(StoreId storeId) {
        if (repository.findPartnerSelectionsByStoreId(storeId).stream().noneMatch(QrPartnerSelection::qrSelectable)) {
            throw new QrOperationException("At least one QR-selectable partner is required before issuing a QR");
        }
    }

    public record IssueCommand(StoreId storeId, String storeDisplayName, String operatorId) {

        public IssueCommand {
            Objects.requireNonNull(storeId, "Store id must be supplied");
            storeDisplayName = requireNonBlank(storeDisplayName, "Store display name");
            operatorId = requireNonBlank(operatorId, "QR operator id");
        }
    }

    public record ReissueCommand(StoreId storeId, String operatorId) {

        public ReissueCommand {
            Objects.requireNonNull(storeId, "Store id must be supplied");
            operatorId = requireNonBlank(operatorId, "QR operator id");
        }
    }

    public record RevokeCommand(StoreId storeId, String operatorId) {

        public RevokeCommand {
            Objects.requireNonNull(storeId, "Store id must be supplied");
            operatorId = requireNonBlank(operatorId, "QR operator id");
        }
    }

    public record ChangePartnerSelectionCommand(
        StoreId storeId,
        MealContractId mealContractId,
        boolean qrSelectable,
        String operatorId
    ) {

        public ChangePartnerSelectionCommand {
            Objects.requireNonNull(storeId, "Store id must be supplied");
            Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
            operatorId = requireNonBlank(operatorId, "QR operator id");
        }
    }

    public record PartnerSelectionResult(QrPartnerSelection selection, boolean changed) {

        public PartnerSelectionResult {
            Objects.requireNonNull(selection, "QR partner selection must be supplied");
        }
    }

    public static final class IssuedQr {

        private final MealUsageQrContext context;
        private final String rawToken;

        private IssuedQr(MealUsageQrContext context, String rawToken) {
            this.context = Objects.requireNonNull(context);
            this.rawToken = requireNonBlank(rawToken, "Raw QR token");
        }

        public MealUsageQrContext context() {
            return context;
        }

        public String rawToken() {
            return rawToken;
        }

        @Override
        public String toString() {
            return "IssuedQr[contextId=" + context.id().value() + ", rawToken=[redacted]]";
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
