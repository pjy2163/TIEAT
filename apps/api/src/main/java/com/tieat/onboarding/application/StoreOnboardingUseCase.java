package com.tieat.onboarding.application;

import com.tieat.identity.domain.StoreAccount;
import com.tieat.identity.domain.StoreAccountRepository;
import com.tieat.onboarding.config.InviteCodeVerifier;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.PartnerOrganization;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.partnership.domain.PartnerOrganizationRepository;
import com.tieat.store.domain.Store;
import com.tieat.store.domain.StoreCatalogEntry;
import com.tieat.store.domain.StoreCatalogRepository;
import com.tieat.store.domain.StoreId;
import com.tieat.store.domain.StoreOnboardingStatus;
import com.tieat.store.domain.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreOnboardingUseCase {

    private static final Pattern NEW_LOGIN_ID = Pattern.compile("[a-z0-9._-]{4,120}");
    private static final int MAX_CATALOG_RESULTS = 10;

    private final StoreRepository storeRepository;
    private final StoreCatalogRepository storeCatalogRepository;
    private final StoreAccountRepository storeAccountRepository;
    private final PartnerOrganizationRepository partnerOrganizationRepository;
    private final MealContractRepository mealContractRepository;
    private final InviteCodeVerifier inviteCodeVerifier;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public StoreOnboardingUseCase(
        StoreRepository storeRepository,
        StoreCatalogRepository storeCatalogRepository,
        StoreAccountRepository storeAccountRepository,
        PartnerOrganizationRepository partnerOrganizationRepository,
        MealContractRepository mealContractRepository,
        InviteCodeVerifier inviteCodeVerifier,
        PasswordEncoder passwordEncoder,
        Clock clock
    ) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.storeCatalogRepository = Objects.requireNonNull(storeCatalogRepository);
        this.storeAccountRepository = Objects.requireNonNull(storeAccountRepository);
        this.partnerOrganizationRepository = Objects.requireNonNull(partnerOrganizationRepository);
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
        this.inviteCodeVerifier = Objects.requireNonNull(inviteCodeVerifier);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public SignupResult signUp(StoreSignupCommand command) {
        Objects.requireNonNull(command, "Store signup command must be supplied");
        if (!inviteCodeVerifier.matches(command.inviteCode())) {
            throw OnboardingException.inviteInvalid();
        }

        String loginId = normalizeLoginId(command.loginId());
        validatePassword(command.password());
        ResolvedStoreSelection storeSelection = resolveStoreSelection(command.catalogEntryId(), command.manualStoreName());

        if (storeAccountRepository.findByLoginId(loginId).isPresent()) {
            throw OnboardingException.loginIdAlreadyInUse();
        }

        Store store = new Store(
            new StoreId(UUID.randomUUID()),
            storeSelection.displayName(),
            storeSelection.catalogEntryId(),
            StoreOnboardingStatus.PARTNER_REQUIRED,
            Instant.now(clock)
        );
        storeRepository.save(store);
        try {
            storeAccountRepository.save(new StoreAccount(
                loginId,
                passwordEncoder.encode(command.password()),
                store.id(),
                true
            ));
        } catch (DataIntegrityViolationException exception) {
            throw OnboardingException.loginIdAlreadyInUse();
        }
        return new SignupResult(loginId, StoreOnboardingStatus.PARTNER_REQUIRED);
    }

    @Transactional(readOnly = true)
    public OnboardingStatus currentStatus(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return storeRepository.findById(storeId)
            .map(store -> new OnboardingStatus(store.onboardingStatus(), false))
            .orElseGet(() -> new OnboardingStatus(StoreOnboardingStatus.COMPLETE, true));
    }

    @Transactional(readOnly = true)
    public List<StoreCatalogEntry> searchCatalog(String rawQuery) {
        String query = normalizeCatalogQuery(rawQuery);
        return storeCatalogRepository.search(query, MAX_CATALOG_RESULTS);
    }

    @Transactional
    public PartnerRegistrationResult registerFirstPartner(FirstPartnerRegistrationCommand command) {
        Objects.requireNonNull(command, "First partner registration command must be supplied");
        Store store = storeRepository.findByIdForUpdate(command.actorStoreId()).orElse(null);
        if (store == null) {
            return PartnerRegistrationResult.alreadyComplete(true);
        }
        if (store.onboardingStatus() == StoreOnboardingStatus.COMPLETE) {
            return PartnerRegistrationResult.alreadyComplete(false);
        }

        String partnerName = normalizeDisplayName(command.partnerName());
        long initialPrepaidBalanceMinor = validateInitialPrepaidBalance(
            command.paymentType(), command.initialPrepaidBalanceMinor()
        );
        boolean qrSelectable = requireExplicitQrSelection(command.qrSelectable());

        PartnerOrganization partnerOrganization = partnerOrganizationRepository.save(new PartnerOrganization(
            new PartnerOrganizationId(UUID.randomUUID()), partnerName
        ));
        MealContract mealContract = mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            store.id(),
            command.paymentType(),
            initialPrepaidBalanceMinor,
            partnerOrganization.id(),
            qrSelectable
        ));
        storeRepository.save(store.completeOnboarding());
        return PartnerRegistrationResult.created(partnerOrganization.displayName(), mealContract.paymentType());
    }

    private ResolvedStoreSelection resolveStoreSelection(UUID catalogEntryId, String manualStoreName) {
        String normalizedManualStoreName = normalizeOptionalDisplayName(manualStoreName);
        if (catalogEntryId != null && normalizedManualStoreName != null) {
            throw OnboardingException.validationFailed();
        }
        if (catalogEntryId != null) {
            StoreCatalogEntry catalogEntry = storeCatalogRepository.findById(catalogEntryId)
                .orElseThrow(OnboardingException::catalogEntryNotFound);
            return new ResolvedStoreSelection(catalogEntry.storeDisplayName(), catalogEntry.id());
        }
        if (normalizedManualStoreName == null) {
            throw OnboardingException.validationFailed();
        }
        return new ResolvedStoreSelection(normalizedManualStoreName, null);
    }

    private String normalizeLoginId(String rawLoginId) {
        if (rawLoginId == null) {
            throw OnboardingException.validationFailed();
        }
        String loginId = rawLoginId.trim();
        if (!NEW_LOGIN_ID.matcher(loginId).matches()) {
            throw OnboardingException.validationFailed();
        }
        return loginId;
    }

    private void validatePassword(String password) {
        if (password == null
            || password.codePointCount(0, password.length()) < 10
            || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw OnboardingException.validationFailed();
        }
    }

    private String normalizeCatalogQuery(String rawQuery) {
        if (rawQuery == null) {
            throw OnboardingException.validationFailed();
        }
        String query = rawQuery.trim();
        if (query.isEmpty() || query.length() > 100) {
            throw OnboardingException.validationFailed();
        }
        return query.toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplayName(String rawName) {
        String normalizedName = normalizeOptionalDisplayName(rawName);
        if (normalizedName == null) {
            throw OnboardingException.validationFailed();
        }
        return normalizedName;
    }

    private String normalizeOptionalDisplayName(String rawName) {
        if (rawName == null) {
            return null;
        }
        String normalizedName = rawName.trim();
        if (normalizedName.isEmpty()) {
            return null;
        }
        if (normalizedName.length() > 100) {
            throw OnboardingException.validationFailed();
        }
        return normalizedName;
    }

    private long validateInitialPrepaidBalance(MealContractPaymentType paymentType, Long initialPrepaidBalanceMinor) {
        if (paymentType == null || initialPrepaidBalanceMinor == null || initialPrepaidBalanceMinor < 0) {
            throw OnboardingException.validationFailed();
        }
        if (paymentType == MealContractPaymentType.POSTPAID && initialPrepaidBalanceMinor != 0) {
            throw OnboardingException.validationFailed();
        }
        return initialPrepaidBalanceMinor;
    }

    private boolean requireExplicitQrSelection(Boolean qrSelectable) {
        if (qrSelectable == null) {
            throw OnboardingException.validationFailed();
        }
        return qrSelectable;
    }

    public record StoreSignupCommand(
        String inviteCode,
        String loginId,
        String password,
        UUID catalogEntryId,
        String manualStoreName
    ) {
    }

    public record SignupResult(String loginId, StoreOnboardingStatus onboardingStatus) {
    }

    public record OnboardingStatus(StoreOnboardingStatus status, boolean legacy) {
    }

    public record FirstPartnerRegistrationCommand(
        StoreId actorStoreId,
        String partnerName,
        MealContractPaymentType paymentType,
        Long initialPrepaidBalanceMinor,
        Boolean qrSelectable
    ) {

        public FirstPartnerRegistrationCommand {
            Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        }
    }

    public record PartnerRegistrationResult(
        StoreOnboardingStatus onboardingStatus,
        boolean created,
        boolean legacy,
        String partnerDisplayName,
        MealContractPaymentType paymentType
    ) {

        static PartnerRegistrationResult created(String partnerDisplayName, MealContractPaymentType paymentType) {
            return new PartnerRegistrationResult(
                StoreOnboardingStatus.COMPLETE,
                true,
                false,
                partnerDisplayName,
                paymentType
            );
        }

        static PartnerRegistrationResult alreadyComplete(boolean legacy) {
            return new PartnerRegistrationResult(
                StoreOnboardingStatus.COMPLETE,
                false,
                legacy,
                null,
                null
            );
        }
    }

    private record ResolvedStoreSelection(String displayName, UUID catalogEntryId) {
    }
}
