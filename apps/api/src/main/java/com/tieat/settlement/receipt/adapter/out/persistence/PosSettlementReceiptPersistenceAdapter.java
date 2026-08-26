package com.tieat.settlement.receipt.adapter.out.persistence;

import com.tieat.settlement.receipt.domain.PosSettlementReceipt;
import com.tieat.store.domain.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PosSettlementReceiptPersistenceAdapter {

    private final JdbcTemplate jdbcTemplate;

    public PosSettlementReceiptPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    public boolean lockSettlement(UUID posSettlementId, StoreId storeId) {
        Objects.requireNonNull(posSettlementId, "POS settlement id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return jdbcTemplate.query(
            """
                select id
                from pos_settlements
                where id = ? and store_id = ?
                for update
                """,
            (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
            posSettlementId,
            storeId.value()
        ).stream().findFirst().isPresent();
    }

    public Optional<PosSettlementReceipt> findBySettlementIdAndStoreId(UUID posSettlementId, StoreId storeId) {
        Objects.requireNonNull(posSettlementId, "POS settlement id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return jdbcTemplate.query(
            """
                select id, pos_settlement_id, store_id, object_key, file_name, content_type,
                       size_bytes, uploaded_at, expires_at, scan_status, deleted_at
                from pos_settlement_receipts
                where pos_settlement_id = ? and store_id = ?
                """,
            (resultSet, rowNum) -> toDomain(resultSet),
            posSettlementId,
            storeId.value()
        ).stream().findFirst();
    }

    public List<PosSettlementReceipt> findBySettlementIdsAndStoreId(List<UUID> posSettlementIds, StoreId storeId) {
        Objects.requireNonNull(posSettlementIds, "POS settlement ids must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        List<UUID> ids = List.copyOf(posSettlementIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> arguments = new ArrayList<>(ids.size() + 1);
        arguments.add(storeId.value());
        arguments.addAll(ids);
        return jdbcTemplate.query(
            """
                select id, pos_settlement_id, store_id, object_key, file_name, content_type,
                       size_bytes, uploaded_at, expires_at, scan_status, deleted_at
                from pos_settlement_receipts
                where store_id = ? and pos_settlement_id in (""" + placeholders + ")",
            (resultSet, rowNum) -> toDomain(resultSet),
            arguments.toArray()
        );
    }

    public void insert(PosSettlementReceipt receipt) {
        Objects.requireNonNull(receipt, "Receipt must be supplied");
        jdbcTemplate.update(
            """
                insert into pos_settlement_receipts
                    (id, pos_settlement_id, store_id, object_key, file_name, content_type,
                     size_bytes, uploaded_at, expires_at, scan_status, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            receipt.id(),
            receipt.posSettlementId(),
            receipt.storeId().value(),
            receipt.objectKey(),
            receipt.fileName(),
            receipt.contentType(),
            receipt.sizeBytes(),
            Timestamp.from(receipt.uploadedAt()),
            Timestamp.from(receipt.expiresAt()),
            receipt.scanStatus().name(),
            receipt.deletedAt() == null ? null : Timestamp.from(receipt.deletedAt())
        );
    }

    public List<PosSettlementReceipt> findExpiredActive(Instant now, int limit) {
        Objects.requireNonNull(now, "Current time must be supplied");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("Cleanup limit must be between 1 and 1000");
        }
        return jdbcTemplate.query(
            """
                select id, pos_settlement_id, store_id, object_key, file_name, content_type,
                       size_bytes, uploaded_at, expires_at, scan_status, deleted_at
                from pos_settlement_receipts
                where deleted_at is null and expires_at <= ?
                order by expires_at asc, id asc
                limit ?
                """,
            (resultSet, rowNum) -> toDomain(resultSet),
            Timestamp.from(now),
            limit
        );
    }

    public void markDeleted(UUID receiptId, Instant deletedAt) {
        Objects.requireNonNull(receiptId, "Receipt id must be supplied");
        Objects.requireNonNull(deletedAt, "Deletion time must be supplied");
        jdbcTemplate.update(
            "update pos_settlement_receipts set deleted_at = ? where id = ? and deleted_at is null",
            Timestamp.from(deletedAt),
            receiptId
        );
    }

    public Set<String> findAllObjectKeys() {
        return Set.copyOf(jdbcTemplate.query(
            "select object_key from pos_settlement_receipts",
            (resultSet, rowNum) -> resultSet.getString("object_key")
        ));
    }

    private PosSettlementReceipt toDomain(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new PosSettlementReceipt(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("pos_settlement_id", UUID.class),
            new StoreId(resultSet.getObject("store_id", UUID.class)),
            resultSet.getString("object_key"),
            resultSet.getString("file_name"),
            resultSet.getString("content_type"),
            resultSet.getLong("size_bytes"),
            resultSet.getTimestamp("uploaded_at").toInstant(),
            resultSet.getTimestamp("expires_at").toInstant(),
            PosSettlementReceipt.ScanStatus.valueOf(resultSet.getString("scan_status")),
            resultSet.getTimestamp("deleted_at") == null ? null : resultSet.getTimestamp("deleted_at").toInstant()
        );
    }
}
