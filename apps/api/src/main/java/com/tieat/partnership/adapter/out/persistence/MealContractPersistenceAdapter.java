package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.partnership.domain.QrSelectableMealContract;
import com.tieat.partnership.domain.StorePartnerDirectoryEntry;
import com.tieat.store.domain.StoreId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MealContractPersistenceAdapter implements MealContractRepository {

    private final MealContractJpaRepository repository;

    public MealContractPersistenceAdapter(MealContractJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Optional<MealContract> findById(MealContractId id) {
        Objects.requireNonNull(id, "Meal contract id must be supplied");
        return repository.findById(id.value()).map(this::toDomain);
    }

    @Override
    public Optional<MealContract> findByIdForUpdate(MealContractId id) {
        Objects.requireNonNull(id, "Meal contract id must be supplied");
        return repository.findByIdForUpdate(id.value()).map(this::toDomain);
    }

    @Override
    public List<QrSelectableMealContract> findQrSelectableByStoreId(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.findQrSelectableByStoreId(storeId.value()).stream()
            .map(projection -> new QrSelectableMealContract(
                new MealContractId(projection.getMealContractId()),
                projection.getPartnerDisplayName()
            ))
            .toList();
    }

    @Override
    public List<StorePartnerDirectoryEntry> findPartnerDirectoryByStoreId(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.findPartnerDirectoryByStoreId(storeId.value()).stream()
            .map(this::toDirectoryEntry)
            .toList();
    }

    @Override
    public Optional<StorePartnerDirectoryEntry> findPartnerDirectoryEntryByIdAndStoreId(
        MealContractId mealContractId,
        StoreId storeId
    ) {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.findPartnerDirectoryEntryByIdAndStoreId(mealContractId.value(), storeId.value())
            .map(this::toDirectoryEntry);
    }

    @Override
    public MealContract save(MealContract mealContract) {
        Objects.requireNonNull(mealContract, "Meal contract must be supplied");
        return toDomain(repository.saveAndFlush(toEntity(mealContract)));
    }

    private MealContractJpaEntity toEntity(MealContract mealContract) {
        return new MealContractJpaEntity(
            mealContract.id().value(),
            mealContract.storeId().value(),
            mealContract.paymentType(),
            mealContract.prepaidBalance(),
            mealContract.partnerOrganizationId().map(PartnerOrganizationId::value).orElse(null),
            mealContract.isQrSelectable(),
            mealContract.archivedAt().orElse(null),
            mealContract.archivedByLoginId().orElse(null)
        );
    }

    private MealContract toDomain(MealContractJpaEntity entity) {
        return new MealContract(
            new MealContractId(entity.id()),
            new StoreId(entity.storeId()),
            entity.paymentType(),
            entity.prepaidBalance(),
            entity.partnerOrganizationId() == null ? null : new PartnerOrganizationId(entity.partnerOrganizationId()),
            entity.qrSelectable(),
            entity.archivedAt(),
            entity.archivedByLoginId()
        );
    }

    @Override
    public boolean existsPendingUsage(MealContractId mealContractId, StoreId storeId) {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.existsPendingUsageByStoreIdAndMealContractId(storeId.value(), mealContractId.value());
    }

    @Override
    public boolean existsOutstandingReceivable(MealContractId mealContractId, StoreId storeId) {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.existsOutstandingReceivableByStoreIdAndMealContractId(storeId.value(), mealContractId.value());
    }

    private StorePartnerDirectoryEntry toDirectoryEntry(StorePartnerDirectoryProjection projection) {
        return new StorePartnerDirectoryEntry(
            new MealContractId(projection.getMealContractId()),
            projection.getPartnerDisplayName(),
            projection.getPartnerKind(),
            projection.getPaymentType(),
            projection.getQrSelectable(),
            projection.getRepresentativePhone(),
            projection.getRepresentativeEmail()
        );
    }
}
