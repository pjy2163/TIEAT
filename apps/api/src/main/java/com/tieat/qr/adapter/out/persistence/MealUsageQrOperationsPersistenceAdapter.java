package com.tieat.qr.adapter.out.persistence;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.qr.domain.MealUsageQrOperationsRepository;
import com.tieat.qr.domain.QrOperationAudit;
import com.tieat.store.domain.StoreId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MealUsageQrOperationsPersistenceAdapter implements MealUsageQrOperationsRepository {

    private final JdbcTemplate jdbcTemplate;

    public MealUsageQrOperationsPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public void lockStoreForOperations(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(?)",
            statement -> statement.setLong(1, lockKey(storeId)),
            resultSet -> null
        );
    }

    @Override
    public boolean enabledStoreAccountExists(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Boolean exists = jdbcTemplate.queryForObject(
            "select exists (select 1 from store_accounts where store_id = ? and enabled = true)",
            Boolean.class,
            storeId.value()
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<MealUsageQrContext> findCurrentByStoreId(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return jdbcTemplate.query(
            """
                select id, store_id, store_display_name, token_hash, created_at, expires_at, revoked_at,
                       token_ciphertext, token_nonce, token_key_version
                from meal_usage_qr_contexts
                where store_id = ? and revoked_at is null
                """,
            (resultSet, rowNum) -> toContext(resultSet),
            storeId.value()
        ).stream().findFirst();
    }

    @Override
    public Optional<MealUsageQrContext> findCurrentByStoreIdForUpdate(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return jdbcTemplate.query(
            """
                select id, store_id, store_display_name, token_hash, created_at, expires_at, revoked_at,
                       token_ciphertext, token_nonce, token_key_version
                from meal_usage_qr_contexts
                where store_id = ? and revoked_at is null
                for update
                """,
            (resultSet, rowNum) -> toContext(resultSet),
            storeId.value()
        ).stream().findFirst();
    }

    @Override
    public void insert(MealUsageQrContext context) {
        Objects.requireNonNull(context, "Meal usage QR context must be supplied");
        jdbcTemplate.update(
            """
                insert into meal_usage_qr_contexts
                    (id, store_id, store_display_name, token_hash, expires_at, revoked_at, created_at,
                     token_ciphertext, token_nonce, token_key_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            context.id().value(),
            context.storeId().value(),
            context.storeDisplayName(),
            context.tokenHash(),
            Timestamp.from(context.expiresAt()),
            context.revokedAt().map(Timestamp::from).orElse(null),
            Timestamp.from(context.issuedAt()),
            context.protectedToken().map(MealUsageQrContext.ProtectedToken::ciphertext).orElse(null),
            context.protectedToken().map(MealUsageQrContext.ProtectedToken::nonce).orElse(null),
            context.protectedToken().map(MealUsageQrContext.ProtectedToken::keyVersion).orElse(null)
        );
    }

    @Override
    public void revoke(MealUsageQrContextId contextId, Instant revokedAt) {
        Objects.requireNonNull(contextId, "Meal usage QR context id must be supplied");
        Objects.requireNonNull(revokedAt, "QR revocation time must be supplied");
        int updated = jdbcTemplate.update(
            "update meal_usage_qr_contexts set revoked_at = ? where id = ? and revoked_at is null",
            Timestamp.from(revokedAt),
            contextId.value()
        );
        if (updated != 1) {
            throw new IllegalStateException("Current meal usage QR context was not revoked");
        }
    }

    @Override
    public void renew(MealUsageQrContextId contextId, Instant renewedExpiresAt) {
        Objects.requireNonNull(contextId, "Meal usage QR context id must be supplied");
        Objects.requireNonNull(renewedExpiresAt, "QR renewal expiry must be supplied");
        int updated = jdbcTemplate.update(
            "update meal_usage_qr_contexts set expires_at = ? where id = ? and revoked_at is null",
            Timestamp.from(renewedExpiresAt),
            contextId.value()
        );
        if (updated != 1) {
            throw new IllegalStateException("Current meal usage QR context was not renewed");
        }
    }

    @Override
    public List<QrPartnerSelection> findPartnerSelectionsByStoreId(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return jdbcTemplate.query(
            """
                select meal_contract.id, partner_organization.display_name, meal_contract.qr_selectable
                from meal_contracts meal_contract
                join partner_organizations partner_organization
                    on partner_organization.id = meal_contract.partner_organization_id
                where meal_contract.store_id = ? and meal_contract.archived_at is null
                order by partner_organization.display_name asc, meal_contract.id asc
                """,
            (resultSet, rowNum) -> toPartnerSelection(resultSet),
            storeId.value()
        );
    }

    @Override
    public Optional<QrPartnerSelection> findPartnerSelectionByIdAndStoreIdForUpdate(
        MealContractId mealContractId,
        StoreId storeId
    ) {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return jdbcTemplate.query(
            """
                select meal_contract.id, partner_organization.display_name, meal_contract.qr_selectable
                from meal_contracts meal_contract
                join partner_organizations partner_organization
                    on partner_organization.id = meal_contract.partner_organization_id
                where meal_contract.id = ? and meal_contract.store_id = ? and meal_contract.archived_at is null
                for update of meal_contract
                """,
            (resultSet, rowNum) -> toPartnerSelection(resultSet),
            mealContractId.value(),
            storeId.value()
        ).stream().findFirst();
    }

    @Override
    public void updateQrSelectable(MealContractId mealContractId, StoreId storeId, boolean qrSelectable) {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        int updated = jdbcTemplate.update(
            "update meal_contracts set qr_selectable = ? where id = ? and store_id = ?",
            qrSelectable,
            mealContractId.value(),
            storeId.value()
        );
        if (updated != 1) {
            throw new IllegalStateException("QR partner contract was not updated");
        }
    }

    @Override
    public void appendAudit(QrOperationAudit audit) {
        Objects.requireNonNull(audit, "QR operation audit must be supplied");
        jdbcTemplate.update(
            """
                insert into meal_usage_qr_operation_audits
                    (id, action, operator_id, store_id, qr_context_id, meal_contract_id,
                     qr_selectable_before, qr_selectable_after, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            audit.id(),
            audit.action().name(),
            audit.operatorId(),
            audit.storeId().value(),
            audit.qrContextId() == null ? null : audit.qrContextId().value(),
            audit.mealContractId() == null ? null : audit.mealContractId().value(),
            audit.qrSelectableBefore(),
            audit.qrSelectableAfter(),
            Timestamp.from(audit.occurredAt())
        );
    }

    private MealUsageQrContext toContext(ResultSet resultSet) throws SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        byte[] tokenCiphertext = resultSet.getBytes("token_ciphertext");
        byte[] tokenNonce = resultSet.getBytes("token_nonce");
        int tokenKeyVersion = resultSet.getInt("token_key_version");
        MealUsageQrContext.ProtectedToken protectedToken = null;
        if (tokenCiphertext != null || tokenNonce != null || tokenKeyVersion != 0) {
            if (tokenCiphertext == null || tokenNonce == null || resultSet.wasNull()) {
                throw new IllegalStateException("QR token protection columns are incomplete");
            }
            protectedToken = new MealUsageQrContext.ProtectedToken(tokenCiphertext, tokenNonce, tokenKeyVersion);
        }
        return new MealUsageQrContext(
            new MealUsageQrContextId(resultSet.getObject("id", UUID.class)),
            new StoreId(resultSet.getObject("store_id", UUID.class)),
            resultSet.getString("store_display_name"),
            resultSet.getString("token_hash"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("expires_at").toInstant(),
            revokedAt == null ? null : revokedAt.toInstant(),
            protectedToken
        );
    }

    private QrPartnerSelection toPartnerSelection(ResultSet resultSet) throws SQLException {
        return new QrPartnerSelection(
            new MealContractId(resultSet.getObject("id", UUID.class)),
            resultSet.getString("display_name"),
            resultSet.getBoolean("qr_selectable")
        );
    }

    private long lockKey(StoreId storeId) {
        UUID value = storeId.value();
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }
}
