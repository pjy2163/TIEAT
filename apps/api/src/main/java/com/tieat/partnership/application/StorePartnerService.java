package com.tieat.partnership.application;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.PartnerKind;
import com.tieat.partnership.domain.StorePartnerDirectoryEntry;
import com.tieat.store.domain.StoreId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePartnerService {

    private final StorePartnerDirectoryService directoryService;
    private final StorePartnerRegistrationService registrationService;
    private final StorePartnerPaymentTermService paymentTermService;
    private final StorePartnerArchiveService archiveService;

    public StorePartnerService(
        StorePartnerDirectoryService directoryService,
        StorePartnerRegistrationService registrationService,
        StorePartnerPaymentTermService paymentTermService,
        StorePartnerArchiveService archiveService
    ) {
        this.directoryService = Objects.requireNonNull(directoryService);
        this.registrationService = Objects.requireNonNull(registrationService);
        this.paymentTermService = Objects.requireNonNull(paymentTermService);
        this.archiveService = Objects.requireNonNull(archiveService);
    }

    @Transactional(readOnly = true)
    public List<StorePartnerDirectoryEntry> list(StoreId actorStoreId) {
        return directoryService.list(actorStoreId);
    }

    @Transactional(readOnly = true)
    public StorePartnerDirectoryEntry requireOwnedPartner(StoreId actorStoreId, MealContractId mealContractId) {
        return directoryService.requireOwnedPartner(actorStoreId, mealContractId);
    }

    @Transactional
    public StorePartnerDirectoryEntry updatePaymentType(
        StoreId actorStoreId,
        MealContractId mealContractId,
        MealContractPaymentType expectedPaymentType,
        MealContractPaymentType newPaymentType,
        Long newPrepaidBalanceMinor,
        String actorLoginId
    ) {
        return paymentTermService.updatePaymentType(
            actorStoreId,
            mealContractId,
            expectedPaymentType,
            newPaymentType,
            newPrepaidBalanceMinor,
            actorLoginId
        );
    }

    @Transactional(noRollbackFor = StoreArchivePinException.class)
    public void archiveWithPin(StoreId actorStoreId, MealContractId mealContractId, String pin, String actorLoginId) {
        archiveService.archiveWithPin(actorStoreId, mealContractId, pin, actorLoginId);
    }

    @Transactional(noRollbackFor = StoreArchivePinException.class)
    public void setArchivePin(
        StoreId actorStoreId,
        String currentPin,
        String accountPassword,
        String newPin,
        String newPinConfirmation,
        String actorLoginId
    ) {
        archiveService.setArchivePin(
            actorStoreId,
            currentPin,
            accountPassword,
            newPin,
            newPinConfirmation,
            actorLoginId
        );
    }

    @Transactional(readOnly = true)
    public ArchivePinStatus archivePinStatus(StoreId actorStoreId) {
        return new ArchivePinStatus(archiveService.archivePinConfigured(actorStoreId));
    }

    @Transactional
    public void archive(StoreId actorStoreId, MealContractId mealContractId, String actorLoginId) {
        archiveService.archive(actorStoreId, mealContractId, actorLoginId);
    }

    @Transactional
    public StorePartnerDirectoryEntry create(CreateStorePartnerCommand command) {
        Objects.requireNonNull(command, "Store partner command must be supplied");
        return registrationService.create(new StorePartnerRegistrationService.CreateCommand(
            command.actorStoreId(),
            command.idempotencyKey(),
            command.partnerName(),
            command.partnerKind(),
            command.paymentType(),
            command.initialPrepaidBalanceMinor(),
            command.qrSelectable(),
            command.representativePhone(),
            command.representativeEmail()
        ));
    }

    public record ArchivePinStatus(boolean configured) {
    }

    public record CreateStorePartnerCommand(
        StoreId actorStoreId,
        UUID idempotencyKey,
        String partnerName,
        PartnerKind partnerKind,
        MealContractPaymentType paymentType,
        Long initialPrepaidBalanceMinor,
        Boolean qrSelectable,
        String representativePhone,
        String representativeEmail
    ) {

        public CreateStorePartnerCommand {
            Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
            Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        }

        public CreateStorePartnerCommand(
            StoreId actorStoreId,
            UUID idempotencyKey,
            String partnerName,
            PartnerKind partnerKind,
            MealContractPaymentType paymentType,
            Long initialPrepaidBalanceMinor,
            Boolean qrSelectable
        ) {
            this(
                actorStoreId,
                idempotencyKey,
                partnerName,
                partnerKind,
                paymentType,
                initialPrepaidBalanceMinor,
                qrSelectable,
                null,
                null
            );
        }
    }
}
