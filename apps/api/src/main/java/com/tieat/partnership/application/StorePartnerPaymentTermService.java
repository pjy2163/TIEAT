package com.tieat.partnership.application;

import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentTermAudit;
import com.tieat.partnership.domain.MealContractPaymentTermAuditRepository;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.StorePartnerDirectoryEntry;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StorePartnerPaymentTermService {

    private final MealContractRepository mealContractRepository;
    private final MealContractPaymentTermAuditRepository paymentTermAuditRepository;
    private final Clock clock;

    StorePartnerPaymentTermService(
        MealContractRepository mealContractRepository,
        MealContractPaymentTermAuditRepository paymentTermAuditRepository,
        Clock clock
    ) {
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
        this.paymentTermAuditRepository = Objects.requireNonNull(paymentTermAuditRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    StorePartnerDirectoryEntry updatePaymentType(
        StoreId actorStoreId,
        MealContractId mealContractId,
        MealContractPaymentType expectedPaymentType,
        MealContractPaymentType newPaymentType,
        Long newPrepaidBalanceMinor,
        String actorLoginId
    ) {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(expectedPaymentType, "Expected payment type must be supplied");
        Objects.requireNonNull(newPaymentType, "New payment type must be supplied");
        requireActor(actorLoginId);
        if (expectedPaymentType == newPaymentType) {
            throw new StorePartnerValidationException();
        }
        long requestedBalance = validatePaymentTermBalance(newPaymentType, newPrepaidBalanceMinor);

        MealContract mealContract = mealContractRepository.findByIdForUpdate(mealContractId)
            .orElseThrow(StorePartnerNotFoundException::new);
        if (!mealContract.storeId().equals(actorStoreId)
            || mealContract.isArchived()) {
            throw new StorePartnerNotFoundException();
        }
        if (mealContract.paymentType() != expectedPaymentType) {
            throw new StorePartnerPaymentTermConflictException(
                StorePartnerPaymentTermConflictException.Reason.EXPECTED_PAYMENT_TYPE_STALE
            );
        }
        if (mealContractRepository.existsPendingUsage(mealContractId, actorStoreId)) {
            throw new StorePartnerPaymentTermConflictException(
                StorePartnerPaymentTermConflictException.Reason.PENDING_USAGE
            );
        }
        if (mealContractRepository.existsOutstandingReceivable(mealContractId, actorStoreId)) {
            throw new StorePartnerPaymentTermConflictException(
                StorePartnerPaymentTermConflictException.Reason.OUTSTANDING_RECEIVABLE
            );
        }
        if (newPaymentType == MealContractPaymentType.POSTPAID && mealContract.prepaidBalance() != 0) {
            throw new StorePartnerPaymentTermConflictException(
                StorePartnerPaymentTermConflictException.Reason.PREPAID_BALANCE_REMAINING
            );
        }

        MealContract.PaymentTermTransition transition;
        try {
            transition = mealContract.changePaymentType(expectedPaymentType, newPaymentType, requestedBalance);
        } catch (IllegalStateException exception) {
            throw new StorePartnerPaymentTermConflictException(
                StorePartnerPaymentTermConflictException.Reason.PREPAID_BALANCE_REMAINING
            );
        } catch (IllegalArgumentException exception) {
            throw new StorePartnerValidationException();
        }
        mealContractRepository.save(mealContract);
        paymentTermAuditRepository.save(new MealContractPaymentTermAudit(
            UUID.randomUUID(),
            actorStoreId,
            mealContractId,
            actorLoginId,
            transition.previousPaymentType(),
            transition.newPaymentType(),
            transition.prepaidBalanceBefore(),
            transition.prepaidBalanceAfter(),
            Instant.now(clock)
        ));
        return mealContractRepository.findPartnerDirectoryEntryByIdAndStoreId(mealContractId, actorStoreId)
            .orElseThrow(StorePartnerNotFoundException::new);
    }

    private long validatePaymentTermBalance(
        MealContractPaymentType paymentType,
        Long newPrepaidBalanceMinor
    ) {
        if (paymentType == MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW) {
            if (newPrepaidBalanceMinor == null || newPrepaidBalanceMinor <= 0) {
                throw new StorePartnerValidationException();
            }
            return newPrepaidBalanceMinor;
        }
        if (newPrepaidBalanceMinor != null && newPrepaidBalanceMinor != 0) {
            throw new StorePartnerValidationException();
        }
        return 0;
    }

    private void requireActor(String actorLoginId) {
        if (actorLoginId == null || actorLoginId.isBlank()) {
            throw new StorePartnerValidationException();
        }
    }
}
