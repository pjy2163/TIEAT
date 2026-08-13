package com.tieat.settlement.adapter.out.persistence;

import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.settlement.domain.PosSettlement;
import com.tieat.settlement.domain.PosSettlementRepository;
import com.tieat.settlement.domain.PosSettlementSlice;
import com.tieat.store.domain.StoreId;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PosSettlementPersistenceAdapter implements PosSettlementRepository {

    private final JdbcTemplate jdbcTemplate;

    public PosSettlementPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public void lockStoreForSettlement(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(?)",
            statement -> statement.setLong(1, lockKey(storeId)),
            resultSet -> null
        );
    }

    @Override
    public Optional<PosSettlement> findByStoreIdAndIdempotencyKey(StoreId storeId, UUID idempotencyKey) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        return jdbcTemplate.query(
            """
                select id, store_id, meal_contract_id, pos_business_date, submitted_total_minor,
                       recorded_by_login_id, recorded_at, idempotency_key
                from pos_settlements
                where store_id = ? and idempotency_key = ?
                """,
            (resultSet, rowNum) -> new SettlementHeader(
                resultSet.getObject("id", UUID.class),
                new StoreId(resultSet.getObject("store_id", UUID.class)),
                new MealContractId(resultSet.getObject("meal_contract_id", UUID.class)),
                resultSet.getObject("pos_business_date", java.time.LocalDate.class),
                resultSet.getLong("submitted_total_minor"),
                resultSet.getString("recorded_by_login_id"),
                resultSet.getTimestamp("recorded_at").toInstant(),
                resultSet.getObject("idempotency_key", UUID.class)
            ),
            storeId.value(),
            idempotencyKey
        ).stream().findFirst().map(header -> toDomain(header, storeId));
    }

    @Override
    public boolean existsMealContractByIdAndStoreId(
        MealContractId mealContractId,
        StoreId storeId
    ) {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists(
                    select 1
                    from meal_contracts
                    where id = ? and store_id = ?
                )
                """,
            Boolean.class,
            mealContractId.value(),
            storeId.value()
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<LockedReceivable> lockReceivablesByIdAndStoreId(List<UUID> mealUsageIds, StoreId storeId) {
        Objects.requireNonNull(mealUsageIds, "Meal usage ids must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        if (mealUsageIds.isEmpty()) {
            throw new IllegalArgumentException("Meal usage ids must not be empty");
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(mealUsageIds.size(), "?"));
        String query = """
            select meal_usage.id, meal_usage.meal_contract_id, meal_usage.status, meal_usage.receivable_created,
                   exists (
                       select 1
                       from pos_settlement_allocations allocation
                       where allocation.meal_usage_id = meal_usage.id
                   ) as already_allocated
            from meal_usages meal_usage
            where meal_usage.store_id = ? and meal_usage.id in (%s)
            order by meal_usage.id asc
            for update of meal_usage
            """.formatted(placeholders);
        List<Object> parameters = new ArrayList<>(mealUsageIds.size() + 1);
        parameters.add(storeId.value());
        parameters.addAll(mealUsageIds);
        return jdbcTemplate.query(query, (resultSet, rowNum) -> new LockedReceivable(
            resultSet.getObject("id", UUID.class),
            new MealContractId(resultSet.getObject("meal_contract_id", UUID.class)),
            MealUsageStatus.valueOf(resultSet.getString("status")),
            resultSet.getObject("receivable_created", Long.class),
            resultSet.getBoolean("already_allocated")
        ), parameters.toArray());
    }

    @Override
    public List<OutstandingReceivable> findOutstandingReceivablesByStoreId(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return jdbcTemplate.query(
            """
                select meal_usage.id as meal_usage_id,
                       meal_usage.meal_contract_id,
                       coalesce(meal_usage.partner_display_name, partner_organization.display_name) as partner_display_name,
                       meal_usage.confirmed_at,
                       meal_usage.receivable_created
                from meal_usages meal_usage
                join meal_contracts meal_contract
                    on meal_contract.id = meal_usage.meal_contract_id
                   and meal_contract.store_id = ?
                left join partner_organizations partner_organization
                    on partner_organization.id = meal_contract.partner_organization_id
                where meal_usage.store_id = ?
                  and meal_usage.status = 'CONFIRMED'
                  and meal_usage.receivable_created > 0
                  and not exists (
                      select 1
                      from pos_settlement_allocations allocation
                      where allocation.meal_usage_id = meal_usage.id
                  )
                order by meal_usage.meal_contract_id asc, meal_usage.confirmed_at asc, meal_usage.id asc
                """,
            (resultSet, rowNum) -> new OutstandingReceivable(
                resultSet.getObject("meal_usage_id", UUID.class),
                new MealContractId(resultSet.getObject("meal_contract_id", UUID.class)),
                resultSet.getString("partner_display_name"),
                resultSet.getTimestamp("confirmed_at").toInstant(),
                resultSet.getLong("receivable_created")
            ),
            storeId.value(),
            storeId.value()
        );
    }

    @Override
    public PosSettlementSlice findByStoreId(StoreId storeId, int page, int size) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        long offset = Math.multiplyExact((long) page, size);
        List<SettlementHeader> headers = jdbcTemplate.query(
            """
                select id, store_id, meal_contract_id, pos_business_date, submitted_total_minor,
                       recorded_by_login_id, recorded_at, idempotency_key
                from pos_settlements
                where store_id = ?
                order by recorded_at desc, id desc
                limit ? offset ?
                """,
            (resultSet, rowNum) -> new SettlementHeader(
                resultSet.getObject("id", UUID.class),
                new StoreId(resultSet.getObject("store_id", UUID.class)),
                new MealContractId(resultSet.getObject("meal_contract_id", UUID.class)),
                resultSet.getObject("pos_business_date", java.time.LocalDate.class),
                resultSet.getLong("submitted_total_minor"),
                resultSet.getString("recorded_by_login_id"),
                resultSet.getTimestamp("recorded_at").toInstant(),
                resultSet.getObject("idempotency_key", UUID.class)
            ),
            storeId.value(),
            size + 1,
            offset
        );
        boolean hasNext = headers.size() > size;
        return new PosSettlementSlice(
            headers.stream().limit(size).map(header -> toDomain(header, storeId)).toList(),
            hasNext
        );
    }

    @Override
    public List<AllocationDisplay> findAllocationDisplaysBySettlementIdAndStoreId(
        UUID posSettlementId,
        StoreId storeId
    ) {
        Objects.requireNonNull(posSettlementId, "POS settlement id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return jdbcTemplate.query(
            """
                select allocation.meal_usage_id,
                       coalesce(meal_usage.partner_display_name, partner_organization.display_name) as partner_display_name,
                       meal_usage.confirmed_at,
                       allocation.receivable_amount_minor
                from pos_settlement_allocations allocation
                join pos_settlements settlement
                    on settlement.id = allocation.pos_settlement_id
                   and settlement.store_id = ?
                join meal_usages meal_usage
                    on meal_usage.id = allocation.meal_usage_id
                   and meal_usage.store_id = settlement.store_id
                left join meal_contracts meal_contract
                    on meal_contract.id = meal_usage.meal_contract_id
                   and meal_contract.store_id = settlement.store_id
                left join partner_organizations partner_organization
                    on partner_organization.id = meal_contract.partner_organization_id
                where allocation.pos_settlement_id = ?
                order by allocation.meal_usage_id asc
                """,
            (resultSet, rowNum) -> new AllocationDisplay(
                resultSet.getObject("meal_usage_id", UUID.class),
                resultSet.getString("partner_display_name"),
                resultSet.getTimestamp("confirmed_at").toInstant(),
                resultSet.getLong("receivable_amount_minor")
            ),
            storeId.value(),
            posSettlementId
        );
    }

    @Override
    public void insert(PosSettlement settlement) {
        Objects.requireNonNull(settlement, "POS settlement must be supplied");
        jdbcTemplate.update(
            """
                insert into pos_settlements
                    (id, store_id, meal_contract_id, pos_business_date, submitted_total_minor,
                     recorded_by_login_id, recorded_at, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            settlement.id(),
            settlement.storeId().value(),
            settlement.mealContractId().value(),
            Date.valueOf(settlement.posBusinessDate()),
            settlement.submittedTotalMinor(),
            settlement.recordedByLoginId(),
            Timestamp.from(settlement.recordedAt()),
            settlement.idempotencyKey()
        );
        for (PosSettlement.Allocation allocation : settlement.allocations()) {
            jdbcTemplate.update(
                """
                    insert into pos_settlement_allocations
                        (pos_settlement_id, meal_usage_id, receivable_amount_minor)
                    values (?, ?, ?)
                    """,
                settlement.id(),
                allocation.mealUsageId(),
                allocation.receivableAmountMinor()
            );
        }
    }

    private PosSettlement toDomain(SettlementHeader header, StoreId storeId) {
        return new PosSettlement(
            header.id(),
            header.storeId(),
            header.mealContractId(),
            header.posBusinessDate(),
            header.submittedTotalMinor(),
            header.recordedByLoginId(),
            header.recordedAt(),
            header.idempotencyKey(),
            findAllocations(header.id(), storeId)
        );
    }

    private List<PosSettlement.Allocation> findAllocations(UUID settlementId, StoreId storeId) {
        return jdbcTemplate.query(
            """
                select allocation.meal_usage_id, allocation.receivable_amount_minor
                from pos_settlement_allocations allocation
                join pos_settlements settlement on settlement.id = allocation.pos_settlement_id
                where allocation.pos_settlement_id = ?
                  and settlement.store_id = ?
                order by allocation.meal_usage_id asc
                """,
            (resultSet, rowNum) -> new PosSettlement.Allocation(
                resultSet.getObject("meal_usage_id", UUID.class),
                resultSet.getLong("receivable_amount_minor")
            ),
            settlementId,
            storeId.value()
        );
    }

    private long lockKey(StoreId storeId) {
        UUID value = storeId.value();
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }

    private record SettlementHeader(
        UUID id,
        StoreId storeId,
        MealContractId mealContractId,
        java.time.LocalDate posBusinessDate,
        long submittedTotalMinor,
        String recordedByLoginId,
        java.time.Instant recordedAt,
        UUID idempotencyKey
    ) {
    }
}
