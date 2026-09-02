package com.tieat.partnership.application;

import com.tieat.identity.domain.StoreAccountRepository;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.StoreArchivePinSecurity;
import com.tieat.partnership.domain.StoreArchivePinSecurityRepository;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StorePartnerArchiveService {

    private static final Pattern ARCHIVE_PIN_PATTERN = Pattern.compile("\\d{4}");

    private final MealContractRepository mealContractRepository;
    private final StoreAccountRepository storeAccountRepository;
    private final StoreArchivePinSecurityRepository archivePinSecurityRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    StorePartnerArchiveService(
        MealContractRepository mealContractRepository,
        StoreAccountRepository storeAccountRepository,
        StoreArchivePinSecurityRepository archivePinSecurityRepository,
        PasswordEncoder passwordEncoder,
        Clock clock
    ) {
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
        this.storeAccountRepository = Objects.requireNonNull(storeAccountRepository);
        this.archivePinSecurityRepository = Objects.requireNonNull(archivePinSecurityRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(noRollbackFor = StoreArchivePinException.class)
    void archiveWithPin(StoreId actorStoreId, MealContractId mealContractId, String pin, String actorLoginId) {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        requireActor(actorLoginId);

        MealContract mealContract = mealContractRepository.findByIdForUpdate(mealContractId)
            .orElseThrow(StorePartnerNotFoundException::new);
        if (!mealContract.storeId().equals(actorStoreId)) {
            throw new StorePartnerNotFoundException();
        }
        Instant now = Instant.now(clock);
        if (mealContractRepository.existsPendingUsage(mealContractId, actorStoreId, now)
            || mealContractRepository.existsOutstandingReceivable(mealContractId, actorStoreId)
            || mealContract.prepaidBalance() > 0) {
            throw new StorePartnerArchiveConflictException();
        }

        verifyArchivePin(actorStoreId, pin, actorLoginId);
        if (mealContract.isArchived()) {
            return;
        }
        mealContract.archive(actorLoginId, now);
        mealContractRepository.save(mealContract);
    }

    @Transactional(noRollbackFor = StoreArchivePinException.class)
    void setArchivePin(
        StoreId actorStoreId,
        String currentPin,
        String accountPassword,
        String newPin,
        String newPinConfirmation,
        String actorLoginId
    ) {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        requireActor(actorLoginId);
        requireArchivePin(newPin);
        requireArchivePin(newPinConfirmation);
        if (!Objects.equals(newPin, newPinConfirmation)) {
            throw new StorePartnerValidationException();
        }
        archivePinSecurityRepository.lockStore(actorStoreId);
        Instant now = Instant.now(clock);
        StoreArchivePinSecurity security = archivePinSecurityRepository.findByStoreIdForUpdate(actorStoreId).orElse(null);
        if (security == null) {
            if (currentPin != null && !currentPin.isBlank()) {
                throw new StoreArchivePinException(StoreArchivePinException.Reason.CURRENT_REQUIRED);
            }
            if (!matchesAccountPassword(actorStoreId, actorLoginId, accountPassword)) {
                throw new StoreArchivePinException(StoreArchivePinException.Reason.ACCOUNT_PASSWORD_INVALID);
            }
            archivePinSecurityRepository.save(StoreArchivePinSecurity.initial(
                actorStoreId,
                passwordEncoder.encode(newPin),
                now,
                actorLoginId
            ));
            return;
        }
        if (currentPin == null || currentPin.isBlank()) {
            throw new StoreArchivePinException(StoreArchivePinException.Reason.ALREADY_CONFIGURED);
        }
        verifyLoadedArchivePin(security, currentPin, now, actorLoginId);
        security.changePinHash(passwordEncoder.encode(newPin), now, actorLoginId);
        archivePinSecurityRepository.save(security);
    }

    @Transactional(readOnly = true)
    boolean archivePinConfigured(StoreId actorStoreId) {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        return archivePinSecurityRepository.findByStoreId(actorStoreId).isPresent();
    }

    @Transactional
    void archive(StoreId actorStoreId, MealContractId mealContractId, String actorLoginId) {
        throw new StorePartnerArchivePinRequiredException();
    }

    private void verifyArchivePin(StoreId actorStoreId, String pin, String actorLoginId) {
        requireArchivePin(pin);
        archivePinSecurityRepository.lockStore(actorStoreId);
        Instant now = Instant.now(clock);
        StoreArchivePinSecurity security = archivePinSecurityRepository.findByStoreIdForUpdate(actorStoreId)
            .orElseThrow(() -> new StoreArchivePinException(StoreArchivePinException.Reason.NOT_CONFIGURED));
        verifyLoadedArchivePin(security, pin, now, actorLoginId);
    }

    private void verifyLoadedArchivePin(
        StoreArchivePinSecurity security,
        String pin,
        Instant now,
        String actorLoginId
    ) {
        if (security.isLockedAt(now)) {
            throw new StoreArchivePinException(StoreArchivePinException.Reason.LOCKED);
        }
        if (!passwordEncoder.matches(pin, security.pinHash())) {
            security.registerFailedAttempt(now, actorLoginId);
            archivePinSecurityRepository.save(security);
            throw new StoreArchivePinException(
                security.isLockedAt(now)
                    ? StoreArchivePinException.Reason.LOCKED
                    : StoreArchivePinException.Reason.INVALID
            );
        }
        security.markVerified(now, actorLoginId);
        archivePinSecurityRepository.save(security);
    }

    private boolean matchesAccountPassword(StoreId actorStoreId, String actorLoginId, String accountPassword) {
        if (accountPassword == null) {
            return false;
        }
        return storeAccountRepository.findByLoginId(actorLoginId)
            .filter(account -> account.storeId().equals(actorStoreId))
            .map(account -> passwordEncoder.matches(accountPassword, account.passwordHash()))
            .orElse(false);
    }

    private void requireArchivePin(String pin) {
        if (pin == null || !ARCHIVE_PIN_PATTERN.matcher(pin).matches()) {
            throw new StorePartnerValidationException();
        }
    }

    private void requireActor(String actorLoginId) {
        if (actorLoginId == null || actorLoginId.isBlank()) {
            throw new StorePartnerValidationException();
        }
    }
}
