package com.tieat.settlement.adapter.out.persistence;

import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.settlement.domain.CumulativeSettlementSnapshot;
import com.tieat.settlement.domain.PosSettlement;
import com.tieat.settlement.domain.PosSettlementRepository;
import com.tieat.settlement.domain.PosSettlementSlice;
import com.tieat.store.domain.StoreId;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public OutstandingReceivableOverview findOutstandingReceivableOverviewByStoreId(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        List<OutstandingReceivable> items = new ArrayList<>();
        Map<UUID, PartnerReceivableSummary> partnersByMealContractId = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                with latest_pos_settlement as (
                    select meal_contract_id, max(pos_business_date) as previous_pos_business_date
                    from pos_settlements
                    where store_id = ?
                    group by meal_contract_id
                ),
                confirmed_meal_usage as (
                    select meal_usage.id as meal_usage_id,
                           meal_usage.meal_contract_id,
                           meal_contract.partner_organization_id,
                           meal_usage.partner_display_name,
                           meal_usage.confirmed_at,
                           meal_usage.amount,
                           coalesce(meal_usage.prepaid_applied, 0) as prepaid_applied,
                           meal_usage.receivable_created,
                           exists (
                               select 1
                               from pos_settlement_allocations allocation
                               where allocation.meal_usage_id = meal_usage.id
                           ) as allocated
                    from meal_usages meal_usage
                    join meal_contracts meal_contract
                        on meal_contract.id = meal_usage.meal_contract_id
                       and meal_contract.store_id = ?
                    where meal_usage.store_id = ?
                      and meal_usage.status = 'CONFIRMED'
                ),
                partner_summary as (
                    select confirmed.meal_contract_id,
                           confirmed.partner_organization_id,
                           coalesce(partner_organization.display_name, max(confirmed.partner_display_name)) as partner_display_name,
                           latest_pos_settlement.previous_pos_business_date,
                           coalesce(sum(confirmed.amount) filter (
                               where latest_pos_settlement.previous_pos_business_date is null
                                  or confirmed.confirmed_at >= (
                                      (latest_pos_settlement.previous_pos_business_date + 1)::timestamp
                                      at time zone 'Asia/Seoul'
                                  )
                           ), 0) as period_confirmed_usage_total_minor,
                           coalesce(sum(confirmed.prepaid_applied) filter (
                               where latest_pos_settlement.previous_pos_business_date is null
                                  or confirmed.confirmed_at >= (
                                      (latest_pos_settlement.previous_pos_business_date + 1)::timestamp
                                      at time zone 'Asia/Seoul'
                                  )
                           ), 0) as period_prepaid_applied_total_minor,
                           count(*) filter (
                               where confirmed.receivable_created > 0
                                 and not confirmed.allocated
                           ) as outstanding_receivable_count,
                           coalesce(sum(confirmed.receivable_created) filter (
                               where confirmed.receivable_created > 0
                                 and not confirmed.allocated
                           ), 0) as outstanding_receivable_total_minor
                    from confirmed_meal_usage confirmed
                    left join latest_pos_settlement
                        on latest_pos_settlement.meal_contract_id = confirmed.meal_contract_id
                    left join partner_organizations partner_organization
                        on partner_organization.id = confirmed.partner_organization_id
                    group by confirmed.meal_contract_id,
                             confirmed.partner_organization_id,
                             partner_organization.display_name,
                             latest_pos_settlement.previous_pos_business_date
                    having coalesce(sum(confirmed.amount) filter (
                        where latest_pos_settlement.previous_pos_business_date is null
                           or confirmed.confirmed_at >= (
                               (latest_pos_settlement.previous_pos_business_date + 1)::timestamp
                               at time zone 'Asia/Seoul'
                           )
                    ), 0) > 0
                        or count(*) filter (
                            where confirmed.receivable_created > 0
                              and not confirmed.allocated
                        ) > 0
                )
                select partner_summary.meal_contract_id,
                       partner_summary.partner_organization_id,
                       partner_summary.partner_display_name,
                       partner_summary.previous_pos_business_date,
                       partner_summary.period_confirmed_usage_total_minor,
                       partner_summary.period_prepaid_applied_total_minor,
                       partner_summary.outstanding_receivable_count,
                       partner_summary.outstanding_receivable_total_minor,
                       candidate.meal_usage_id,
                       coalesce(candidate.partner_display_name, partner_summary.partner_display_name) as candidate_partner_display_name,
                       candidate.confirmed_at,
                       candidate.receivable_created
                from partner_summary
                left join confirmed_meal_usage candidate
                    on candidate.meal_contract_id = partner_summary.meal_contract_id
                   and candidate.receivable_created > 0
                   and not candidate.allocated
                order by partner_summary.meal_contract_id asc,
                         candidate.confirmed_at asc,
                         candidate.meal_usage_id asc
            """,
            resultSet -> {
                UUID mealContractId = resultSet.getObject("meal_contract_id", UUID.class);
                if (!partnersByMealContractId.containsKey(mealContractId)) {
                    partnersByMealContractId.put(mealContractId, new PartnerReceivableSummary(
                        new MealContractId(mealContractId),
                        resultSet.getObject("partner_organization_id", UUID.class),
                        resultSet.getString("partner_display_name"),
                        resultSet.getObject("previous_pos_business_date", LocalDate.class),
                        resultSet.getLong("period_confirmed_usage_total_minor"),
                        resultSet.getLong("period_prepaid_applied_total_minor"),
                        resultSet.getLong("outstanding_receivable_count"),
                        resultSet.getLong("outstanding_receivable_total_minor")
                    ));
                }
                UUID mealUsageId = resultSet.getObject("meal_usage_id", UUID.class);
                if (mealUsageId != null) {
                    items.add(new OutstandingReceivable(
                        mealUsageId,
                        new MealContractId(mealContractId),
                        resultSet.getString("candidate_partner_display_name"),
                        resultSet.getTimestamp("confirmed_at").toInstant(),
                        resultSet.getLong("receivable_created")
                    ));
                }
            },
            storeId.value(),
            storeId.value(),
            storeId.value()
        );
        List<PartnerReceivableSummary> partners = partnersByMealContractId.values().stream()
            .sorted(Comparator
                .comparing(PartnerReceivableSummary::partnerDisplayName, Comparator.nullsLast(String::compareTo))
                .thenComparing(summary -> summary.mealContractId().value()))
            .toList();
        return new OutstandingReceivableOverview(items, partners);
    }

    @Override
    public Optional<CumulativeSettlementSnapshot> findCumulativeSettlementSnapshotByMealContractIdAndStoreId(
        MealContractId mealContractId,
        StoreId storeId,
        Instant generatedAt
    ) {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(generatedAt, "Generated at must be supplied");
        return jdbcTemplate.query(
            """
                with contract_scope as (
                    select id, prepaid_balance
                    from meal_contracts
                    where id = ? and store_id = ?
                ),
                confirmed_usage_totals as (
                    select coalesce(sum(amount), 0) as confirmed_usage_total_minor,
                           coalesce(sum(prepaid_applied), 0) as prepaid_applied_total_minor,
                           coalesce(sum(receivable_created), 0) as receivable_created_total_minor
                    from meal_usages
                    where store_id = ?
                      and meal_contract_id = ?
                      and status = 'CONFIRMED'
                ),
                recorded_payment_totals as (
                    select coalesce(sum(submitted_total_minor), 0) as recorded_pos_payment_total_minor
                    from pos_settlements
                    where store_id = ?
                      and meal_contract_id = ?
                ),
                allocated_receivable_totals as (
                    select coalesce(sum(allocation.receivable_amount_minor), 0) as allocated_receivable_total_minor
                    from pos_settlement_allocations allocation
                    join pos_settlements settlement
                        on settlement.id = allocation.pos_settlement_id
                       and settlement.store_id = ?
                    join meal_usages meal_usage
                        on meal_usage.id = allocation.meal_usage_id
                       and meal_usage.store_id = ?
                       and meal_usage.meal_contract_id = ?
                    where settlement.meal_contract_id = ?
                )
                select contract_scope.id as meal_contract_id,
                       contract_scope.prepaid_balance,
                       confirmed_usage_totals.confirmed_usage_total_minor,
                       confirmed_usage_totals.prepaid_applied_total_minor,
                       confirmed_usage_totals.receivable_created_total_minor,
                       recorded_payment_totals.recorded_pos_payment_total_minor,
                       allocated_receivable_totals.allocated_receivable_total_minor
                from contract_scope
                cross join confirmed_usage_totals
                cross join recorded_payment_totals
                cross join allocated_receivable_totals
                """,
            (resultSet, rowNum) -> new CumulativeSettlementSnapshot(
                new MealContractId(resultSet.getObject("meal_contract_id", UUID.class)),
                generatedAt,
                resultSet.getLong("confirmed_usage_total_minor"),
                resultSet.getLong("prepaid_applied_total_minor"),
                resultSet.getLong("prepaid_balance"),
                resultSet.getLong("receivable_created_total_minor"),
                resultSet.getLong("recorded_pos_payment_total_minor"),
                resultSet.getLong("allocated_receivable_total_minor")
            ),
            mealContractId.value(),
            storeId.value(),
            storeId.value(),
            mealContractId.value(),
            storeId.value(),
            mealContractId.value(),
            storeId.value(),
            storeId.value(),
            mealContractId.value(),
            mealContractId.value()
        ).stream().findFirst();
    }

    @Override
    public PosSettlementSlice findByStoreId(
        StoreId storeId,
        int page,
        int size,
        String partnerDisplayName,
        String search
    ) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        long offset = Math.multiplyExact((long) page, size);
        List<SettlementHeader> headers = jdbcTemplate.query(
            """
                select id, store_id, meal_contract_id, pos_business_date, submitted_total_minor,
                       recorded_by_login_id, recorded_at, idempotency_key
                from pos_settlements settlement
                where settlement.store_id = ?
                  and exists (
                      select 1
                      from lateral (
                          select coalesce(
                                     meal_usage.partner_display_name,
                                     usage_partner_organization.display_name,
                                     settlement_partner_organization.display_name
                                 ) as partner_display_name
                          from pos_settlement_allocations allocation
                          join meal_usages meal_usage
                              on meal_usage.id = allocation.meal_usage_id
                             and meal_usage.store_id = settlement.store_id
                          left join meal_contracts usage_contract
                              on usage_contract.id = meal_usage.meal_contract_id
                             and usage_contract.store_id = settlement.store_id
                          left join partner_organizations usage_partner_organization
                              on usage_partner_organization.id = usage_contract.partner_organization_id
                          left join meal_contracts settlement_contract
                              on settlement_contract.id = settlement.meal_contract_id
                             and settlement_contract.store_id = settlement.store_id
                          left join partner_organizations settlement_partner_organization
                              on settlement_partner_organization.id = settlement_contract.partner_organization_id
                          where allocation.pos_settlement_id = settlement.id
                      ) partner_name
                      where (cast(? as text) is null or lower(partner_name.partner_display_name) = lower(cast(? as text)))
                        and (cast(? as text) is null or lower(partner_name.partner_display_name) like lower('%' || cast(? as text) || '%'))
                  )
                order by settlement.recorded_at desc, settlement.id desc
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
            partnerDisplayName,
            partnerDisplayName,
            search,
            search,
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
                       coalesce(
                           meal_usage.partner_display_name,
                           usage_partner_organization.display_name,
                           settlement_partner_organization.display_name
                       ) as partner_display_name,
                       meal_usage.confirmed_at,
                       allocation.receivable_amount_minor
                from pos_settlement_allocations allocation
                join pos_settlements settlement
                    on settlement.id = allocation.pos_settlement_id
                   and settlement.store_id = ?
                join meal_usages meal_usage
                    on meal_usage.id = allocation.meal_usage_id
                   and meal_usage.store_id = settlement.store_id
                left join meal_contracts usage_contract
                    on usage_contract.id = meal_usage.meal_contract_id
                   and usage_contract.store_id = settlement.store_id
                left join partner_organizations usage_partner_organization
                    on usage_partner_organization.id = usage_contract.partner_organization_id
                left join meal_contracts settlement_contract
                    on settlement_contract.id = settlement.meal_contract_id
                   and settlement_contract.store_id = settlement.store_id
                left join partner_organizations settlement_partner_organization
                    on settlement_partner_organization.id = settlement_contract.partner_organization_id
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
