package com.tieat.partnership.application;

import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.PartnerKind;
import com.tieat.partnership.domain.PartnerOrganization;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.partnership.domain.PartnerOrganizationRepository;
import com.tieat.partnership.domain.StorePartnerDirectoryEntry;
import com.tieat.partnership.domain.StorePartnerRegistration;
import com.tieat.partnership.domain.StorePartnerRegistrationRepository;
import com.tieat.qr.application.ManageMealUsageQrOperationsUseCase;
import com.tieat.store.domain.Store;
import com.tieat.store.domain.StoreId;
import com.tieat.store.domain.StoreOnboardingStatus;
import com.tieat.store.domain.StoreRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StorePartnerRegistrationService {

    private static final Pattern REPRESENTATIVE_PHONE_PATTERN = Pattern.compile("[0-9+()\\-\\s]+");
    private static final Pattern REPRESENTATIVE_EMAIL_PATTERN = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    private final MealContractRepository mealContractRepository;
    private final PartnerOrganizationRepository partnerOrganizationRepository;
    private final StorePartnerRegistrationRepository registrationRepository;
    private final StoreRepository storeRepository;
    private final ManageMealUsageQrOperationsUseCase manageMealUsageQrOperationsUseCase;

    StorePartnerRegistrationService(
        MealContractRepository mealContractRepository,
        PartnerOrganizationRepository partnerOrganizationRepository,
        StorePartnerRegistrationRepository registrationRepository,
        StoreRepository storeRepository,
        ManageMealUsageQrOperationsUseCase manageMealUsageQrOperationsUseCase
    ) {
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
        this.partnerOrganizationRepository = Objects.requireNonNull(partnerOrganizationRepository);
        this.registrationRepository = Objects.requireNonNull(registrationRepository);
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.manageMealUsageQrOperationsUseCase = Objects.requireNonNull(manageMealUsageQrOperationsUseCase);
    }

    @Transactional
    StorePartnerDirectoryEntry create(CreateCommand command) {
        Objects.requireNonNull(command, "Store partner command must be supplied");
        Store store = storeRepository.findById(command.actorStoreId()).orElse(null);
        if (store != null && store.onboardingStatus() == StoreOnboardingStatus.PARTNER_REQUIRED) {
            throw new StorePartnerValidationException();
        }
        String partnerDisplayName = normalizePartnerDisplayName(command.partnerName());
        long initialPrepaidBalanceMinor = validateInitialPrepaidBalance(
            command.paymentType(), command.initialPrepaidBalanceMinor()
        );
        PartnerKind partnerKind = requirePartnerKind(command.partnerKind());
        boolean qrSelectable = requireExplicitQrSelection(command.qrSelectable());
        String representativePhone = normalizeRepresentativePhone(command.representativePhone());
        String representativeEmail = normalizeRepresentativeEmail(command.representativeEmail());

        // Store rows may be absent for legacy accounts, so serialize on the store key itself.
        registrationRepository.lockStore(command.actorStoreId());
        var existing = registrationRepository.findByStoreIdAndIdempotencyKey(
            command.actorStoreId(), command.idempotencyKey()
        );
        if (existing.isPresent()) {
            StorePartnerRegistration registration = existing.get();
            PartnerOrganization existingPartnerOrganization = partnerOrganizationRepository
                .findById(registration.partnerOrganizationId())
                .orElseThrow(StorePartnerConflictException::new);
            if (!registration.matches(
                partnerDisplayName,
                partnerKind,
                command.paymentType(),
                initialPrepaidBalanceMinor,
                qrSelectable
            ) || !Objects.equals(existingPartnerOrganization.representativePhone(), representativePhone)
                || !Objects.equals(existingPartnerOrganization.representativeEmail(), representativeEmail)) {
                throw new StorePartnerConflictException();
            }
            StorePartnerDirectoryEntry entry = mealContractRepository.findPartnerDirectoryEntryByIdAndStoreId(
                    registration.mealContractId(), command.actorStoreId()
                )
                .orElseThrow(StorePartnerConflictException::new);
            ensureQrIssued(store);
            return entry;
        }

        PartnerOrganization partnerOrganization = partnerOrganizationRepository.save(new PartnerOrganization(
            new PartnerOrganizationId(UUID.randomUUID()),
            partnerDisplayName,
            partnerKind,
            representativePhone,
            representativeEmail
        ));
        MealContract mealContract = mealContractRepository.save(new MealContract(
            new com.tieat.partnership.domain.MealContractId(UUID.randomUUID()),
            command.actorStoreId(),
            command.paymentType(),
            initialPrepaidBalanceMinor,
            partnerOrganization.id(),
            qrSelectable
        ));
        registrationRepository.save(new StorePartnerRegistration(
            command.actorStoreId(),
            command.idempotencyKey(),
            partnerOrganization.id(),
            mealContract.id(),
            partnerOrganization.displayName(),
            partnerOrganization.partnerKind(),
            mealContract.paymentType(),
            initialPrepaidBalanceMinor,
            mealContract.isQrSelectable()
        ));
        StorePartnerDirectoryEntry entry = new StorePartnerDirectoryEntry(
            mealContract.id(),
            partnerOrganization.id(),
            partnerOrganization.displayName(),
            partnerOrganization.partnerKind(),
            mealContract.paymentType(),
            mealContract.isQrSelectable(),
            partnerOrganization.representativePhone(),
            partnerOrganization.representativeEmail()
        );
        ensureQrIssued(store);
        return entry;
    }

    private void ensureQrIssued(Store store) {
        if (store != null) {
            manageMealUsageQrOperationsUseCase.ensureIssuedIfMissing(store.id(), store.displayName());
        }
    }

    private String normalizePartnerDisplayName(String rawPartnerName) {
        if (rawPartnerName == null) {
            throw new StorePartnerValidationException();
        }
        String partnerName = rawPartnerName.trim();
        if (partnerName.isEmpty() || partnerName.length() > 100) {
            throw new StorePartnerValidationException();
        }
        return partnerName;
    }

    private long validateInitialPrepaidBalance(
        MealContractPaymentType paymentType,
        Long initialPrepaidBalanceMinor
    ) {
        if (paymentType == null || initialPrepaidBalanceMinor == null || initialPrepaidBalanceMinor < 0) {
            throw new StorePartnerValidationException();
        }
        if (paymentType == MealContractPaymentType.POSTPAID && initialPrepaidBalanceMinor != 0) {
            throw new StorePartnerValidationException();
        }
        return initialPrepaidBalanceMinor;
    }

    private boolean requireExplicitQrSelection(Boolean qrSelectable) {
        if (qrSelectable == null) {
            throw new StorePartnerValidationException();
        }
        return qrSelectable;
    }

    private PartnerKind requirePartnerKind(PartnerKind partnerKind) {
        if (partnerKind == null) {
            throw new StorePartnerValidationException();
        }
        return partnerKind;
    }

    private String normalizeRepresentativePhone(String rawPhone) {
        if (rawPhone == null) {
            return null;
        }
        String phone = rawPhone.trim();
        if (phone.isEmpty()) {
            return null;
        }
        if (phone.length() > 30
            || !REPRESENTATIVE_PHONE_PATTERN.matcher(phone).matches()
            || phone.chars().noneMatch(Character::isDigit)) {
            throw new StorePartnerValidationException();
        }
        return phone;
    }

    private String normalizeRepresentativeEmail(String rawEmail) {
        if (rawEmail == null) {
            return null;
        }
        String email = rawEmail.trim();
        if (email.isEmpty()) {
            return null;
        }
        if (email.length() > 254 || !REPRESENTATIVE_EMAIL_PATTERN.matcher(email).matches()) {
            throw new StorePartnerValidationException();
        }
        return email;
    }

    record CreateCommand(
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

        CreateCommand {
            Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
            Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        }
    }
}
